package dev.altayeb.shoebill.player

import android.content.Context
import android.view.Window
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.library.formatTime
import dev.altayeb.shoebill.player.logic.Axis
import dev.altayeb.shoebill.player.logic.pickAxis
import dev.altayeb.shoebill.settings.AppSettings
import dev.altayeb.shoebill.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** What the touch handler reads and writes on the player screen; the states are shared with the composable's own `by` vars. */
internal class GestureHost(
    val player: MpvPlayer,
    val scope: CoroutineScope,
    val context: Context,
    val window: Window,
    val haptics: HapticFeedback,
    val settingsState: State<Settings>,
    val overlayVisible: MutableState<Boolean>,
    val panel: MutableState<Panel?>,
    val searchOpen: MutableState<Boolean>,
    val locked: MutableState<Boolean>,
    val hud: MutableState<Hud?>,
    val seekPreview: MutableState<Double?>,
    val setBrightness: (Float) -> Unit,
)

/** Tap, double tap, hold-to-speed, drag (seek / brightness / volume / subtitle position) and pinch (zoom / subtitle size). */
internal fun Modifier.playerGestures(host: GestureHost): Modifier = pointerInput(Unit) {
    val player = host.player
    val scope = host.scope
    val context = host.context
    val window = host.window
    val haptics = host.haptics
    val settingsState = host.settingsState
    val setBrightness = host.setBrightness
    var overlayVisible by host.overlayVisible
    val panel by host.panel
    val searchOpen by host.searchOpen
    val locked by host.locked
    var hud by host.hud
    var seekPreview by host.seekPreview
    var lastTapAt = 0L
    var lastTapPos = Offset.Zero
    var tapToken = 0
    var hudToken = 0
    var seekAcc = 0
    var seekSign = 0
    var seekAt = 0L
    val slop = 12.dp.toPx()

    fun clearHudLater() {
        val token = ++hudToken
        scope.launch { delay(700); if (hudToken == token) hud = null }
    }

    fun showHud(h: Hud) {
        hud = h
        clearHudLater()
    }

    fun tapSeek(sign: Int) {
        val now = System.currentTimeMillis()
        val step = settingsState.value.doubleTapSec
        seekAcc = if (now - seekAt < 600 && sign == seekSign) seekAcc + step else step
        seekSign = sign
        seekAt = now
        player.seekBy(sign * step.toDouble())
        showHud(Hud(HudKind.Tap, label = (if (sign > 0) "+" else "−") + context.getString(R.string.player_seconds, seekAcc.toString())))
    }

    awaitEachGesture {
        val w = this@pointerInput.size.width.toFloat()
        val h = this@pointerInput.size.height.toFloat()
        val down = awaitFirstDown(requireUnconsumed = false)
        val y0 = down.position.y
        val onChrome = overlayVisible && (y0 < 96.dp.toPx() || y0 > h - 170.dp.toPx())
        if (down.isConsumed || onChrome || panel != null || searchOpen) return@awaitEachGesture

        val gesturesOn = settingsState.value.gestures && !locked
        // ponytail: subtitle band estimated from sub-pos as a share of the view height; exact only when the
        // video fills the height (landscape). Use mpv's sub bounding box if portrait drags feel off.
        val subPos0 = player.getString("sub-pos")?.toDoubleOrNull() ?: 100.0
        val subBottom = h * (subPos0 / 100.0).toFloat()
        val onSub = gesturesOn && !player.getString("sub-text").isNullOrBlank() &&
            down.position.x in w * 0.15f..w * 0.85f && y0 in subBottom - h * 0.22f..subBottom + h * 0.05f
        var axis = Axis.None
        var multi = false
        var pinch0 = 0f
        var zoom0 = 0.0
        var startSec = 0.0
        var startBright = 0f
        var startVol = 0.0
        var lastVol = 0.0
        var totalX = 0f
        var totalY = 0f
        // Hold still to play fast until the finger lifts.
        var holding = false
        var speedBefore = 1.0
        val holdRate = settingsState.value.holdSpeed.toDouble()
        val holdJob = if (gesturesOn && holdRate > 0.0) scope.launch {
            delay(450)
            if (axis == Axis.None && !multi && !player.paused.value) {
                holding = true
                speedBefore = player.getDouble("speed") ?: 1.0
                player.setSpeed(holdRate)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                hud = Hud(HudKind.Tap, label = "\u2066${holdRate.toInt()}×\u2069")
            }
        } else null
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                multi = true
                holdJob?.cancel()
                if (gesturesOn && settingsState.value.gesturePinch) {
                    val dist = (pressed[0].position - pressed[1].position).getDistance()
                    if (pinch0 == 0f) {
                        pinch0 = dist
                        zoom0 = player.getDouble(if (onSub) "sub-scale" else "video-zoom") ?: if (onSub) 1.0 else 0.0
                    } else if (dist > 0f && onSub) {
                        // pinch that starts on the subtitle line resizes the subtitle, not the video
                        val sc = (zoom0 * dist / pinch0).coerceIn(0.6, 2.0)
                        player.setDouble("sub-scale", sc)
                        hud = Hud(HudKind.Tap, label = context.getString(R.string.sub_size_hud, (sc * 100).roundToInt().toString()))
                    } else if (dist > 0f) {
                        player.setDouble("video-zoom", zoom0 + ln(dist / pinch0) / ln(2.0))
                    }
                    pressed.forEach { it.consume() }
                }
            } else if (pressed.size == 1 && !multi) {
                val c = pressed[0]
                totalX = c.position.x - down.position.x
                totalY = c.position.y - down.position.y
                if (axis == Axis.None && !holding && (abs(totalX) > slop || abs(totalY) > slop)) {
                    holdJob?.cancel()
                    val prefs = settingsState.value
                    axis = pickAxis(gesturesOn, onSub, totalX, totalY, leftHalf = down.position.x < w / 2, brightness = prefs.gestureBrightness, volume = prefs.gestureVolume, seek = prefs.gestureSeek)
                    if (axis == Axis.Vertical) {
                        if (down.position.x < w / 2) {
                            startBright = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                        } else {
                            startVol = player.volume.value
                            lastVol = startVol
                        }
                    } else if (axis == Axis.Horizontal) {
                        startSec = player.position.value
                    }
                }
                when (axis) {
                    Axis.Subtitle -> {
                        val pos = (subPos0 + totalY / h * 100.0).roundToInt().coerceIn(0, 100)
                        player.setString("sub-pos", pos.toString())
                        hud = Hud(HudKind.Tap, label = context.getString(R.string.sub_position_hud, pos.toString()))
                        c.consume()
                    }
                    Axis.Vertical -> {
                        val frac = -totalY / h
                        if (down.position.x < w / 2) {
                            val b = (startBright + frac).coerceIn(0.01f, 1f)
                            setBrightness(b)
                            hud = Hud(HudKind.Brightness, b, "\u2066${(b * 100).toInt()}%\u2069")
                        } else {
                            val top = settingsState.value.maxVolume.toDouble()
                            val v = (startVol + frac * top).coerceIn(0.0, top)
                            if ((lastVol < 100.0) != (v < 100.0)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            lastVol = v
                            player.setVolume(v)
                            hud = Hud(HudKind.Volume, (v / top).toFloat(), "\u2066${v.toInt()}%\u2069")
                        }
                        c.consume()
                    }
                    Axis.Horizontal -> {
                        val dur = player.duration.value
                        val target = (startSec + totalX / w * 180.0).coerceIn(0.0, dur.coerceAtLeast(0.0))
                        seekPreview = target
                        val d = target - startSec
                        hud = Hud(
                            HudKind.Seek,
                            label = (if (d >= 0) "+" else "−") + formatTime((abs(d) * 1000).toLong()) +
                                " / " + formatTime((target * 1000).toLong()),
                        )
                        c.consume()
                    }
                    else -> {}
                }
            }
        } while (event.changes.any { it.pressed })
        holdJob?.cancel()
        if (holding) {
            player.setSpeed(speedBefore)
            clearHudLater()
            return@awaitEachGesture
        }

        if (axis == Axis.Horizontal) {
            seekPreview?.let { player.seekTo(it) }
            seekPreview = null
        }
        if (multi && onSub && pinch0 > 0f) {
            val sc = player.getDouble("sub-scale")
            if (sc != null) AppSettings.get(context).update { it.copy(subStyle = it.subStyle.copy(scale = sc.toFloat())) }
            clearHudLater()
        } else if (axis == Axis.Subtitle) {
            val pos = player.getString("sub-pos")?.toIntOrNull()
            if (pos != null) AppSettings.get(context).update { it.copy(subStyle = it.subStyle.copy(pos = pos)) }
            clearHudLater()
        } else if (axis == Axis.Vertical || axis == Axis.Horizontal) {
            clearHudLater()
        } else if (axis == Axis.None && !multi) {
            val now = System.currentTimeMillis()
            val isDouble = now - lastTapAt < 300 && (down.position - lastTapPos).getDistance() < 80.dp.toPx()
            if (isDouble) {
                lastTapAt = 0L
                tapToken++
                if (gesturesOn && settingsState.value.gestureDoubleTap) {
                    val f = down.position.x / w
                    when {
                        f < 1f / 3f -> tapSeek(-1)
                        f > 2f / 3f -> tapSeek(1)
                        else -> if (player.eof.value) { player.seekTo(0.0); player.setPaused(false) } else player.togglePause()
                    }
                }
            } else {
                lastTapAt = now
                lastTapPos = down.position
                val token = ++tapToken
                scope.launch {
                    delay(300)
                    if (tapToken == token && panel == null) overlayVisible = !overlayVisible
                }
            }
        }
    }
}
