package dev.altayeb.shoebill.player.logic

/** Where a chapter starting at [sec] sits on the seek bar, 0..1. Null when no tick can be drawn: unknown duration or a chapter outside the file. */
fun chapterFraction(sec: Double, durationSec: Double): Float? {
    if (durationSec <= 0.0 || sec.isNaN() || sec < 0.0 || sec > durationSec) return null
    return (sec / durationSec).toFloat()
}
