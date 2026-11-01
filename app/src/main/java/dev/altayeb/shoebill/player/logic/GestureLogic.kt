package dev.altayeb.shoebill.player.logic

import kotlin.math.abs

enum class Axis { None, Vertical, Horizontal, Blocked, Subtitle }

/** Which gesture a drag of ([dx], [dy]) starts, once it passed the touch slop. [leftHalf]: the finger went down on the left half (brightness side). */
fun pickAxis(gesturesOn: Boolean, onSub: Boolean, dx: Float, dy: Float, leftHalf: Boolean, brightness: Boolean, volume: Boolean, seek: Boolean): Axis = when {
    !gesturesOn -> Axis.Blocked
    onSub && abs(dy) > abs(dx) -> Axis.Subtitle
    abs(dy) > abs(dx) -> if (if (leftHalf) brightness else volume) Axis.Vertical else Axis.Blocked
    seek -> Axis.Horizontal
    else -> Axis.Blocked
}

val ASPECTS = listOf("-1", "16:9", "4:3", "2.35:1")

/** The aspect override after [cur] in the cycle; anything unknown restarts at auto ("-1"). */
fun nextAspect(cur: String?): String {
    val idx = ASPECTS.indexOf(cur ?: "-1")
    return ASPECTS[(idx + 1) % ASPECTS.size]
}
