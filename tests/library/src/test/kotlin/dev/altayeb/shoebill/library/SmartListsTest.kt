package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.library.logic.PlayPeriod
import dev.altayeb.shoebill.library.logic.PlayStat
import dev.altayeb.shoebill.library.logic.PlayTracker
import dev.altayeb.shoebill.library.logic.SmartLists
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class SmartListsTest {
    data class S(val id: Long, val added: Long)
    private val all = listOf(S(1, 30), S(2, 20), S(3, 10), S(4, 40))
    private val stats = mapOf(1L to PlayStat(5, 100), 2L to PlayStat(5, 300), 3L to PlayStat(1, 200))

    @Test fun playRule() {
        assertTrue(SmartLists.counts(30_000, 240_000))
        assertFalse(SmartLists.counts(29_999, 240_000))
        assertTrue(SmartLists.counts(10_000, 20_000)) // half of a short song
        assertFalse(SmartLists.counts(9_000, 20_000))
        assertFalse(SmartLists.counts(10_000, 0)) // unknown length: only the 30 s rule
    }

    @Test fun lists() {
        assertEquals(listOf(4L, 1, 2, 3), SmartLists.recentlyAdded(all, { it.added }).map { it.id })
        assertEquals(listOf(2L, 3, 1), SmartLists.recentlyPlayed(all, { it.id }, stats).map { it.id })
        assertEquals(listOf(2L, 1, 3), SmartLists.mostPlayed(all, { it.id }, stats).map { it.id }) // tie on count: last played first
        assertEquals(listOf(4L), SmartLists.notPlayed(all, { it.id }, stats).map { it.id })
        assertEquals(listOf(3L, 1), SmartLists.favorites(all, { it.id }, listOf(1, 99, 3)).map { it.id }) // 99 is gone from the phone
    }

    @Test fun codecSkipsJunk() {
        assertEquals(stats, SmartLists.decode(SmartLists.encode(stats)))
        assertEquals(mapOf(7L to PlayStat(2, 0)), SmartLists.decode("x:1:2;7:2;8:-1:5;;9"))
        assertEquals(emptyMap<Long, PlayStat>(), SmartLists.decode(null))
    }

    @Test fun periods() {
        var st: PlayStat? = null
        st = SmartLists.bump(st, 10, weekKey = 1, monthKey = 1)
        st = SmartLists.bump(st, 20, weekKey = 1, monthKey = 1)
        st = SmartLists.bump(st, 30, weekKey = 2, monthKey = 1) // new week, same month
        assertEquals(PlayStat(3, 30, 2, 1, 1, 3), st)
        assertEquals(1, SmartLists.plays(st, PlayPeriod.Week, 2, 1))
        assertEquals(0, SmartLists.plays(st, PlayPeriod.Week, 3, 1)) // a week with no play yet
        assertEquals(3, SmartLists.plays(st, PlayPeriod.Month, 2, 1))
        assertEquals(0, SmartLists.plays(st, PlayPeriod.Month, 3, 2))
        assertEquals(3, SmartLists.plays(st, PlayPeriod.All, 9, 9))
        st = SmartLists.bump(st, 40, weekKey = 3, monthKey = 2)
        assertEquals(PlayStat(4, 40, 3, 1, 2, 1), st)

        // This week: 3 has 2 plays, 1 has 1 from an older week; all time 1 still leads.
        val m = mapOf(1L to PlayStat(9, 100, 1, 9, 1, 9), 3L to PlayStat(2, 50, 5, 2, 1, 2))
        assertEquals(listOf(3L), SmartLists.mostPlayed(all, { it.id }, m, PlayPeriod.Week, 5, 1).map { it.id })
        assertEquals(listOf(1L, 3), SmartLists.mostPlayed(all, { it.id }, m, PlayPeriod.Month, 5, 1).map { it.id })
        assertEquals(listOf(1L, 3), SmartLists.mostPlayed(all, { it.id }, m, PlayPeriod.All, 5, 1).map { it.id })
        // Listening time: plays × length (length here = id × 10).
        assertEquals(2 * 30L, SmartLists.listened(all, { it.id }, { it.id * 10 }, m, PlayPeriod.Week, 5, 1))
        assertEquals(9 * 10L + 2 * 30, SmartLists.listened(all, { it.id }, { it.id * 10 }, m, PlayPeriod.All, 5, 1))
        // Recently added since 25: only 4 (40) and 1 (30).
        assertEquals(listOf(4L, 1), SmartLists.recentlyAdded(all, { it.added }, since = 25).map { it.id })
    }

    @Test fun codecKeepsPeriodsAndReadsOldSaves() {
        val m = mapOf(5L to PlayStat(4, 99, 12, 2, 7, 3))
        assertEquals(m, SmartLists.decode(SmartLists.encode(m)))
        assertEquals(mapOf(5L to PlayStat(4, 99)), SmartLists.decode("5:4:99")) // saved before periods existed
        assertEquals(mapOf(5L to PlayStat(4, 99, 12, 4, 7, 0)), SmartLists.decode("5:4:99:12:50:7:x")) // week can't exceed all time
    }

    @Test fun trackerCountsOncePerPlay() {
        val t = PlayTracker()
        assertNull(t.start(1, 200_000, playing = true, now = 0))
        assertNull(t.playing(false, 20_000)) // 20 s, paused
        assertNull(t.playing(true, 50_000)) // paused time is not listening
        assertEquals(1L, t.check(60_000)) // 30 s reached
        assertNull(t.check(90_000)) // not twice
        assertNull(t.start(2, 200_000, playing = true, now = 100_000)) // song 1 already counted
        assertNull(t.start(3, 200_000, playing = true, now = 105_000)) // 5 s of song 2: skipped, no play
        assertEquals(3L, t.start(3, 200_000, playing = true, now = 140_000)) // repeat-one: the first round counts
    }
}
