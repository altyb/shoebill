package dev.altayeb.shoebill.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class SubCharsetTest {
    private val arabic = "مرحبا بالعالم، هذا اختبار لترجمة الفيديو"

    @Test
    fun decodesWindows1256Arabic() {
        val bytes = arabic.toByteArray(Charset.forName("windows-1256"))
        assertTrue("heuristic should flag Arabic", SubCharset.looksArabic(bytes))
        assertEquals(arabic, SubCharset.decode(bytes))
    }

    @Test
    fun decodesWindows1252Latin() {
        val text = "Café déjà vu, señor"
        val bytes = text.toByteArray(Charset.forName("windows-1252"))
        assertEquals(text, SubCharset.decode(bytes))
    }

    @Test
    fun decodesUtf8WithAndWithoutBom() {
        val bytes = arabic.toByteArray(Charsets.UTF_8)
        assertEquals(arabic, SubCharset.decode(bytes))
        assertEquals(arabic, SubCharset.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes))
    }

    @Test
    fun honoursUtf16Bom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)
        assertEquals("hi", SubCharset.decode(bytes))
    }
}
