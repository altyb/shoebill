package dev.altayeb.shoebill.subs

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtParserTest {
    private val sample = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\nHello\r\nworld\r\n\r\n" +
        "2\r\n00:01:02,250 --> 00:01:04,000\r\nSecond cue\r\n"

    @Test
    fun parsesBomAndCrlf() {
        val cues = SrtParser.parse(sample)
        assertEquals(listOf(Cue(1000, 3500, "Hello\nworld"), Cue(62250, 64000, "Second cue")), cues)
    }

    @Test
    fun roundTrips() {
        val cues = SrtParser.parse(sample)
        assertEquals(cues, SrtParser.parse(SrtParser.write(cues)))
    }

    @Test
    fun shiftMovesAndDropsCuesBeforeZero() {
        val cues = listOf(Cue(1000, 2000, "a"), Cue(0, 300, "gone"), Cue(200, 900, "clamped"))
        assertEquals(
            listOf(Cue(500, 1500, "a"), Cue(0, 400, "clamped")),
            cues.shift(-500),
        )
    }

    @Test
    fun scaleFpsFilmToPal() {
        // 23.976 -> 25: times * 23.976/25
        val out = listOf(Cue(10_000, 20_000, "x")).scaleFps()
        assertEquals(listOf(Cue(9590, 19181, "x")), out)
    }
}
