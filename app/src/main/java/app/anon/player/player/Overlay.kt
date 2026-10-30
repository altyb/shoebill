package app.anon.player.player

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import app.anon.player.library.formatTime
import app.anon.player.ui.theme.AnonAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

enum class Panel { Subs, Audio, Options }
enum class HudKind { Brightness, Volume, Seek, Tap }

/** Gesture feedback: pill for brightness/volume (fraction), label for seek and double-tap. */
class Hud(val kind: HudKind, val fraction: Float = 0f, val label: String = "")

/** Snackbar-style pill; [onAction] runs when its button is pressed. */
class Notice(val text: String, val action: String? = null, val onAction: () -> Unit = {})

/** Transport + bars. Time and the seek bar are forced LTR (rule ق-04). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Controls(
    player: MpvPlayer,
    title: String,
    codec: String?,
    subLabel: String,
    decoderLabel: String,
    speedLabel: String,
    paused: Boolean,
    eof: Boolean,
    positionSec: Double,
    durationSec: Double,
    shownSec: Double,
    abA: Double?,
    abB: Double?,
    hasPrev: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onPanel: (Panel) -> Unit,
    onLock: () -> Unit,
    onRotate: () -> Unit,
    onAspect: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onScrub: (Double) -> Unit,
    onScrubEnd: (Double?) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.75f)),
            ),
        ).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // top bar
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                codec?.let { Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium) }
            }
            IconButton(onClick = { onPanel(Panel.Subs) }) { Icon(Icons.Rounded.Subtitles, "الترجمة", tint = Color.White) }
            IconButton(onClick = { onPanel(Panel.Audio) }) { Icon(Icons.Rounded.Audiotrack, "الصوت", tint = Color.White) }
            IconButton(onClick = { onPanel(Panel.Options) }) { Icon(Icons.Rounded.MoreVert, "المزيد", tint = Color.White) }
        }

        Spacer(Modifier.weight(1f))

        // transport: prev / -10 / play-pause / +10 / next, always in this order
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev, enabled = hasPrev, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "السابق", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { player.seekBy(-10.0) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Replay10, "−10 ث", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                val corner by animateDpAsState(
                    targetValue = if (paused) 48.dp else 24.dp,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                    label = "playCorner",
                )
                FilledIconButton(
                    onClick = {
                        if (eof) { player.seekTo(0.0); player.setPaused(false) } else player.togglePause()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp).size(96.dp),
                    shape = RoundedCornerShape(corner),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Icon(
                        when { eof -> Icons.Rounded.Replay; paused -> Icons.Rounded.PlayArrow; else -> Icons.Rounded.Pause },
                        if (paused) "تشغيل" else "إيقاف مؤقت",
                        modifier = Modifier.size(48.dp),
                    )
                }
                IconButton(onClick = { player.seekBy(10.0) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Forward10, "+10 ث", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "التالي", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // bottom: LTR seek bar, times, two floating pills
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SeekBar(durationSec, shownSec, paused, abA, abB, onScrub, onScrubEnd)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime((shownSec * 1000).toLong()), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "-" + formatTime((max(0.0, durationSec - shownSec) * 1000).toLong()),
                        color = Color.White, style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Pill {
                        IconButton(onClick = onLock) { Icon(Icons.Rounded.Lock, "قفل", tint = Color.White) }
                        IconButton(onClick = onRotate) { Icon(Icons.Rounded.ScreenRotation, "تدوير", tint = Color.White) }
                        IconButton(onClick = onAspect) { Icon(Icons.Rounded.AspectRatio, "نسبة العرض", tint = Color.White) }
                    }
                    Pill {
                        TextButton(onClick = { onPanel(Panel.Subs) }) { Text(subLabel, color = Color.White) }
                        TextButton(onClick = { onPanel(Panel.Audio) }) { Text(speedLabel, color = Color.White) }
                        TextButton(onClick = { onPanel(Panel.Options) }) { Text(decoderLabel, color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.45f)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = { content() })
    }
}

/** Seek bar: wavy while playing, flat when paused, amber marks for the A-B loop. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SeekBar(
    durationSec: Double,
    shownSec: Double,
    paused: Boolean,
    abA: Double?,
    abB: Double?,
    onScrub: (Double) -> Unit,
    onScrubEnd: (Double?) -> Unit,
) {
    val frac = if (durationSec > 0) (shownSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f
    val dur by rememberUpdatedState(durationSec)
    val scrubHandler by rememberUpdatedState(onScrub)
    val endHandler by rememberUpdatedState(onScrubEnd)
    Box(
        Modifier.fillMaxWidth().height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> if (dur > 0) endHandler(o.x / size.width * dur) }
            }
            .pointerInput(Unit) {
                var last = 0.0
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        last = (o.x / size.width * dur).coerceIn(0.0, dur)
                        scrubHandler(last)
                    },
                    onHorizontalDrag = { c, _ ->
                        c.consume()
                        last = (c.position.x / size.width * dur).coerceIn(0.0, dur)
                        scrubHandler(last)
                    },
                    onDragEnd = { endHandler(last) },
                    onDragCancel = { endHandler(null) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (paused) {
            LinearProgressIndicator(
                progress = { frac }, modifier = Modifier.fillMaxWidth(),
                color = Color.White, trackColor = Color.White.copy(alpha = 0.3f),
            )
        } else {
            LinearWavyProgressIndicator(
                progress = { frac }, modifier = Modifier.fillMaxWidth(),
                color = Color.White, trackColor = Color.White.copy(alpha = 0.3f),
            )
        }
        Canvas(Modifier.matchParentSize()) {
            if (durationSec <= 0) return@Canvas
            fun x(sec: Double) = (sec / durationSec).toFloat().coerceIn(0f, 1f) * size.width
            val a = abA
            val b = abB
            if (a != null && b != null && b > a) {
                drawRect(AnonAmber.copy(alpha = 0.25f), topLeft = Offset(x(a), 0f), size = Size(x(b) - x(a), size.height))
            }
            listOfNotNull(a, b).forEach { sec ->
                drawRect(
                    AnonAmber,
                    topLeft = Offset(x(sec) - 1.5.dp.toPx(), size.height / 2 - 10.dp.toPx()),
                    size = Size(3.dp.toPx(), 20.dp.toPx()),
                )
            }
        }
    }
}

/** Lock mode: only a cookie-shaped button; hold 2 s to unlock, ring fills while held. */
@Composable
fun LockOverlay(onUnlock: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ring = remember { Animatable(0f) }
    Box(Modifier.fillMaxSize().padding(start = 24.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.size(72.dp).pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val fill = scope.launch { ring.animateTo(1f, tween(2000)); onUnlock() }
                    val released = tryAwaitRelease()
                    if (released) { fill.cancel(); scope.launch { ring.animateTo(0f) } }
                })
            },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { ring.value }, modifier = Modifier.matchParentSize(),
                color = MaterialTheme.colorScheme.primary,
            )
            // not a button: a clickable child would eat the press and the hold above would never start
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Lock, "قفل: اضغط مطولاً للفتح", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
        }
        Text(
            "اضغط مطولاً للفتح", color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 96.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Physical-side gesture feedback: brightness/volume pill or seek/double-tap label. */
@Composable
fun GestureHud(hud: Hud?) {
    if (hud == null) return
    when (hud.kind) {
        HudKind.Brightness, HudKind.Volume -> {
            val left = hud.kind == HudKind.Brightness
            Box(
                Modifier.fillMaxSize().padding(horizontal = 28.dp),
                contentAlignment = if (left) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight, // physical side (ق-05), not RTL start/end
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (left) Icons.Rounded.BrightnessMedium else Icons.Rounded.VolumeUp, null, tint = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        // the track clips the fill, so a short fill stays inside the rounded shape
                        Modifier.width(44.dp).height(170.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(hud.fraction.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(hud.label, color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        HudKind.Seek, HudKind.Tap -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f)) {
                Text(hud.label, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
    }
}

/** Snackbar-style pill at the bottom; the caller clears it after its timeout. */
@Composable
fun NoticePill(notice: Notice, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice.text, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyLarge)
                notice.action?.let { label ->
                    TextButton(onClick = { notice.onAction(); onDismiss() }) {
                        Text(label, color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
    }
}

/** End-of-file card for the next video in the same folder (ش-14). */
@Composable
fun EndCard(name: String, countdown: Int, onPlayNow: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomEnd) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(300.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("التالي خلال $countdown", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPlayNow) { Text("شغّل الآن") }
                    FilledTonalButton(onClick = onCancel) { Text("إلغاء") }
                }
            }
        }
    }
}
