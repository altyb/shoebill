package app.anon.player.subs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object SubCharset {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        strictUtf8(bytes)?.let { return it }
        val cs = Charset.forName(if (looksArabic(bytes)) "windows-1256" else "windows-1252")
        return String(bytes, cs)
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    // ponytail: byte-frequency heuristic, not a language detector. Good enough for subtitle files.
    internal fun looksArabic(bytes: ByteArray): Boolean {
        var high = 0
        var arabic = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x80) continue
            high++
            if (v in 0xC1..0xDA || v in 0xE1..0xE3) arabic++
        }
        return high >= 8 && arabic * 10 >= high * 6
    }
}
