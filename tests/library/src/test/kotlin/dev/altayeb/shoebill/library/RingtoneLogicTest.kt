package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.library.logic.RingtoneLogic
import org.junit.Assert.assertEquals
import org.junit.Test

class RingtoneLogicTest {
    @Test fun envelope() {
        assertEquals(0f, RingtoneLogic.envelope(0, 10_000, 2_000, 2_000, 1f), 1e-6f)
        assertEquals(0.5f, RingtoneLogic.envelope(1_000, 10_000, 2_000, 2_000, 1f), 1e-6f)
        assertEquals(1.5f, RingtoneLogic.envelope(5_000, 10_000, 2_000, 2_000, 1.5f), 1e-6f)
        assertEquals(0.25f, RingtoneLogic.envelope(9_500, 10_000, 2_000, 2_000, 1f), 1e-6f)
        assertEquals(1f, RingtoneLogic.envelope(0, 10_000, 0, 0, 1f), 1e-6f)
    }
    @Test fun clipping() {
        assertEquals(Short.MAX_VALUE, RingtoneLogic.apply(30_000, 2f))
        assertEquals(Short.MIN_VALUE, RingtoneLogic.apply(-30_000, 2f))
        assertEquals(1_500.toShort(), RingtoneLogic.apply(1_000, 1.5f))
    }
    @Test fun selection() {
        assertEquals(0L to 30_000L, RingtoneLogic.clamp(0, 30_000, 200_000))
        assertEquals(10_000L to 11_000L, RingtoneLogic.clamp(10_000, 9_000, 200_000)) // end before start: one second
        assertEquals(199_000L to 200_000L, RingtoneLogic.clamp(250_000, 260_000, 200_000)) // past the end
        assertEquals(0L to 300_000L, RingtoneLogic.clamp(0, 900_000, 1_000_000)) // 5 minute cap
        assertEquals(2_000L to 1_000L, RingtoneLogic.fades(5_000, 1_000, 4_000))
        assertEquals("a b.m4a", RingtoneLogic.fileName("a/b", "x"))
        assertEquals("x.m4a", RingtoneLogic.fileName("  ", "x"))
    }
}
