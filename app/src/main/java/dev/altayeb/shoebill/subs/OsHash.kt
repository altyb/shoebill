package dev.altayeb.shoebill.subs

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** OpenSubtitles moviehash: file size + sum of LE 64-bit words of first and last 64 KiB, as 16 hex chars. */
object OsHash {
    const val CHUNK = 64 * 1024

    fun compute(file: RandomAccessFile): String =
        compute(file.length()) { off, n -> ByteArray(n).also { file.seek(off); file.readFully(it) } }

    /** [read] returns exactly [n] bytes at [offset]; only the two chunks are requested. */
    fun compute(size: Long, read: (offset: Long, n: Int) -> ByteArray): String {
        require(size >= CHUNK) { "file smaller than 64 KiB" }
        return compute(size, read(0, CHUNK), read(size - CHUNK, CHUNK))
    }

    fun compute(size: Long, first: ByteArray, last: ByteArray): String {
        val h = size + sumWords(first) + sumWords(last)
        return String.format("%016x", h)
    }

    private fun sumWords(chunk: ByteArray): Long {
        val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        var s = 0L
        while (bb.remaining() >= 8) s += bb.getLong()
        return s
    }
}
