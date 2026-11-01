package dev.altayeb.shoebill.vault

import java.security.SecureRandom
import android.content.Context
import dev.altayeb.shoebill.vault.crypto.BackupFormat
import dev.altayeb.shoebill.vault.crypto.MasterKey
import dev.altayeb.shoebill.vault.crypto.VaultCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.security.GeneralSecurityException
import android.security.keystore.UserNotAuthenticatedException

/** Caches unwrapped keysets while unlocked, so Keystore is only hit when a key is first needed (inside the 30 s window). */
class SessionKeys(private val hw: MasterKey) : MasterKey {
    private val cache = HashMap<String, ByteArray>()
    private fun id(w: ByteArray) = Base64.getEncoder().encodeToString(w)

    @Synchronized override fun wrap(plain: ByteArray): ByteArray {
        val w = hw.wrap(plain)
        cache[id(w)] = plain.copyOf()
        return w
    }

    @Synchronized override fun unwrap(wrapped: ByteArray): ByteArray =
        cache.getOrPut(id(wrapped)) { hw.unwrap(wrapped) }

    @Synchronized fun wipe() {
        cache.values.forEach { it.fill(0) }
        cache.clear()
    }
}

/** One index record. The name, wrapped key and thumbnail key are all ciphertext or wrapped material. */
class VaultEntry(
    val id: String,
    val encName: String,
    val wrappedKey: ByteArray,
    val size: Long,
    val durationMs: Long,
    val mime: String,
    val kind: String, // "video" | "photo"
    val addedAt: Long,
    val thumbId: String?,
    val thumbKey: ByteArray?,
    val encPath: String? = null, // encrypted RELATIVE_PATH of the original gallery folder, e.g. "DCIM/Camera/"
)

/**
 * One unlocked vault: encrypted index, thumbnails and file keys live in memory only; nothing decrypted touches disk.
 * Layout under noBackupFilesDir/vault: `<uuid>.bin` media, `<uuid>.bin` thumbs, `index.bin` (u32 header length,
 * wrapped index key, Tink ciphertext of the JSON list), `namekey.bin` (wrapped name keyset).
 */
