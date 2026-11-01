package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.library.logic.GainMode
import dev.altayeb.shoebill.library.logic.ReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainLogicTest {
    @Test fun parsesCommonTagForms() {
        assertEquals(-6.2, ReplayGain.parseDb("-6.20 dB")!!, 1e-9)
        assertEquals(3.0, ReplayGain.parseDb("+3 dB")!!, 1e-9)
        assertEquals(3.5, ReplayGain.parseDb("3.5")!!, 1e-9)
        assertEquals(-0.5, ReplayGain.parseDb("  -0.50dB ")!!, 1e-9)
    }

    @Test fun parsesArabicAndPersianDigits() {
        assertEquals(-6.2, ReplayGain.parseDb("-٦٫٢٠ dB")!!, 1e-9)
        assertEquals(-6.2, ReplayGain.parseDb("−٦٫٢٠ dB")!!, 1e-9)
        assertEquals(3.0, ReplayGain.parseDb("۳ dB")!!, 1e-9)
    }

    @Test fun rejectsGarbage() {
        assertNull(ReplayGain.parseDb(null))
        assertNull(ReplayGain.parseDb(""))
        assertNull(ReplayGain.parseDb("garbage"))
        assertNull(ReplayGain.parseDb("dB"))
        assertNull(ReplayGain.parseDb("-6.20 dB extra"))
        assertNull(ReplayGain.parseDb("NaN"))
        assertNull(ReplayGain.parseDb("1e9"))
    }

    @Test fun rejectsHugeValues() {
        assertNull(ReplayGain.parseDb("999999 dB"))
        assertNull(ReplayGain.parseDb("61 dB"))
        assertNull(ReplayGain.parseDb("-60.5 dB"))
        assertEquals(-60.0, ReplayGain.parseDb("-60 dB")!!, 1e-9)
    }

    @Test fun readsTrackAndAlbumKeysWithoutCase() {
        val (track, album) = ReplayGain.fromTags(listOf(
            "replaygain_track_gain" to "-7.00 dB",
            "REPLAYGAIN_ALBUM_GAIN" to "-5.10 dB",
            "REPLAYGAIN_TRACK_PEAK" to "0.99",
        ))
        assertEquals(-7.0, track!!, 1e-9)
        assertEquals(-5.1, album!!, 1e-9)
    }

    @Test fun garbageTagIsIgnoredAndFirstUsableWins() {
        val (track, album) = ReplayGain.fromTags(listOf("REPLAYGAIN_TRACK_GAIN" to "junk", "REPLAYGAIN_TRACK_GAIN" to "-2 dB"))
        assertEquals(-2.0, track!!, 1e-9)
        assertNull(album)
    }

    @Test fun offOrNoTagLeavesVolumeAlone() {
        assertEquals(1f, ReplayGain.volume(GainMode.Off, -6.0, -6.0, 0), 0f)
        assertEquals(1f, ReplayGain.volume(GainMode.Track, null, -6.0, 0), 0f)
        assertEquals(1f, ReplayGain.volume(GainMode.Album, -6.0, null, 0), 0f)
    }

    @Test fun attenuatesByTheTag() {
        assertEquals(0.5012f, ReplayGain.volume(GainMode.Track, -6.0, null, 0), 1e-3f)
        assertEquals(0.3548f, ReplayGain.volume(GainMode.Track, -6.0, null, -3), 1e-3f)
    }

    @Test fun boostsNeverClip() {
        assertEquals(1f, ReplayGain.volume(GainMode.Track, 3.0, null, 0), 0f)
        assertEquals(1f, ReplayGain.volume(GainMode.Track, -2.0, null, 3), 0f)
        assertEquals(1f, ReplayGain.volume(GainMode.Track, 5.0, null, 50), 0f)
    }
}
