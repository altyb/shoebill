package app.anon.player.subs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

/** Old Arabic .srt files are windows-1256. The player decodes them with SubCharset.decode, then parses. */
class SubtitleFileTest {
    @Test
    fun windows1256SrtDecodesToUtf8Cues() {
        val srt = "1\n00:00:01,000 --> 00:00:03,500\nمرحبا بالعالم\n\n2\n00:00:04,000 --> 00:00:06,000\nهذا اختبار للترجمة\n\n"
        val bytes = srt.toByteArray(Charset.forName("windows-1256"))
        val cues = SrtParser.parse(SubCharset.decode(bytes))
        assertEquals(listOf("مرحبا بالعالم", "هذا اختبار للترجمة"), cues.map { it.text })
        assertEquals(1000L, cues[0].startMs)
        assertEquals(6000L, cues[1].endMs)
    }
}
