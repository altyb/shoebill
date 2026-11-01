package dev.altayeb.shoebill.library

import android.content.Context
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.library.logic.HostKeyVerdict
import dev.altayeb.shoebill.library.logic.RemoteEntry
import dev.altayeb.shoebill.library.logic.RemoteKind
import dev.altayeb.shoebill.library.logic.RemoteLogic
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jce.provider.BouncyCastleProvider
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.time.Duration
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.TimeUnit

/** One open file on a saved server. Reads are random access: the player's proxy seeks. */
interface RemoteFile : AutoCloseable {
    val size: Long

    /** Reads up to [len] bytes at [offset] into [buf]. Returns the count, 0 at the end. */
    fun read(offset: Long, buf: ByteArray, len: Int): Int
}

/** One connection to a saved server. Paths are "/"-separated and relative to the server root. */
interface RemoteLink : AutoCloseable {
    fun list(path: String): List<RemoteEntry>
    fun open(path: String): RemoteFile
}

fun openRemote(c: Context, s: SmbServer): RemoteLink = when (s.kind) {
    RemoteKind.WEBDAV -> WebDavLink(c, s.host, s.user, SmbStore.password(c, s.id))
    RemoteKind.SFTP -> SftpLink(c, s)
    RemoteKind.FTP -> FtpLink(c, s)
    else -> SmbLink(c, s)
}

private const val TIMEOUT_MS = 15_000
private const val MAX_LISTING = 4 shl 20
private const val SKIP_LIMIT = 1L shl 20
private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

/** One client for every WebDAV link: each OkHttpClient owns a thread pool and a connection pool that outlive the link. Holds no credentials. */
private val webDavHttp by lazy {
    OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()
}

/**
 * WebDAV over OkHttp. The password goes in an Authorization header, never in the URL, and mpv never sees it: playback
 * reads through the proxy in [SmbProvider]. OkHttp drops that header if a redirect leaves the host.
 */
class WebDavLink(private val c: Context, base: String, user: String, pass: String) : RemoteLink {
    private val base = base.trimEnd('/')
    private val auth = if (user.isEmpty()) null else Credentials.basic(user, pass, Charsets.UTF_8)
    private val rootPath = RemoteLogic.hrefSegments(URL(this.base).path)
    private val http get() = webDavHttp

    private fun segs(path: String): List<String> = RemoteLogic.segments(path) ?: throw IOException(c.getString(R.string.remote_bad_path))

    private fun url(path: String): String = base + segs(path).joinToString("") { "/" + RemoteLogic.encodeSegment(it) }

