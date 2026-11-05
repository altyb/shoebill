package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.player.logic.Axis
import dev.altayeb.shoebill.player.logic.nextAspect
import dev.altayeb.shoebill.player.logic.pickAxis
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureLogicTest {
    private fun axis(dx: Float, dy: Float, left: Boolean = true, on: Boolean = true, sub: Boolean = false, bright: Boolean = true, vol: Boolean = true, seek: Boolean = true) =
        pickAxis(gesturesOn = on, onSub = sub, dx = dx, dy = dy, leftHalf = left, brightness = bright, volume = vol, seek = seek)

    @Test fun gesturesOffBlocksEverything() = assertEquals(Axis.Blocked, axis(50f, 0f, on = false))
    @Test fun verticalOnSubtitleMovesSubtitle() = assertEquals(Axis.Subtitle, axis(1f, 30f, sub = true))
    @Test fun horizontalOnSubtitleStillSeeks() = assertEquals(Axis.Horizontal, axis(30f, 1f, sub = true))
    @Test fun leftVerticalIsBrightness() = assertEquals(Axis.Vertical, axis(0f, 30f, left = true))
    @Test fun leftVerticalBlockedWhenBrightnessOff() = assertEquals(Axis.Blocked, axis(0f, 30f, left = true, bright = false))
    @Test fun rightVerticalIsVolume() = assertEquals(Axis.Vertical, axis(0f, 30f, left = false))
    @Test fun rightVerticalBlockedWhenVolumeOff() = assertEquals(Axis.Blocked, axis(0f, 30f, left = false, vol = false, bright = true))
    @Test fun horizontalSeeks() = assertEquals(Axis.Horizontal, axis(30f, -2f))
    @Test fun horizontalBlockedWhenSeekOff() = assertEquals(Axis.Blocked, axis(30f, -2f, seek = false))
    @Test fun equalDeltaIsHorizontal() = assertEquals(Axis.Horizontal, axis(10f, 10f))

    @Test fun aspectCycles() {
        assertEquals("16:9", nextAspect("-1"))
        assertEquals("4:3", nextAspect("16:9"))
        assertEquals("2.35:1", nextAspect("4:3"))
        assertEquals("-1", nextAspect("2.35:1"))
    }
    @Test fun unknownAspectRestartsAtAuto() {
        assertEquals("-1", nextAspect("3:2"))
        assertEquals("16:9", nextAspect(null))
    }
}
