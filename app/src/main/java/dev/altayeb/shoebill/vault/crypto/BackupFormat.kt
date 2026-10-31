package dev.altayeb.shoebill.vault.crypto

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.*
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected vault backup. Plaintext header, then a Tink AES-GCM-HKDF stream (4 KiB segments).
 *
 * Header: MAGIC "SHOEVB1\n" (or OLD_MAGIC in backups from 0.3.0 and earlier) | version 1 | iterations (Int) | salt (16) | verifier (32).
 * PBKDF2-HMAC-SHA256 over the password gives 64 bytes: ikm = first 32 (stream key), checkKey = last 32.
 * verifier = HMAC(checkKey, label), so a wrong password fails before any stream byte is touched.
 * The whole header is the stream's AAD, so it cannot be altered either.
 * Body: item count, then per item name, mime, durationMs, size, thumb (or -1), then exactly `size` bytes.
 */
object BackupFormat {
    class Item(val name: String, val mime: String, val durationMs: Long, val thumb: ByteArray?, val size: Long)
    class BadPasswordException(msg: String) : IOException(msg)
    class CorruptBackupException(msg: String) : IOException(msg)

    private val MAGIC = "SHOEVB1\n".toByteArray(Charsets.US_ASCII)
    private val OLD_MAGIC = "ANONVB1\n".toByteArray(Charsets.US_ASCII) // read only, so old backups keep restoring
    private const val VERSION = 1
    private const val SALT_LEN = 16
    private const val VERIFY_LEN = 32
    private const val HEADER_LEN = 8 + 1 + 4 + SALT_LEN + VERIFY_LEN
    private const val MIN_ITER = 1000
    private const val MAX_ITER = 10_000_000 // a crafted file must not hang the import
    private const val SEGMENT = 4096
    private const val COPY = 64 * 1024
    private const val MAX_COUNT = 1_000_000
    private const val MAX_THUMB = 16 * 1024 * 1024
    private val LABEL = "shoebill-vault-backup-v1".toByteArray(Charsets.UTF_8)
    private val OLD_LABEL = "anon-vault-backup-v1".toByteArray(Charsets.UTF_8)
    private val RNG = SecureRandom()

    fun write(out: OutputStream, password: CharArray, items: List<Item>, open: (Int) -> InputStream, iterations: Int = 600_000) {
        val salt = ByteArray(SALT_LEN).also { RNG.nextBytes(it) }
        val (ikm, checkKey) = deriveKeys(password, salt, iterations)
        val h = ByteArrayOutputStream()
        DataOutputStream(h).apply {
            write(MAGIC); writeByte(VERSION); writeInt(iterations); write(salt); write(verifierOf(checkKey, LABEL))
        }
        val header = h.toByteArray()
        out.write(header)
        val enc = AesGcmHkdfStreaming(ikm, "HmacSha256", 32, SEGMENT, 0).newEncryptingStream(NonClosing(out), header)
        val data = DataOutputStream(enc)
        data.writeInt(items.size)
        items.forEachIndexed { i, item ->
            data.writeUTF(item.name); data.writeUTF(item.mime)
            data.writeLong(item.durationMs); data.writeLong(item.size)
            data.writeInt(item.thumb?.size ?: -1)
            item.thumb?.let { data.write(it) }
            open(i).use { src ->
                var left = item.size
                val buf = ByteArray(COPY)
                while (left > 0) {
                    val n = src.read(buf, 0, minOf(left, COPY.toLong()).toInt())
                    if (n < 0) throw IOException("'${item.name}': source ${item.size - left} bytes, declared ${item.size}")
                    data.write(buf, 0, n)
                    left -= n
                }
                if (src.read() >= 0) throw IOException("'${item.name}': source longer than declared ${item.size}")
            }
        }
        enc.close() // writes the final segment; NonClosing keeps `out` open
    }

    fun read(input: InputStream, password: CharArray, onItem: (Item, InputStream) -> Unit) {
        try {
            val header = ByteArray(HEADER_LEN).also { DataInputStream(input).readFully(it) }
            val hd = DataInputStream(ByteArrayInputStream(header))
            val magic = ByteArray(MAGIC.size).also { hd.readFully(it) }
            val old = magic.contentEquals(OLD_MAGIC)
            if (!(old || magic.contentEquals(MAGIC)) || hd.readUnsignedByte() != VERSION) throw CorruptBackupException("not a vault backup")
            val iterations = hd.readInt()
            if (iterations !in MIN_ITER..MAX_ITER) throw CorruptBackupException("bad iteration count $iterations")
            val salt = ByteArray(SALT_LEN).also { hd.readFully(it) }
            val verifier = ByteArray(VERIFY_LEN).also { hd.readFully(it) }
            val (ikm, checkKey) = deriveKeys(password, salt, iterations)
            if (!MessageDigest.isEqual(verifierOf(checkKey, if (old) OLD_LABEL else LABEL), verifier)) throw BadPasswordException("wrong password")

            val dec = AesGcmHkdfStreaming(ikm, "HmacSha256", 32, SEGMENT, 0).newDecryptingStream(input, header)
            val data = DataInputStream(dec)
            val count = data.readInt()
            if (count !in 0..MAX_COUNT) throw CorruptBackupException("bad item count $count")
            repeat(count) {
                val name = data.readUTF()
                val mime = data.readUTF()
                val durationMs = data.readLong()
                val size = data.readLong()
                val thumbLen = data.readInt()
                if (size < 0 || thumbLen < -1 || thumbLen > MAX_THUMB) throw CorruptBackupException("bad metadata for '$name'")
                val thumb = if (thumbLen < 0) null else ByteArray(thumbLen).also { data.readFully(it) }
                val lim = Limited(data, size)
                onItem(Item(name, mime, durationMs, thumb, size), lim)
                lim.drain()
                if (lim.left != 0L) throw CorruptBackupException("'$name' is truncated")
            }
            // Reading past the last item forces the final segment to authenticate.
            if (dec.read() != -1) throw CorruptBackupException("trailing data after last item")
        } catch (e: CorruptBackupException) {
            throw e
        } catch (e: BadPasswordException) {
            throw e
        } catch (e: IOException) {
            throw corrupt("backup is corrupt or truncated: ${e.message}", e)
        } catch (e: GeneralSecurityException) {
            throw corrupt("backup is corrupt: ${e.message}", e)
        }
    }

    private fun deriveKeys(password: CharArray, salt: ByteArray, iterations: Int): Pair<ByteArray, ByteArray> {
        val spec = PBEKeySpec(password, salt, iterations, 512)
        try {
            val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return k.copyOfRange(0, 32) to k.copyOfRange(32, 64)
        } finally {
            spec.clearPassword()
        }
    }

    private fun corrupt(msg: String, cause: Throwable): CorruptBackupException =
        CorruptBackupException(msg).apply { initCause(cause) }

    private fun verifierOf(checkKey: ByteArray, label: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(checkKey, "HmacSHA256")); doFinal(label) }

    /** Tink closes what it wraps; the caller owns the backup stream, so swallow close and only flush. */
    private class NonClosing(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() = out.flush()
    }

    /** Exposes exactly [size] bytes of the decrypted stream; a plain InputStream so skip() cannot leak past the limit. */
    private class Limited(private val src: InputStream, var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else src.read().also { if (it >= 0) left-- }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        fun drain() {
            val buf = ByteArray(COPY)
            while (read(buf) >= 0) Unit
        }
    }
}