    private fun request(url: String) = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }

    override fun list(path: String): List<RemoteEntry> {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        http.newCall(request(url(path)).header("Depth", "1").method("PROPFIND", body).build()).execute().use { reply ->
            if (!reply.isSuccessful) throw IOException(c.getString(R.string.remote_http, reply.code))
            val xml = readCapped(reply.body!!.byteStream())
            return try {
                RemoteLogic.parseWebDavListing(xml, rootPath + segs(path))
            } catch (e: Exception) {
                throw IOException(c.getString(R.string.remote_bad_listing), e)
            }
        }
    }

    override fun open(path: String): RemoteFile {
        val u = url(path)
        val total = http.newCall(request(u).head().build()).execute().use { reply ->
            if (!reply.isSuccessful) throw IOException(c.getString(R.string.remote_http, reply.code))
            reply.header("Content-Length")?.toLongOrNull() ?: throw IOException(c.getString(R.string.remote_no_size))
        }
        return StreamFile(total) { offset ->
            val reply = http.newCall(request(u).header("Range", "bytes=$offset-").build()).execute()
            // A server that ignores Range sends the whole body, which is only right from byte 0.
            if (reply.code != 206 && !(reply.code == 200 && offset == 0L)) {
                reply.close()
                throw IOException(c.getString(R.string.remote_range, reply.code))
            }
            OpenStream(reply.body!!.byteStream()) { reply.close() }
        }
    }

    override fun close() {}

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val b = ByteArray(8192)
        while (out.size() < MAX_LISTING) {
            val n = input.read(b)
            if (n < 0) break
            out.write(b, 0, minOf(n, MAX_LISTING - out.size()))
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

private class OpenStream(val input: InputStream, val close: () -> Unit)

/**
 * Holds one stream open across sequential reads, because the player asks for small pieces and a new request per piece
 * cannot keep up with video. A seek or a dropped connection (for example after a long pause) opens a new stream.
 */
private class StreamFile(override val size: Long, private val openAt: (Long) -> OpenStream) : RemoteFile {
    private var current: OpenStream? = null
    private var position = 0L

    private fun drop() {
        current?.let { runCatching { it.close() } }
        current = null
    }

    private fun readOnce(offset: Long, buf: ByteArray, len: Int): Int {
        val gap = offset - position
        if (current != null && gap in 1..SKIP_LIMIT) {
            var left = gap
            val skip = ByteArray(minOf(gap, 64L * 1024).toInt())
            while (left > 0) {
                val n = current!!.input.read(skip, 0, minOf(left, skip.size.toLong()).toInt())
                if (n < 0) throw IOException("stream ended early")
                left -= n
            }
            position = offset
        } else if (current == null || gap != 0L) {
            drop()
            current = openAt(offset)
            position = offset
        }
        var done = 0
        while (done < len) {
            val n = current!!.input.read(buf, done, len - done)
            if (n < 0) break
            done += n
            position += n
        }
        return done
    }

    override fun read(offset: Long, buf: ByteArray, len: Int): Int = try {
        readOnce(offset, buf, len)
    } catch (e: IOException) {
        drop()
        readOnce(offset, buf, len)
    }

    override fun close() = drop()
}

/**
 * sshj asks for provider "BC" by name, and Android's own "BC" is cut down (no X25519). Other crypto in the app names no
 * provider, or AndroidKeyStore, so swapping this one and adding it last leaves those choices as they were.
 */
private fun useFullBouncyCastle() = synchronized(Security::class.java) {
    if (Security.getProvider("BC") !is BouncyCastleProvider) {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }
}

/**
 * SFTP through sshj. The first connection stores the server's key fingerprint; later connections are refused with a
 * clear message if it differs. Password only, like the other kinds.
 */
class SftpLink(private val c: Context, s: SmbServer) : RemoteLink {
    private val root = s.share
    private val ssh = run { useFullBouncyCastle(); SSHClient() }
    private var keyChanged = false
    private val sftp: SFTPClient

    init {
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                val fp = fingerprint(key ?: return false)
                return when (RemoteLogic.hostKey(SmbStore.hostKey(c, s.id), fp)) {
                    HostKeyVerdict.New -> { SmbStore.setHostKey(c, s.id, fp); true }
                    HostKeyVerdict.Same -> true
                    is HostKeyVerdict.Changed -> { keyChanged = true; false }
                }
            }

            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        })
        try {
            ssh.connectTimeout = TIMEOUT_MS
            ssh.timeout = TIMEOUT_MS
            ssh.connect(s.host, s.port)
            try {
                ssh.authPassword(s.user, SmbStore.password(c, s.id))
            } catch (e: UserAuthException) {
                throw IOException(c.getString(R.string.remote_login_failed), e)
            }
            sftp = ssh.newSFTPClient()
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            if (keyChanged) throw IOException(c.getString(R.string.remote_hostkey_changed, s.host), e)
            throw e
        }
    }

    private fun full(path: String): String = RemoteLogic.remotePath(root, path) ?: throw IOException(c.getString(R.string.remote_bad_path))

    override fun list(path: String): List<RemoteEntry> =
        RemoteLogic.clean(sftp.ls(full(path)).map { RemoteEntry(it.name, it.isDirectory, it.attributes.size) })

    override fun open(path: String): RemoteFile {
        val f = sftp.open(full(path))
        return object : RemoteFile {
            override val size = f.length()
            override fun read(offset: Long, buf: ByteArray, len: Int): Int = f.read(offset, buf, 0, len).coerceAtLeast(0)
            override fun close() = f.close()
        }
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }

    private fun fingerprint(k: PublicKey): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(k.encoded))
}

/**
 * FTP through commons-net. FTP sends the password in clear text; the add form says so. Each read opens a data
 * connection at the wanted offset (REST), so seeking works without holding a transfer open.
 */
class FtpLink(private val c: Context, private val s: SmbServer) : RemoteLink {
    private val root = s.share
    private var ftp = connect()

    private fun connect(): FTPClient {
        val client = FTPClient()
        try {
            client.controlEncoding = "UTF-8" // Must be set before connecting, or Arabic names arrive broken.
            client.connectTimeout = TIMEOUT_MS
            client.defaultTimeout = TIMEOUT_MS
            client.connect(s.host, s.port)
            client.soTimeout = TIMEOUT_MS
            client.setDataTimeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
            if (!client.login(s.user.ifEmpty { "anonymous" }, SmbStore.password(c, s.id))) throw IOException(c.getString(R.string.remote_login_failed))
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            return client
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    private fun full(path: String): String = RemoteLogic.remotePath(root, path) ?: throw IOException(c.getString(R.string.remote_bad_path))

    override fun list(path: String): List<RemoteEntry> =
        RemoteLogic.clean(ftp.listFiles(full(path)).filterNotNull().map { RemoteEntry(it.name, it.isDirectory, it.size) })

    override fun open(path: String): RemoteFile {
        val p = full(path)
        val name = p.substringAfterLast('/')
        val total = ftp.listFiles(p.substringBeforeLast('/').ifEmpty { "/" }).firstOrNull { it?.name == name }?.size
            ?: throw IOException(c.getString(R.string.remote_not_found))
        return StreamFile(total) { offset ->
            // Servers drop an idle control connection during a long pause; StreamFile retries once and lands here.
            if (!runCatching { ftp.sendNoOp() }.getOrDefault(false)) {
                runCatching { ftp.disconnect() }
                ftp = connect()
            }
            ftp.restartOffset = offset
            val input = ftp.retrieveFileStream(p) ?: throw IOException(c.getString(R.string.remote_not_found))
            OpenStream(input) {
                input.close()
                // Stopping early makes the server report an aborted transfer; the next command still works.
                ftp.completePendingCommand()
            }
        }
    }

    override fun close() {
        runCatching { ftp.logout() }
        runCatching { ftp.disconnect() }
    }
}
