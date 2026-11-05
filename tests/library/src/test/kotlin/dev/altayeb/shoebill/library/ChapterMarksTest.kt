package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.player.logic.chapterFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterMarksTest {
    @Test fun zeroDurationDrawsNothing() = assertNull(chapterFraction(10.0, 0.0))
    @Test fun unknownDurationDrawsNothing() = assertNull(chapterFraction(10.0, -1.0))
    @Test fun chapterBeyondDurationDrawsNothing() = assertNull(chapterFraction(100.0, 90.0))
    @Test fun negativeChapterDrawsNothing() = assertNull(chapterFraction(-1.0, 90.0))
    @Test fun nanChapterDrawsNothing() = assertNull(chapterFraction(Double.NaN, 90.0))
    @Test fun startIsZero() = assertEquals(0f, chapterFraction(0.0, 90.0)!!, 0f)
    @Test fun endIsOne() = assertEquals(1f, chapterFraction(90.0, 90.0)!!, 0f)
    @Test fun middle() = assertEquals(0.25f, chapterFraction(30.0, 120.0)!!, 1e-6f)
}
