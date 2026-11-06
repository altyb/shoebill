package dev.altayeb.shoebill.library.logic

/** The numbers behind the ringtone maker: volume curve, sample clamping and a safe selection. Pure, for tests. */
object RingtoneLogic {
    const val MIN_MS = 1_000L
    const val MAX_MS = 60_000L * 5 // phones accept longer, but a ringtone past 5 minutes is a mistake

    /** Volume at [t] ms into a clip of [len] ms: linear fade in from 0 and fade out to 0, times [gain]. */
    fun envelope(t: Long, len: Long, fadeIn: Long, fadeOut: Long, gain: Float): Float {
        var v = gain
        if (fadeIn > 0 && t < fadeIn) v *= t.toFloat() / fadeIn
        val left = len - t
        if (fadeOut > 0 && left < fadeOut) v *= (left.coerceAtLeast(0)).toFloat() / fadeOut
        return v
    }

    /** One 16-bit sample after the volume change, clipped instead of wrapping around (which would crackle). */
    fun apply(sample: Short, v: Float): Short = (sample * v).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    /** Start and end kept inside the song, in order, at least [MIN_MS] and at most [MAX_MS] apart; fades fit inside. */
    fun clamp(start: Long, end: Long, duration: Long): Pair<Long, Long> {
        val d = duration.coerceAtLeast(MIN_MS)
        val s = start.coerceIn(0, d - MIN_MS)
        val e = end.coerceIn(s + MIN_MS, minOf(d, s + MAX_MS))
        return s to e
    }
    fun fades(fadeIn: Long, fadeOut: Long, len: Long): Pair<Long, Long> {
        val i = fadeIn.coerceIn(0, len / 2); val o = fadeOut.coerceIn(0, len / 2)
        return i to o
    }

    /** A file name from what the user typed; never empty. */
    fun fileName(typed: String, fallback: String): String =
        typed.replace(Regex("""[\\/:*?"<>|\p{Cc}]"""), " ").trim().ifEmpty { fallback }.take(80) + ".m4a"
}
