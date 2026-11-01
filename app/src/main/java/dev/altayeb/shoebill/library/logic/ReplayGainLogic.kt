package dev.altayeb.shoebill.library.logic

import kotlin.math.pow

enum class GainMode { Off, Track, Album }

/** ReplayGain tags ("REPLAYGAIN_TRACK_GAIN = -6.20 dB") and the player volume that applies them. */
object ReplayGain {
    private const val LIMIT_DB = 60.0
    private val NUMBER = Regex("""[+-]?\d{1,3}(?:\.\d{1,4})?\s*(?:dB)?""", RegexOption.IGNORE_CASE)

    /** "-6.20 dB", "+3 dB", "3.5", Arabic or Persian digits. Null for garbage, or for anything beyond ±60 dB. */
    fun parseDb(text: String?): Double? {
        val ascii = text?.trim()?.map(::toAscii)?.joinToString("") ?: return null
        if (!NUMBER.matches(ascii)) return null
        return ascii.takeWhile { it == '+' || it == '-' || it == '.' || it.isDigit() }.toDouble().takeIf { it in -LIMIT_DB..LIMIT_DB }
    }

    private fun toAscii(c: Char): Char = when (c) {
        in '٠'..'٩' -> '0' + (c - '٠')
        in '۰'..'۹' -> '0' + (c - '۰')
        '٫' -> '.'
        '−' -> '-'
        else -> c
    }

    /** Track and album gain from (key, value) pairs of any tag format. Keys match without case; the first usable value wins. */
    fun fromTags(tags: Iterable<Pair<String, String>>): Pair<Double?, Double?> {
        var track: Double? = null
        var album: Double? = null
        for ((key, value) in tags) when (key.trim().uppercase()) {
            "REPLAYGAIN_TRACK_GAIN" -> track = track ?: parseDb(value)
            "REPLAYGAIN_ALBUM_GAIN" -> album = album ?: parseDb(value)
        }
        return track to album
    }

    /**
     * Linear player volume (0..1) for [mode]. Player.volume cannot go above 1, so this only attenuates: any gain above
     * 0 dB after pre-amp becomes 1f (source level). Boosts are dropped, never clipped. No usable tag = 1f (unchanged).
     */
    fun volume(mode: GainMode, trackDb: Double?, albumDb: Double?, preampDb: Int): Float {
        val tag = when (mode) { GainMode.Off -> null; GainMode.Track -> trackDb; GainMode.Album -> albumDb } ?: return 1f
        val db = tag + preampDb.coerceIn(-12, 12)
        return if (db >= 0.0) 1f else 10.0.pow(db / 20.0).toFloat()
    }
}
