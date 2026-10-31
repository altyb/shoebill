package dev.altayeb.shoebill.library.logic

/** Frame time for a thumbnail. Saved position wins when it is mid-file (5 s .. end-30 s); else 10% capped at 5 min. */
fun pickFrameMs(durationMs: Long, savedPosMs: Long): Long {
    if (durationMs <= 0) return savedPosMs.coerceAtLeast(0)
    if (savedPosMs >= 5_000 && savedPosMs < durationMs - 30_000) return savedPosMs
    return minOf(durationMs / 10, 5 * 60_000L)
}

/** Second try when the first frame is black: 25% of the file. */
fun fallbackFrameMs(durationMs: Long): Long = if (durationMs <= 0) 0 else durationMs / 4

/** Samples up to 64 evenly spaced pixels (ARGB) and checks mean luma < 12. Letterboxing still reads as bright. */
fun isMostlyBlack(pixels: IntArray): Boolean = pixels.isEmpty() || meanLuma(pixels) < 12

/** A white title card or fade: as useless for a thumbnail as a black one. */
fun isMostlyWhite(pixels: IntArray): Boolean = pixels.isNotEmpty() && meanLuma(pixels) > 240

private fun meanLuma(pixels: IntArray): Long {
    val n = minOf(64, pixels.size)
    var sum = 0L
    for (i in 0 until n) {
        val p = pixels[(i.toLong() * pixels.size / n).toInt()]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        sum += (r * 299 + g * 587 + b * 114) / 1000
    }
    return sum / n
}
