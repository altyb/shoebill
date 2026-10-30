package app.anon.player.subs

import java.util.Locale
import kotlin.math.roundToLong

data class Cue(val startMs: Long, val endMs: Long, val text: String)

object SrtParser {
    private val TIME = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{3})""")

    fun parse(srt: String): List<Cue> {
        val lines = srt.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = mutableListOf<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = TIME.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val (h1, m1, s1, f1, h2, m2, s2, f2) = m.destructured
            i++
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(lines[i])
                i++
            }
            cues += Cue(ms(h1, m1, s1, f1), ms(h2, m2, s2, f2), text.toString())
        }
        return cues
    }

    fun write(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { idx, c ->
            append(idx + 1).append('\n')
            append(fmt(c.startMs)).append(" --> ").append(fmt(c.endMs)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    private fun ms(h: String, m: String, s: String, f: String): Long =
        ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f.toLong()

    private fun fmt(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val f = ms % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, f)
    }
}

/** Move every cue by [ms]. Cues that end before 0 are dropped; starts are clamped to 0. */
fun List<Cue>.shift(ms: Long): List<Cue> = mapNotNull { c ->
    val end = c.endMs + ms
    if (end <= 0) null else c.copy(startMs = maxOf(0L, c.startMs + ms), endMs = end)
}

/**
 * Subtitles timed for [from] fps but played on a [to] fps video: timestamps scale by from/to.
 * Defaults fit 23.976 film transferred to 25 fps PAL (video runs ~4% fast, so times shrink).
 */
fun List<Cue>.scaleFps(from: Double = 23.976, to: Double = 25.0): List<Cue> {
    val k = from / to
    return map { c -> c.copy(startMs = (c.startMs * k).roundToLong(), endMs = (c.endMs * k).roundToLong()) }
}
