import app.anon.player.subs.AutoSync
import app.anon.player.subs.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class AutoSyncTest {
    private val r = Random(3)

    /** Random talking and silence, like a film. */
    private fun cues(): List<Cue> {
        val out = ArrayList<Cue>()
        var t = 2_000L
        while (t < 290_000) {
            val len = 800L + r.nextInt(3500)
            out.add(Cue(t, t + len, "x"))
            t += len + 500 + r.nextInt(6000)
        }
        return out
    }

    private fun voice(cs: List<Cue>, shiftMs: Long, noise: Double): BooleanArray {
        val v = BooleanArray(3000)
        for (c in cs) for (i in ((c.startMs + shiftMs) / 100).toInt().coerceAtLeast(0) until ((c.endMs + shiftMs) / 100).toInt().coerceAtMost(3000)) v[i] = true
        for (i in v.indices) if (r.nextDouble() < noise) v[i] = !v[i]
        return v
    }

    @Test fun findsLateAndEarlySubtitles() {
        val cs = cues()
        for (real in listOf(-12_300L, 0L, 800L, 25_000L)) {
            val got = AutoSync.bestOffsetMs(voice(cs, real, 0.15), 0, 100, cs)!!
            assertEquals(real.toDouble(), got.toDouble(), 200.0)
        }
    }

    @Test fun refusesToGuessWhenNothingMatches() {
        val flat = BooleanArray(3000)
        assertNull(AutoSync.bestOffsetMs(flat, 0, 100, cues()))
        assertNull(AutoSync.bestOffsetMs(BooleanArray(0), 0, 100, cues()))
        assertNull(AutoSync.bestOffsetMs(voice(cues(), 0, 0.0), 0, 100, emptyList()))
    }

    @Test fun louderStepsCountAsVoice() {
        val m = AutoSync.voiceMask(floatArrayOf(1f, 1f, 1f, 9f, 1f))
        assertEquals(listOf(false, false, false, true, false), m.toList())
    }
}
