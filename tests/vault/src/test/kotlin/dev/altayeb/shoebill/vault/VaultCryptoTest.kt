package dev.altayeb.shoebill.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.security.GeneralSecurityException
import kotlin.random.Random

private const val MIB = 1024 * 1024
private const val FILE_ID = "file-0001"

/**
 * Contract for VaultCrypto. Written before the implementation; the implementer must not edit this file.
 * Against the stub every test must fail.
 */
class VaultCryptoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val masterKey = TestMasterKey()
    private val crypto = VaultCrypto(masterKey)

    private class Encrypted(val ciphertext: ByteArray, val wrappedKey: ByteArray)

    private fun encryptBytes(
        plain: ByteArray,
        c: VaultCrypto = crypto,
        fileId: String = FILE_ID,
    ): Encrypted {
        val out = ByteArrayOutputStream()
        val wrapped = c.encrypt(ByteArrayInputStream(plain), out, fileId)
        return Encrypted(out.toByteArray(), wrapped)
    }

    /** Writes ciphertext to a real file and opens it as a SeekableByteChannel. */
    private fun openFile(
        ciphertext: ByteArray,
        wrappedKey: ByteArray,
        fileId: String = FILE_ID,
        c: VaultCrypto = crypto,
    ): SeekableByteChannel {
        val path = tmp.newFile().toPath()
        Files.write(path, ciphertext)
        return c.openSeekable(Files.newByteChannel(path), wrappedKey, fileId)
    }

    private fun readAll(ch: SeekableByteChannel): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(64 * 1024)
        while (true) {
            buf.clear()
            val n = ch.read(buf)
            if (n < 0) break
            out.write(buf.array(), 0, n)
        }
        return out.toByteArray()
    }

    /** Positions at offset and reads up to len bytes, looping until full or EOF. */
    private fun readAt(ch: SeekableByteChannel, offset: Long, len: Int): ByteArray {
        ch.position(offset)
        val buf = ByteBuffer.allocate(len)
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) break
        }
        return buf.array().copyOf(buf.position())
    }

    /** Any decrypt/open/unwrap failure: must throw, never return bytes. */
    private fun assertFails(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            return
        }
        fail("$what: expected a failure, got data back")
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            if (hay[i] != needle[0]) continue
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // 1. Round trip
    @Test
    fun roundTripFiveMegabytes() {
        val plain = Random(1).nextBytes(5 * MIB)
        val enc = encryptBytes(plain)
        val channel = openFile(enc.ciphertext, enc.wrappedKey)
        assertEquals(plain.size.toLong(), channel.size())
        assertArrayEquals(plain, readAll(channel))
    }

    // 2. Random access across segment boundaries
    @Test
    fun randomAccessMatchesOriginalSlices() {
        val size = 3 * MIB + 12345
        val plain = Random(2).nextBytes(size)
        val enc = encryptBytes(plain)
        val ch = openFile(enc.ciphertext, enc.wrappedKey)
        val rnd = Random(3)

        repeat(200) {
            val offset = if (rnd.nextBoolean()) {
                // near a 1 MiB segment boundary
                (rnd.nextInt(1, 4) * MIB + rnd.nextInt(-100, 100)).coerceIn(0, size - 1)
            } else {
                rnd.nextInt(size)
            }
            val len = rnd.nextInt(1, 300_000).coerceAtMost(size - offset)
            val got = readAt(ch, offset.toLong(), len)
            assertArrayEquals(
                "offset=$offset len=$len",
                plain.copyOfRange(offset, offset + len),
                got,
            )
        }

        // Read straddling EOF returns the tail, then -1.
        assertArrayEquals(plain.copyOfRange(size - 10, size), readAt(ch, (size - 10).toLong(), 100))
        ch.position(size.toLong())
        assertEquals(-1, ch.read(ByteBuffer.allocate(16)))
        ch.position(size.toLong() + 1000)
        assertEquals(-1, ch.read(ByteBuffer.allocate(16)))
    }

    // 3. Seek does not read the whole ciphertext
    @Test
    fun seekReadsOnlyNeededCiphertext() {
        val plain = Random(4).nextBytes(6 * MIB)
        val enc = encryptBytes(plain)
        val path = tmp.newFile().toPath()
        Files.write(path, enc.ciphertext)
        val counting = CountingChannel(Files.newByteChannel(path))

        val ch = crypto.openSeekable(counting, enc.wrappedKey, FILE_ID)
        val got = readAt(ch, 4L * MIB, MIB)

        assertArrayEquals(plain.copyOfRange(4 * MIB, 5 * MIB), got)
        assertTrue(
            "read ${counting.bytesRead} ciphertext bytes for a 1 MiB read at 4 MiB",
            counting.bytesRead < 3L * MIB,
        )
    }

    // 4. Tamper a byte mid-file: must throw, never return wrong bytes
    @Test
    fun tamperedCiphertextThrows() {
        val plain = Random(5).nextBytes(3 * MIB)
        val enc = encryptBytes(plain)
        val bad = enc.ciphertext.copyOf()
        val pos = bad.size / 2
        bad[pos] = (bad[pos].toInt() xor 0x01).toByte()

        try {
            readAll(openFile(bad, enc.wrappedKey))
        } catch (e: Exception) {
            assertTrue(
                "unexpected exception type ${e::class.qualifiedName}",
                e is GeneralSecurityException || e is IOException,
            )
            return
        }
        fail("tampered ciphertext was read back without error")
    }

    // 5. Wrong fileId (associated data)
    @Test
    fun wrongFileIdFails() {
        val plain = Random(6).nextBytes(64 * 1024)
        val enc = encryptBytes(plain, fileId = "file-A")
        assertFails("wrong fileId") {
            readAll(openFile(enc.ciphertext, enc.wrappedKey, fileId = "file-B"))
        }
    }

    // 6. Wrong master key
    @Test
    fun wrongMasterKeyFailsUnwrap() {
        val plain = Random(7).nextBytes(64 * 1024)
        val enc = encryptBytes(plain)
        val other = TestMasterKey()
        assertFails("unwrap with other master key") { other.unwrap(enc.wrappedKey) }
        assertFails("open with other master key") {
            readAll(openFile(enc.ciphertext, enc.wrappedKey, c = VaultCrypto(other)))
        }
    }

    // 7. Per-file keys differ
    @Test
    fun samePlaintextGetsFreshKeyAndCiphertext() {
        val plain = Random(8).nextBytes(100 * 1024)
        val a = encryptBytes(plain)
        val b = encryptBytes(plain)
        assertFalse("wrapped keys must differ", a.wrappedKey.contentEquals(b.wrappedKey))
        assertFalse("ciphertexts must differ", a.ciphertext.contentEquals(b.ciphertext))
    }

    // 8. Names
    @Test
    fun encryptNameIsNotDeterministic() {
        val a = crypto.encryptName("movie.mkv")
        val b = crypto.encryptName("movie.mkv")
        assertNotEquals(a, b)
        assertTrue("not base64url: $a", a.matches(Regex("[A-Za-z0-9_-]+=*")))
    }

    @Test
    fun decryptNameRoundTripsArabicAndEmoji() {
        val names = listOf(
            "فيلم الأكشن.mp4",
            "مشهد 🎬 نهائي.mkv",
            "🎥🍿.webm",
            "plain.mp4",
        )
        for (name in names) {
            assertEquals(name, crypto.decryptName(crypto.encryptName(name)))
        }
    }

    @Test
    fun tamperedNameFails() {
        val enc = crypto.encryptName("فيلم الأكشن.mp4")
        val i = enc.length / 2
        val flipped = enc.substring(0, i) + (if (enc[i] == 'A') 'B' else 'A') + enc.substring(i + 1)
        assertFails("tampered name") { crypto.decryptName(flipped) }
    }

    // 9. No 32-byte plaintext window appears in ciphertext
    @Test
    fun ciphertextContainsNoPlaintextWindow() {
        val period = Random(9).nextBytes(1000)
        val plain = ByteArray(256 * 1024) { period[it % period.size] }
        val enc = encryptBytes(plain)
        // Windows starting at 0..999 cover every distinct 32-byte window of this periodic plaintext.
        for (s in period.indices) {
            val window = plain.copyOfRange(s, s + 32)
            assertEquals(
                "plaintext window at $s found in ciphertext",
                -1,
                indexOf(enc.ciphertext, window),
            )
        }
    }

    // 10. Empty and 1-byte files
    @Test
    fun emptyFileRoundTrips() {
        val enc = encryptBytes(ByteArray(0))
        val ch = openFile(enc.ciphertext, enc.wrappedKey)
        assertEquals(0L, ch.size())
        assertArrayEquals(ByteArray(0), readAll(ch))
        assertEquals(-1, ch.read(ByteBuffer.allocate(8)))
    }

    @Test
    fun oneByteFileRoundTrips() {
        val plain = byteArrayOf(0x7F)
        val enc = encryptBytes(plain)
        val ch = openFile(enc.ciphertext, enc.wrappedKey)
        assertEquals(1L, ch.size())
        assertArrayEquals(plain, readAll(ch))
    }
}