class VaultRepo private constructor(
    private val dir: File,
    private var keys: SessionKeys,
    private var crypto: VaultCrypto,
    private val entries: MutableList<VaultEntry>,
    private val thumbs: MutableMap<String, ByteArray>,
) {
    val items: List<VaultEntry> get() = entries.toList()
    fun thumbnails(): Map<String, ByteArray> = thumbs.toMap()
    fun find(id: String): VaultEntry? = entries.firstOrNull { it.id == id }
    fun nameOf(e: VaultEntry): String = crypto.decryptName(e.encName)
    fun pathOf(e: VaultEntry): String? = e.encPath?.let { crypto.decryptName(it) }

    /** Seekable plaintext of one item. Closing it closes the file too. */
    fun openChannel(e: VaultEntry): SeekableByteChannel {
        val file = FileChannel.open(File(dir, "${e.id}.bin").toPath(), READ)
        val plain = try { crypto.openSeekable(file, e.wrappedKey, e.id) } catch (t: Throwable) { file.close(); throw t }
        return object : SeekableByteChannel by plain {
            override fun close() {
                plain.close()
                file.close()
            }
        }
    }

    fun exportTo(e: VaultEntry, out: OutputStream) {
        openChannel(e).use { ch -> Channels.newInputStream(ch).use { it.copyTo(out) } }
    }

    /** Password-protected backup of every item; sizes are the stored plaintext sizes. */
    fun exportBackup(out: OutputStream, password: CharArray) {
        val list = entries.toList()
        val items = list.map { BackupFormat.Item(nameOf(it), it.mime, it.durationMs, thumbs[it.id], it.size) }
        BackupFormat.write(out, password, items, { i -> Channels.newInputStream(openChannel(list[i])) })
    }

    /** Imports a backup made by [exportBackup]; returns how many items were added. */
    /** [addOne] wraps [add] so the caller can re-prompt and retry when Keystore's 30 s window lapses: the key is wrapped
     *  before the item's stream is read, so a retry still has the whole stream. */
    fun importBackup(input: InputStream, password: CharArray, addOne: (() -> Unit) -> Unit, onProgress: (done: Int) -> Unit): Int {
        var n = 0
        BackupFormat.read(input, password) { item, stream ->
            addOne { add(item.name, item.mime, item.durationMs, item.thumb) { stream } }
            onProgress(++n)
        }
        return n
    }

    /** Unwraps every file key now (one Keystore window), so a long export never needs Keystore mid-stream. */
    fun warmKeys() = entries.forEach { keys.unwrap(it.wrappedKey) }

    /** Unwraps one file key now, inside the Keystore window, so the stream server never needs Keystore. */
    fun warmKey(e: VaultEntry) { keys.unwrap(e.wrappedKey) }

    /**
     * Encrypts what [open] returns, proves by full decrypt that the ciphertext matches the source's SHA-256, then commits.
     * A failed verify or any error keeps nothing. Keystore may throw UserNotAuthenticatedException here; the caller retries.
     */
    fun add(name: String, mime: String, durationMs: Long, thumb: ByteArray?, relativePath: String? = null, open: () -> InputStream): VaultEntry {
        val id = UUID.randomUUID().toString()
        val thumbId = thumb?.let { UUID.randomUUID().toString() }
        val part = File(dir, "$id.bin.part")
        try {
            val sealed = open().use { src ->
                val tap = Tap(src)
                part.outputStream().use { out -> Sealed(crypto.encrypt(tap, out, id), tap.count, tap.sha()) }
            }
            verify(part, id, sealed.wrapped, sealed.sha)
            var thumbKey: ByteArray? = null
            if (thumb != null && thumbId != null) thumbKey = writeBlob(thumbId, thumb, thumbId)
            if (!part.renameTo(File(dir, "$id.bin"))) throw IOException("commit failed")
            val entry = VaultEntry(
                id, crypto.encryptName(name), sealed.wrapped, sealed.size, durationMs, mime,
                if (mime.startsWith("video/")) "video" else "photo", System.currentTimeMillis(), thumbId, thumbKey,
                relativePath?.let { crypto.encryptName(it) },
            )
            entries.add(entry)
            if (thumb != null) thumbs[id] = thumb
            try {
                writeIndex()
            } catch (t: Throwable) {
                entries.remove(entry)
                thumbs.remove(id)
                throw t
            }
            return entry
        } catch (t: Throwable) {
            part.delete()
            File(dir, "$id.bin").delete()
            thumbId?.let { File(dir, "$it.bin").delete() }
            throw t
        }
    }

    /** Dropping the index entry drops its wrapped key: the ciphertext is unreadable from that point on. */
    fun remove(id: String) {
        val e = find(id) ?: return
        entries.remove(e)
        try {
            writeIndex()
        } catch (t: Throwable) {
            entries.add(e)
            throw t
        }
        thumbs.remove(id)?.fill(0)
        File(dir, "$id.bin").delete()
        e.thumbId?.let { File(dir, "$it.bin").delete() }
    }

    fun wipe() {
        thumbs.values.forEach { it.fill(0) }
        thumbs.clear()
        entries.clear()
        keys.wipe()
        dataKey?.wipe()
        dataKey = null
    }

    private class Sealed(val wrapped: ByteArray, val size: Long, val sha: ByteArray)

    private class Tap(src: InputStream) : FilterInputStream(src) {
        private val md = MessageDigest.getInstance("SHA-256")
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) { md.update(it.toByte()); count++ } }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) { md.update(b, off, it); count += it } }

        fun sha(): ByteArray = md.digest()
    }

    private fun verify(part: File, id: String, wrapped: ByteArray, expected: ByteArray) {
        val sha = MessageDigest.getInstance("SHA-256")
        FileChannel.open(part.toPath(), READ).use { ch ->
            Channels.newInputStream(crypto.openSeekable(ch, wrapped, id)).use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    sha.update(buf, 0, n)
                }
            }
        }
        if (!sha.digest().contentEquals(expected)) throw IOException("verify failed: ciphertext does not match the source")
    }

    private fun writeBlob(fileId: String, bytes: ByteArray, name: String): ByteArray =
        File(dir, "$name.bin").outputStream().use { crypto.encrypt(ByteArrayInputStream(bytes), it, fileId) }

    private fun readBlob(file: String, wrapped: ByteArray, fileId: String): ByteArray =
        FileChannel.open(File(dir, "$file.bin").toPath(), READ).use { ch ->
            Channels.newInputStream(crypto.openSeekable(ch, wrapped, fileId)).use { it.readBytes() }
        }

    private fun writeIndex() {
        if (!stageIndex(entries, crypto).renameTo(File(dir, INDEX))) throw IOException("index commit failed")
    }

    /** Writes the index to `index.bin.tmp` without committing it. The index key is wrapped by [c]'s master key. */
    private fun stageIndex(list: List<VaultEntry>, c: VaultCrypto): File {
        val json = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString().toByteArray(Charsets.UTF_8)
        val blob = ByteArrayOutputStream()
        val wrapped = c.encrypt(ByteArrayInputStream(json), blob, INDEX_ID)
        val tmp = File(dir, "$INDEX.tmp")
        tmp.outputStream().use { out ->
            out.write(ByteBuffer.allocate(4).putInt(wrapped.size).array())
            out.write(wrapped)
            blob.writeTo(out)
        }
        return tmp
    }

    /**
     * Re-wraps every wrapped key under [newKey]: each file key, each thumbnail key, the name keyset, and the index header
     * (the index is re-encrypted, which draws a new wrapped index key). Everything is unwrapped and staged in memory first;
     * on disk only the two renames at the end change, and a failed second rename puts the name key back.
     * Keystore may throw UserNotAuthenticatedException: nothing is written then, so the caller can retry the whole call.
     */
    private var dataKey: SoftKey? = null

    /** PIN-only on/off: only datakey.bin changes hands (re-wrapped by the new chip key); file keys stay as they are. */
    @Synchronized fun rewrapDataKey(newChipKey: MasterKey) {
        val dk = checkNotNull(dataKey) { "vault not open" }
        writeAtomic(File(dir, DATA_KEY), newChipKey.wrap(dk.raw()))
    }

    @Synchronized fun rekey(newKey: MasterKey) {
        val newKeys = SessionKeys(newKey)
        val oldName = crypto.nameKeyWrapped
        val nameFile = File(dir, NAME_KEY)
        val nameTmp = File(dir, "$NAME_KEY.tmp")
        var staged: File? = null
        var nameSwapped = false
        try {
            val newName = newKeys.wrap(keys.unwrap(oldName))
            val newEntries = entries.map { e ->
                VaultEntry(
                    e.id, e.encName, newKeys.wrap(keys.unwrap(e.wrappedKey)), e.size, e.durationMs, e.mime, e.kind, e.addedAt,
                    e.thumbId, e.thumbKey?.let { newKeys.wrap(keys.unwrap(it)) }, e.encPath,
                )
            }
            val newCrypto = VaultCrypto(newKeys, newName)
            staged = stageIndex(newEntries, newCrypto)
            nameTmp.writeBytes(newName)
            if (!nameTmp.renameTo(nameFile)) throw IOException("name key commit failed")
            nameSwapped = true
            if (!staged.renameTo(File(dir, INDEX))) throw IOException("index commit failed")
            keys.wipe()
            keys = newKeys
            crypto = newCrypto
            entries.clear()
            entries.addAll(newEntries)
        } catch (t: Throwable) {
            if (nameSwapped) runCatching { nameTmp.writeBytes(oldName); nameTmp.renameTo(nameFile) }
            nameTmp.delete()
            staged?.delete()
            newKeys.wipe()
            throw t
        }
    }

    private fun sweepOrphans() {
        val keep = HashSet<String>().apply {
            add(INDEX)
            add(NAME_KEY)
            add(DATA_KEY) // losing it loses every file key: never sweep it
            add(DATA_KEY_DONE)
            entries.forEach { add("${it.id}.bin"); it.thumbId?.let { t -> add("$t.bin") } }
        }
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    companion object {
        private const val INDEX = "index.bin"
        private const val INDEX_ID = "index"
        private const val NAME_KEY = "namekey.bin"
        private const val DATA_KEY = "datakey.bin"
        private const val DATA_KEY_DONE = "datakey.done"

        private fun writeAtomic(f: File, bytes: ByteArray) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) throw IOException("commit failed: ${f.name}")
        }

        /** Deletes a space's files and its Keystore key. Plain delete, nothing overwritten. */
        fun wipeAll(context: Context, space: VaultSpace) {
            File(context.noBackupFilesDir, space.dir).deleteRecursively()
            KeystoreMasterKey.delete(space.alias)
            KeystoreMasterKey.delete(KeystoreMasterKey.pinOnlyAlias(space.alias))
            KeystoreMasterKey.setPinOnlyFlag(context, space, false)
        }

        /**
         * Opens the vault, creating the name key on first use. Must run inside the auth gate: the first
         * Keystore call after a lapsed window throws UserNotAuthenticatedException. Retrying is safe, nothing is persisted before it succeeds.
         */
        fun open(context: Context, space: VaultSpace = VaultSpace.Real): VaultRepo {
            val dir = File(context.noBackupFilesDir, space.dir).apply { mkdirs() }
            val active = KeystoreMasterKey.activeAlias(context, space)
            // Both aliases exist only if a PIN-only switch was interrupted: then each blob is wrapped by exactly one of
            // them, so unwrap tries the active key and falls back to the other. The finished switch deletes the old alias.
            val other = if (active.endsWith("_pin")) active.removeSuffix("_pin") else active + "_pin"
            val master: MasterKey = KeystoreMasterKey.load(active).let { primary ->
                if (!KeystoreMasterKey.exists(other)) primary else EitherKey(primary, KeystoreMasterKey.load(other))
            }
            // Vault data key: unwrapped once here (the only chip/Keystore call of a visit), then every per-file, thumbnail,
            // name and index key is wrapped by it in memory, so nothing re-prompts until lock. Older vaults hold
            // chip-wrapped blobs: EitherKey opens those, and the rekey below moves them to the data key once.
            val dataFile = File(dir, DATA_KEY)
            val dataKey = if (dataFile.exists()) master.unwrap(dataFile.readBytes())
                else ByteArray(32).also { SecureRandom().nextBytes(it) }.also { writeAtomic(dataFile, master.wrap(it)) }
            val soft = SoftKey(dataKey)
            val keys = SessionKeys(EitherKey(soft, master))
            val nameFile = File(dir, NAME_KEY)
            val crypto = VaultCrypto(keys, nameFile.takeIf { it.exists() }?.readBytes())
            if (!nameFile.exists()) nameFile.writeBytes(crypto.nameKeyWrapped)
            val indexFile = File(dir, INDEX)
            val entries = if (indexFile.exists()) readIndex(indexFile, crypto) else mutableListOf()
            val repo = VaultRepo(dir, keys, crypto, entries, HashMap())
            // Unwrap everything now, while the 30 s window is open; playback, export and thumbnails then never need Keystore.
            for (e in entries) {
                keys.unwrap(e.wrappedKey)
                if (e.thumbId != null && e.thumbKey != null) {
                    keys.unwrap(e.thumbKey)
                    repo.thumbs[e.id] = repo.readBlob(e.thumbId, e.thumbKey, e.thumbId)
                }
            }
            repo.sweepOrphans()
            repo.dataKey = soft
            if (!File(dir, DATA_KEY_DONE).exists()) {
                repo.rekey(soft)
                File(dir, DATA_KEY_DONE).writeBytes(ByteArray(0))
            }
            return repo
        }

        private fun readIndex(file: File, crypto: VaultCrypto): MutableList<VaultEntry> {
            val bytes = file.readBytes()
            val hlen = ByteBuffer.wrap(bytes, 0, 4).int
            val wrapped = bytes.copyOfRange(4, 4 + hlen)
            val json = FileChannel.open(file.toPath(), READ).use { ch ->
                Channels.newInputStream(crypto.openSeekable(OffsetChannel(ch, 4L + hlen), wrapped, INDEX_ID)).use { it.readBytes() }
            }
            val arr = JSONArray(String(json, Charsets.UTF_8))
            return MutableList(arr.length()) { parseEntry(arr.getJSONObject(it)) }
        }

        private fun parseEntry(o: JSONObject) = VaultEntry(
            id = o.getString("id"),
            encName = o.getString("encName"),
            wrappedKey = Base64.getDecoder().decode(o.getString("wrappedKey")),
            size = o.getLong("size"),
            durationMs = o.getLong("durationMs"),
            mime = o.getString("mime"),
            kind = o.getString("kind"),
            addedAt = o.getLong("addedAt"),
            thumbId = if (o.isNull("thumbId")) null else o.getString("thumbId"),
            thumbKey = if (o.isNull("thumbKey")) null else Base64.getDecoder().decode(o.getString("thumbKey")),
            encPath = if (o.has("encPath") && !o.isNull("encPath")) o.getString("encPath") else null,
        )

        private fun VaultEntry.toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("encName", encName)
            .put("wrappedKey", Base64.getEncoder().encodeToString(wrappedKey))
            .put("size", size)
            .put("durationMs", durationMs)
            .put("mime", mime)
            .put("kind", kind)
            .put("addedAt", addedAt)
            .put("thumbId", thumbId ?: JSONObject.NULL)
            .put("thumbKey", thumbKey?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL)
            .apply { encPath?.let { put("encPath", it) } }

        /** Index header sits in front of the Tink ciphertext; this view makes the ciphertext start at offset 0 again. */
        private class OffsetChannel(private val ch: SeekableByteChannel, private val base: Long) : SeekableByteChannel by ch {
            override fun position(): Long = ch.position() - base
            override fun position(newPosition: Long): SeekableByteChannel = apply { ch.position(base + newPosition) }
            override fun size(): Long = ch.size() - base
        }
    }
}

