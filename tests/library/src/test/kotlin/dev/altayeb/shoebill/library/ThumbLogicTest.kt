package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.library.logic.fallbackFrameMs
import dev.altayeb.shoebill.library.logic.isMostlyBlack
import dev.altayeb.shoebill.library.logic.isMostlyWhite
import dev.altayeb.shoebill.library.logic.pickFrameMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbLogicTest {
    private val min = 60_000L
    private val argb = { v: Int -> (0xFF shl 24) or (v shl 16) or (v shl 8) or v }

    @Test fun zeroDurationNoSave_isStart() = assertEquals(0L, pickFrameMs(0, -1))
    @Test fun zeroDurationWithSave_usesSave() = assertEquals(42_000L, pickFrameMs(0, 42_000))
    @Test fun thirtySeconds_tenPercent() = assertEquals(3_000L, pickFrameMs(30_000, -1))
    @Test fun twoHours_capsAtFiveMinutes() = assertEquals(5 * min, pickFrameMs(2 * 60 * min, -1))
    @Test fun savedInsideRange_wins() = assertEquals(60 * min, pickFrameMs(120 * min, 60 * min))
    @Test fun savedBeforeFiveSeconds_ignored() = assertEquals(5 * min, pickFrameMs(120 * min, 3_000))
    @Test fun savedInFinalThirtySeconds_ignored() = assertEquals(5 * min, pickFrameMs(120 * min, 120 * min - 10_000))
    @Test fun fallbackIsQuarter() = assertEquals(30 * min, fallbackFrameMs(120 * min))
    @Test fun fallbackZeroDuration() = assertEquals(0L, fallbackFrameMs(0))

    @Test fun allBlack_isBlack() = assertTrue(isMostlyBlack(IntArray(320 * 180) { argb(0) }))
    @Test fun midGrey_isNotBlack() = assertFalse(isMostlyBlack(IntArray(320 * 180) { argb(128) }))
    @Test fun blackWithFewBrightPixels_isBlack() {
        val px = IntArray(64) { argb(0) }
        px[3] = argb(255); px[40] = argb(255)
        assertTrue(isMostlyBlack(px))
    }
    @Test fun letterboxedBrightMiddle_isNotBlack() {
        // 320x180: top and bottom 45 rows black, middle 90 rows grey 200.
        val px = IntArray(320 * 180) { i ->
            val row = i / 320
            if (row < 45 || row >= 135) argb(0) else argb(200)
        }
        assertFalse(isMostlyBlack(px))
    }
    @Test fun emptyArray_isBlack() = assertTrue(isMostlyBlack(IntArray(0)))
    @Test fun allWhite_isWhite() = assertTrue(isMostlyWhite(IntArray(320 * 180) { argb(255) }))
    @Test fun brightScene_isNotWhite() = assertFalse(isMostlyWhite(IntArray(320 * 180) { argb(200) }))
}
