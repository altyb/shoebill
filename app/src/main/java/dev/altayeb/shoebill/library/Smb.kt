package dev.altayeb.shoebill.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.EnumSet
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException

data class SmbServer(val id: String, val host: String, val port: Int, val share: String, val user: String)
data class SmbEntry(val name: String, val dir: Boolean, val size: Long)

/** Saved shares. The password is encrypted under a key in the Android Keystore and never leaves the phone. */
object SmbStore {
    private fun prefs(c: Context) = c.getSharedPreferences("smb", Context.MODE_PRIVATE)

    fun list(c: Context): List<SmbServer> = runCatching {
        val a = JSONArray(prefs(c).getString("servers", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            SmbServer(o.getString("id"), o.getString("host"), o.getInt("port"), o.getString("share"), o.getString("user"))
        }
    }.getOrDefault(emptyList())

    fun add(c: Context, s: SmbServer, password: String) {
        val a = JSONArray()
        (list(c) + s).forEach {
            a.put(JSONObject().put("id", it.id).put("host", it.host).put("port", it.port).put("share", it.share).put("user", it.user))
        }
        prefs(c).edit().putString("servers", a.toString()).putString("pw_${s.id}", encrypt(password)).apply()
    }

    fun remove(c: Context, id: String) {
        val a = JSONArray()
        list(c).filter { it.id != id }.forEach {
            a.put(JSONObject().put("id", it.id).put("host", it.host).put("port", it.port).put("share", it.share).put("user", it.user))
        }
        prefs(c).edit().putString("servers", a.toString()).remove("pw_$id").apply()
    }

    fun password(c: Context, id: String): String = prefs(c).getString("pw_$id", null)?.let(::decrypt).orEmpty()

    fun newId(): String = UUID.randomUUID().toString()

    private const val ALIAS = "smb_passwords"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        }
        return String(c.doFinal(b, 12, b.size - 12))
    }
}

/** Connects with NTLM only: smbj's default list also loads Kerberos, whose javax classes Android does not have. */
class SmbLink(context: Context, s: SmbServer) : AutoCloseable {
    private val client = SMBClient(
        SmbConfig.builder().withSecurityProvider(BCSecurityProvider())
            .withAuthenticators(NtlmAuthenticator.Factory()).build(),
    )
    private val connection: Connection = client.connect(s.host, s.port)
    private val session: Session = connection.authenticate(
        if (s.user.isEmpty()) AuthenticationContext.guest()
        else AuthenticationContext(s.user, SmbStore.password(context, s.id).toCharArray(), null),
    )
    val share = session.connectShare(s.share) as DiskShare

    fun list(path: String): List<SmbEntry> = share.list(path)
        .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }
        .map {
            SmbEntry(
                it.fileName,
                it.fileAttributes and 0x10L != 0L, // FILE_ATTRIBUTE_DIRECTORY
                it.endOfFile,
            )
        }
        .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))

    override fun close() { runCatching { client.close() } }
}

/**
 * content://<app>.smb/<server id>/<path>: the player opens a share file like any other video, so resume, casting and
 * the rest need no special case. Not exported; reads arrive as a seekable proxy file descriptor, as in the vault.
 */
class SmbProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val seg = uri.pathSegments
        val server = SmbStore.list(ctx).firstOrNull { it.id == seg.firstOrNull() } ?: throw FileNotFoundException("unknown share")
        val path = seg.drop(1).joinToString("\\")
        try {
            val link = SmbLink(ctx, server)
            val file = link.share.openFile(
                path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null,
            )
            val size = file.fileInformation.standardInformation.endOfFile
            val cb = object : ProxyFileDescriptorCallback() {
                override fun onGetSize() = size
                override fun onRead(offset: Long, length: Int, data: ByteArray): Int = try {
                    if (offset >= size) 0 else file.read(data, offset, 0, minOf(length.toLong(), size - offset).toInt()).coerceAtLeast(0)
                } catch (e: Exception) {
                    throw ErrnoException("onRead", OsConstants.EIO)
                }
                override fun onRelease() {
                    runCatching { file.close() }
                    link.close()
                }
            }
            return ctx.getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, cb, handler)
        } catch (e: Exception) {
            throw FileNotFoundException(e.message)
        }
    }

    override fun getType(uri: Uri): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(uri.lastPathSegment.orEmpty().substringAfterLast('.').lowercase()) ?: "video/mp4"

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        private val handler by lazy { Handler(HandlerThread("smb-fd").apply { start() }.looper) }

        fun uri(c: Context, id: String, path: String): Uri = Uri.Builder().scheme("content").authority("${c.packageName}.smb")
            .appendPath(id).apply { path.split('\\').filter { it.isNotEmpty() }.forEach { appendPath(it) } }.build()
    }
}
