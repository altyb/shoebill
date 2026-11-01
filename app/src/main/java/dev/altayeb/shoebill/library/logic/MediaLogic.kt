package dev.altayeb.shoebill.library.logic

data class LrcLine(val timeMs: Long, val text: String)

/** Synced lyrics files. One line may carry several time tags; [offset:ms] shifts them all; lines without a tag are dropped. */
object Lrc {
    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<LrcLine> {
        val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LrcLine>()
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val tags = TAG.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val body = raw.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val (min, sec, frac) = m.destructured
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.toLong() }
                out.add(LrcLine((min.toLong() * 60_000 + sec.toLong() * 1000 + ms + offset).coerceAtLeast(0), body))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** The line being sung at [posMs], or -1 before the first. */
    fun indexAt(lines: List<LrcLine>, posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }
}

/** Names that sort the way people count: "Track 2" before "Track 10". Case and accents do not matter much. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val si = i; while (i < a.length && a[i].isDigit()) i++
                val sj = j; while (j < b.length && b[j].isDigit()) j++
                val x = a.substring(si, i).trimStart('0')
                val y = b.substring(sj, j).trimStart('0')
                if (x.length != y.length) return x.length - y.length
                val c = x.compareTo(y)
                if (c != 0) return c
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/** Saved playlists as one line of text each; a name may hold any character. */
object PlaylistCodec {
    fun encode(ids: List<Long>) = ids.joinToString(",")
    fun decode(text: String?): List<Long> = text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
}
