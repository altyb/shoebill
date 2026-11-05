package dev.altayeb.shoebill.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseNameTest {
    @Test
    fun movieWithYearAndTags() {
        val r = ReleaseName.parse("Interstellar.2014.1080p.BluRay.x265-RARBG.mkv")
        assertEquals(ReleaseInfo("Interstellar", 2014, null, null), r)
    }

    @Test
    fun episodeWithoutYear() {
        val r = ReleaseName.parse("The.Good.Place.S02E05.720p.mkv")
        assertEquals(ReleaseInfo("The Good Place", null, 2, 5), r)
    }

    @Test
    fun titleWithDashAndYearInParens() {
        val r = ReleaseName.parse("Demon Slayer - Infinity Castle (2025).mkv")
        assertEquals(ReleaseInfo("Demon Slayer - Infinity Castle", 2025, null, null), r)
    }

    @Test
    fun arabicTitleWithYear() {
        val r = ReleaseName.parse("فيلم العراب 1972 1080p BluRay.mkv")
        assertEquals(ReleaseInfo("فيلم العراب", 1972, null, null), r)
    }

    @Test
    fun noYearKeepsWholeTitle() {
        val r = ReleaseName.parse("Some.Movie.720p.WEB-DL.mkv")
        assertEquals(ReleaseInfo("Some Movie", null, null, null), r)
        assertNull(r.year)
    }

    @Test
    fun plainNameWithoutTags() {
        assertEquals(ReleaseInfo("Just A Title", null, null, null), ReleaseName.parse("Just A Title.mp4"))
    }

    @Test
    fun yearLikeTitleIsKept() {
        assertEquals(ReleaseInfo("1917", 2019, null, null), ReleaseName.parse("1917.2019.1080p.mkv"))
    }
}
