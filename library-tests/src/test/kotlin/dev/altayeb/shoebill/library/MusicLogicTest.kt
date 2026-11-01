package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.library.logic.MusicAccess
import dev.altayeb.shoebill.library.logic.QueueRestore
import dev.altayeb.shoebill.library.logic.artBucket
import dev.altayeb.shoebill.library.logic.skipAfterError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLogicTest {
    private val ids = listOf(10L, 20L, 30L, 40L)

    @Test fun restoreKeepsCurrentSongWhenEarlierOnesAreGone() =
        assertEquals(listOf(30L, 40L) to 0, QueueRestore.restore(ids, 30L, 2) { it >= 30 })
    @Test fun restoreFallsBackToSavedIndexWhenCurrentIsGone() =
        assertEquals(listOf(10L, 20L, 40L) to 2, QueueRestore.restore(ids, 30L, 2) { it != 30L })
    @Test fun restoreClampsIndex() = assertEquals(listOf(10L) to 0, QueueRestore.restore(ids, null, 3) { it == 10L })
    @Test fun restoreEmpty() {
        assertEquals(emptyList<Long>() to 0, QueueRestore.restore(ids, 10L, 0) { false })
        assertEquals(emptyList<Long>() to 0, QueueRestore.restore(emptyList(), null, 5) { true })
    }
    @Test fun restoreLegacyPrefsWithoutCurrentId() = assertEquals(ids to 1, QueueRestore.restore(ids, null, 1) { true })

    @Test fun accessOwnSystemAndAuto() {
        assertTrue(MusicAccess.allowed("dev.altayeb.shoebill", "dev.altayeb.shoebill", false))
        assertTrue(MusicAccess.allowed("com.evil.app", "dev.altayeb.shoebill", true))
        assertTrue(MusicAccess.allowed("com.google.android.projection.gearhead", "dev.altayeb.shoebill", false))
        assertTrue(MusicAccess.allowed("com.android.systemui", "dev.altayeb.shoebill", false))
    }
    @Test fun accessRejectsStrangers() = assertFalse(MusicAccess.allowed("com.evil.app", "dev.altayeb.shoebill", false))

    @Test fun skipAdvancesUntilWholeQueueFailed() {
        assertTrue(skipAfterError(1, 3, hasNext = true, repeatOne = false))
        assertTrue(skipAfterError(2, 3, hasNext = true, repeatOne = false))
        assertFalse(skipAfterError(3, 3, hasNext = true, repeatOne = false))
        assertTrue(skipAfterError(9, 20_000, hasNext = true, repeatOne = false))
        assertFalse(skipAfterError(10, 20_000, hasNext = true, repeatOne = false))
    }
    @Test fun skipStopsAtEndAndOnRepeatOne() {
        assertFalse(skipAfterError(1, 3, hasNext = false, repeatOne = false))
        assertFalse(skipAfterError(1, 3, hasNext = true, repeatOne = true))
    }

    @Test fun artBucketSharesSmallSizesAndFitsLarge() {
        assertEquals(256, artBucket(121))
        assertEquals(256, artBucket(143))
        assertEquals(256, artBucket(256))
        assertEquals(512, artBucket(440))
        assertEquals(768, artBucket(700))
        assertEquals(1024, artBucket(770))
        assertEquals(256, artBucket(0))
    }
}