/** Wraps with [primary]; unwraps with [primary], else [fallback] (recovery after an interrupted re-key). */
private class EitherKey(private val primary: MasterKey, private val fallback: MasterKey) : MasterKey {
    override fun wrap(plain: ByteArray): ByteArray = primary.wrap(plain)
    override fun unwrap(wrapped: ByteArray): ByteArray = try {
        primary.unwrap(wrapped)
    } catch (e: GeneralSecurityException) {
        if (e is UserNotAuthenticatedException) throw e // needs a prompt, not the other key
        fallback.unwrap(wrapped)
    }
}

/** The vault data key in memory (AES-256-GCM, 12-byte nonce prefix). Lives only while the vault is unlocked. */
private class SoftKey(private val key: ByteArray) : MasterKey {
    private val spec get() = javax.crypto.spec.SecretKeySpec(key, "AES") // per use: SecretKeySpec copies, wipe() must reach every copy we keep
    // after wipe() the bytes are zero: refuse rather than wrap anything under an all-zero key
    @Volatile private var closed = false
    fun raw(): ByteArray = key.copyOf()
    fun wipe() { closed = true; key.fill(0) }
    override fun wrap(plain: ByteArray): ByteArray {
        if (closed) throw GeneralSecurityException("vault locked")
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, spec, javax.crypto.spec.GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(plain)
    }
    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (closed) throw GeneralSecurityException("vault locked")
        if (wrapped.size < 28) throw GeneralSecurityException("not a data-key blob")
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, spec, javax.crypto.spec.GCMParameterSpec(128, wrapped, 0, 12))
        return c.doFinal(wrapped, 12, wrapped.size - 12)
    }
}
