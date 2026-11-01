package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.download.logic.DownloadLogic
import dev.altayeb.shoebill.download.logic.DownloadOptions
import dev.altayeb.shoebill.download.logic.Quality
import dev.altayeb.shoebill.library.logic.Lrc
import dev.altayeb.shoebill.library.logic.NaturalOrder
import dev.altayeb.shoebill.library.logic.PlaylistCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLogicTest {
    @Test fun lrcTimesAndFractions() {
        val l = Lrc.parse("[00:01.5]a\n[00:02.50]b\n[00:03.123]c\n[01:00]d")
        assertEquals(listOf(1500L, 2500L, 3123L, 60000L), l.map { it.timeMs })
    }
    @Test fun lrcSeveralTagsOneLineAreSorted() {
        val l = Lrc.parse("[00:10.00][00:05.00]يا ليل\n[00:07.00]b")
        assertEquals(listOf("يا ليل", "b", "يا ليل"), l.map { it.text })
    }
    @Test fun lrcOffsetAndBomAndNoise() {
        val l = Lrc.parse("﻿[ar:Someone]\n[offset:-500]\nplain text\n[00:01.00]x")
        assertEquals(listOf(500L), l.map { it.timeMs })
    }
    @Test fun lrcOffsetNeverGoesNegative() = assertEquals(0L, Lrc.parse("[offset:-5000]\n[00:01.00]x")[0].timeMs)
    @Test fun lrcIndexAt() {
        val l = Lrc.parse("[00:01.00]a\n[00:02.00]b\n[00:03.00]c")
        assertEquals(-1, Lrc.indexAt(l, 999))
        assertEquals(0, Lrc.indexAt(l, 1000))
        assertEquals(1, Lrc.indexAt(l, 2999))
        assertEquals(2, Lrc.indexAt(l, 99999))
        assertEquals(-1, Lrc.indexAt(emptyList(), 5))
    }
    @Test fun naturalOrder() {
        assertEquals(listOf("a2", "A10", "b1", "track 02", "track 3"), listOf("track 3", "A10", "b1", "a2", "track 02").sortedWith(NaturalOrder))
        assertEquals(0, NaturalOrder.compare("x007", "x7"))
    }
    @Test fun naturalOrderHugeNumbersDoNotOverflow() = assertTrue(NaturalOrder.compare("99999999999999999999999", "100000000000000000000000") < 0)
    @Test fun playlistCodecSkipsDamage() {
        assertEquals(listOf(1L, 22L), PlaylistCodec.decode("1, x,22,"))
        assertEquals(emptyList<Long>(), PlaylistCodec.decode(null))
        assertEquals("1,2", PlaylistCodec.encode(listOf(1, 2)))
    }
    @Test fun firstUrlFromSharedText() {
        assertEquals("https://youtu.be/abc", DownloadLogic.firstUrl("watch this https://youtu.be/abc."))
        assertEquals("http://x.y/a?b=1&c=2", DownloadLogic.firstUrl("(http://x.y/a?b=1&c=2)"))
        assertNull(DownloadLogic.firstUrl("no link, ftp://x.y"))
        assertNull(DownloadLogic.firstUrl(null))
    }
    @Test fun argsForVideoLimitHeightAndNoPlaylistByDefault() {
        val a = DownloadLogic.args(DownloadOptions(Quality.P720), "/o")
        assertTrue("bv*[height<=720]+ba/b[height<=720]/b" in a)
        assertTrue("--no-playlist" in a)
        assertFalse("-x" in a)
        assertEquals("/o/%(title).150B [%(id)s].%(ext)s", a[a.indexOf("-o") + 1])
    }
    @Test fun argsForAudioExtract() {
        val a = DownloadLogic.args(DownloadOptions(Quality.MP3, playlist = true), "/o")
        assertEquals("mp3", a[a.indexOf("--audio-format") + 1])
        assertTrue("-x" in a && "--yes-playlist" in a)
        assertFalse("--merge-output-format" in a)
    }
    @Test fun subtitlesOnlyWhenAskedAndOnlyVideo() {
        assertFalse("--embed-subs" in DownloadLogic.args(DownloadOptions(), "/o"))
        assertTrue("--embed-subs" in DownloadLogic.args(DownloadOptions(subtitles = true), "/o"))
        assertFalse("--embed-subs" in DownloadLogic.args(DownloadOptions(Quality.M4A, subtitles = true), "/o"))
    }
    @Test fun mediaTypes() {
        assertTrue(DownloadLogic.isAudioFile("a.MP3"))
        assertTrue(DownloadLogic.isMediaFile("a.mkv"))
        assertFalse(DownloadLogic.isMediaFile("a.vtt"))
        assertFalse(DownloadLogic.isMediaFile("noext"))
        assertEquals("video/x-matroska", DownloadLogic.mime("a.MKV"))
    }

    @Test fun proxyAddresses() {
        for (ok in listOf("", "  ", "socks5://127.0.0.1:1080", "http://proxy.example:8080", "https://a.b:443/", "socks5h://u:p@host:1", "SOCKS4://h:65535")) assertTrue(ok, DownloadLogic.validProxy(ok))
        for (bad in listOf("127.0.0.1:1080", "ftp://h:21", "http://h", "http://h:0", "http://h:70000", "http://h:٨٠٨٠", "socks5://h h:80", "http://h:80/path", "--exec=x")) assertFalse(bad, DownloadLogic.validProxy(bad))
    }

    @Test fun limitAndProxyReachYtDlp() {
        val a = DownloadLogic.args(DownloadOptions(limit = "1M", proxy = "socks5://h:1"), "/o")
        assertEquals("1M", a[a.indexOf("--limit-rate") + 1])
        assertEquals("socks5://h:1", a[a.indexOf("--proxy") + 1])
        val none = DownloadLogic.args(DownloadOptions(), "/o")
        assertFalse("--limit-rate" in none); assertFalse("--proxy" in none)
    }

    @Test fun speedLine() {
        assertEquals("1.20MiB/s", DownloadLogic.speed("[download]  42.0% of 10.00MiB at 1.20MiB/s ETA 00:05"))
        assertEquals("900.5KiB/s", DownloadLogic.speed("[download] 1% of ~ 3MiB at  900.5KiB/s ETA 01:00"))
        assertNull(DownloadLogic.speed("[download] Destination: at the zoo.mp4"))
        assertNull(DownloadLogic.speed(null))
    }

    @Test fun errorsHideTheProxyPassword() {
        assertEquals("ERROR: (proxy) refused", DownloadLogic.scrub("ERROR: http://u:secret@h:1 refused", "http://u:secret@h:1"))
        assertEquals("ERROR: x", DownloadLogic.scrub("ERROR: x", null))
    }

    @Test fun times() {
        assertEquals("0:05", DownloadLogic.time(5)); assertEquals("1:01:01", DownloadLogic.time(3661)); assertEquals("0:00", DownloadLogic.time(-3))
    }
}
