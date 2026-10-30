package app.anon.player.subs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class OsHashTest {
    // Reference computed independently with python3 (struct '<Q' sums, mod 2^64):
    // data = bytes((i*31+7)&0xFF for i in range(200000)) -> 5f9fe02060a3cd40
    private val expected = "5f9fe02060a3cd40"

    private fun pattern(n: Int) = ByteArray(n) { ((it * 31 + 7) and 0xFF).toByte() }

    @Test
    fun fileHashMatchesPythonReference() {
        val f = File.createTempFile("oshash", ".bin")
        try {
            f.writeBytes(pattern(200_000))
            RandomAccessFile(f, "r").use { assertEquals(expected, OsHash.compute(it)) }
        } finally {
            f.delete()
        }
    }

    @Test
    fun pureOverloadMatchesPythonReference() {
        val d = pattern(200_000)
        assertEquals(expected, OsHash.compute(200_000L, d.copyOfRange(0, 65536), d.copyOfRange(200_000 - 65536, 200_000)))
    }

    @Test
    fun wordsAreLittleEndian() {
        // size 65536 + first chunk word 0x0102030405060708 (LE bytes 08..01) + zero last chunk
        val first = ByteArray(65536)
        val word = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)
        word.copyInto(first)
        val h = OsHash.compute(65536L, first, ByteArray(65536))
        assertEquals(String.format("%016x", 65536L + 0x0102030405060708L), h)
    }
}
