package dev.altayeb.shoebill.player

import androidx.compose.animation.togetherWith
import dev.altayeb.shoebill.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import dev.altayeb.shoebill.library.formatTime
import dev.altayeb.shoebill.ui.theme.BrandAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** Behind every control, so white icons stay readable on any picture, a white frame included (about 5:1 at worst). */
private val ControlSurface = Color.Black.copy(alpha = 0.62f)

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
    scope: androidx.compose.animation.AnimatedVisibilityScope,
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
    val motion = MaterialTheme.motionScheme
    // Each part comes from its own edge on the Expressive spring and leaves on the quick one.
    fun Modifier.slideFrom(top: Boolean) = with(scope) {
        animateEnterExit(
            enter = androidx.compose.animation.slideInVertically(motion.defaultSpatialSpec()) { if (top) -it else it } + fadeIn(motion.defaultEffectsSpec()),
            exit = androidx.compose.animation.slideOutVertically(motion.fastSpatialSpec()) { if (top) -it else it } + fadeOut(motion.fastEffectsSpec()),
        )
    }
    val popIn = with(scope) {
        Modifier.animateEnterExit(
            enter = androidx.compose.animation.scaleIn(motion.defaultSpatialSpec(), initialScale = 0.8f) + fadeIn(motion.defaultEffectsSpec()),
            exit = androidx.compose.animation.scaleOut(motion.fastSpatialSpec(), targetScale = 0.9f) + fadeOut(motion.fastEffectsSpec()),
        )
    }
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.35f)),
            ),
        ).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // top bar
        Surface(Modifier.slideFrom(top = true), shape = RoundedCornerShape(28.dp), color = ControlSurface) { Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                codec?.let { Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium) }
            }
            IconButton(onClick = { onPanel(Panel.Subs) }) { Icon(Icons.Rounded.Subtitles, stringResource(R.string.player_subtitles), tint = Color.White) }
            IconButton(onClick = { onPanel(Panel.Audio) }) { Icon(Icons.Rounded.Audiotrack, stringResource(R.string.player_audio), tint = Color.White) }
            IconButton(onClick = { onPanel(Panel.Options) }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.player_more), tint = Color.White) }
        } }

        Spacer(Modifier.weight(1f))

        // transport: prev / -10 / play-pause / +10 / next, always in this order
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth().then(popIn), horizontalArrangement = Arrangement.Center) { Surface(shape = RoundedCornerShape(50), color = ControlSurface) {
                // A Button Group: the pressed button widens and squeezes its neighbours, Expressive's own press motion.
                androidx.compose.material3.ButtonGroup(overflowIndicator = {}, modifier = Modifier.padding(6.dp).width(320.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    fun side(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) = customItem({
                        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp).animateWidth(src), interactionSource = src, shapes = IconButtonDefaults.shapes()) {
                            Icon(icon, stringResource(label), tint = Color.White.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(30.dp))
                        }
                    }, {})
                    side(Icons.Rounded.SkipPrevious, R.string.player_previous, hasPrev, onPrev)
                    side(Icons.Rounded.Replay10, R.string.player_rewind_10, true) { player.seekBy(-10.0) }
                    customItem({
                        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        // Round when paused, square while playing: the stock toggle shapes, no hand-made corner spring.
                        androidx.compose.material3.FilledIconToggleButton(
                            checked = !paused && !eof,
                            onCheckedChange = { if (eof) { player.seekTo(0.0); player.setPaused(false) } else player.togglePause() },
                            modifier = Modifier.padding(horizontal = 4.dp).weight(1.4f).height(72.dp).animateWidth(src),
                            interactionSource = src,
                            shapes = IconButtonDefaults.toggleableShapes(),
                            colors = IconButtonDefaults.filledIconToggleButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer, checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            androidx.compose.animation.AnimatedContent(
                                when { eof -> 2; paused -> 0; else -> 1 },
                                transitionSpec = {
                                    (androidx.compose.animation.scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f) + fadeIn(motion.fastEffectsSpec())) togetherWith fadeOut(motion.fastEffectsSpec())
                                },
                                label = "playIcon",
                            ) { state ->
                                Icon(
                                    when (state) { 2 -> Icons.Rounded.Replay; 0 -> Icons.Rounded.PlayArrow; else -> Icons.Rounded.Pause },
                                    stringResource(if (state == 1) R.string.pause else R.string.play),
                                    modifier = Modifier.size(38.dp),
                                )
                            }
                        }
                    }, {})
                    side(Icons.Rounded.Forward10, R.string.player_forward_10, true) { player.seekBy(10.0) }
                    side(Icons.Rounded.SkipNext, R.string.player_next, hasNext, onNext)
                }
            } }
        }

        Spacer(Modifier.weight(1f))

        // bottom: LTR seek bar, times and actions on one card; the preview floats above it
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            var scrubbing by remember { mutableStateOf(false) }
            SeekPreview(player.shareableUri, shownSec, durationSec, scrubbing)
            Surface(Modifier.slideFrom(top = false), shape = RoundedCornerShape(28.dp), color = ControlSurface) { Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                SeekBar(durationSec, shownSec, paused, abA, abB, { scrubbing = true; onScrub(it) }, { scrubbing = false; onScrubEnd(it) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime((shownSec * 1000).toLong()), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "-" + formatTime((max(0.0, durationSec - shownSec) * 1000).toLong()),
                        color = Color.White, style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Pill {
                        IconButton(onClick = onLock, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Lock, stringResource(R.string.player_lock), tint = Color.White) }
                        IconButton(onClick = onRotate, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ScreenRotation, stringResource(R.string.player_rotate), tint = Color.White) }
                        IconButton(onClick = onAspect, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.AspectRatio, stringResource(R.string.player_aspect), tint = Color.White) }
                    }
                    Pill {
                        TextButton(onClick = { onPanel(Panel.Subs) }) { Text(subLabel, color = Color.White) }
                        TextButton(onClick = { onPanel(Panel.Audio) }) { Text(speedLabel, color = Color.White) }
                        TextButton(onClick = { onPanel(Panel.Options) }) { Text(decoderLabel, color = Color.White) }
                    }
                }
            } }
        }
    }
}

/**
 * A frame above the seek bar while dragging. Only local media-library files: the vault has no shareable uri, so nothing
 * of it is ever decoded here, and network files would stall on every position.
 */
@Composable
private fun SeekPreview(uri: android.net.Uri?, sec: Double, duration: Double, active: Boolean) {
    if (!active || uri == null || uri.authority != "media" || duration <= 0) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val retriever = remember(uri) { runCatching { android.media.MediaMetadataRetriever().apply { setDataSource(ctx, uri) } }.getOrNull() }
    androidx.compose.runtime.DisposableEffect(retriever) { onDispose { runCatching { retriever?.release() } } }
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val step = (sec / (duration / 200).coerceAtLeast(1.0)).toInt() // about 200 stops along any video, so a drag does not queue a decode per pixel
    androidx.compose.runtime.LaunchedEffect(step) {
        delay(80)
        frame = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { retriever?.getFrameAtTime((sec * 1_000_000).toLong(), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                android.graphics.Bitmap.createScaledBitmap(it, 320, (320f * it.height / it.width).toInt().coerceAtLeast(1), true)
            } }.getOrNull()
        } ?: frame
    }
    val f = frame ?: return
    val x = (sec / duration).toFloat().coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.BiasAlignment(x * 2 - 1, 0f)) {
        androidx.compose.foundation.Image(f.asImageBitmap(), null, Modifier.width(160.dp).clip(RoundedCornerShape(10.dp)))
    }
}

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, content = { content() })
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
                drawRect(BrandAmber.copy(alpha = 0.25f), topLeft = Offset(x(a), 0f), size = Size(x(b) - x(a), size.height))
            }
            listOfNotNull(a, b).forEach { sec ->
                drawRect(
                    BrandAmber,
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
                Icon(Icons.Rounded.Lock, stringResource(R.string.player_locked_hold), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
        }
        Text(
            stringResource(R.string.player_hold_unlock), color = Color.White, style = MaterialTheme.typography.labelLarge,
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
                Surface(shape = RoundedCornerShape(30.dp), color = ControlSurface) { Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
                } }
            }
        }
        HudKind.Seek, HudKind.Tap -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(50), color = ControlSurface) {
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
                Text(stringResource(R.string.player_next_in, countdown.toString()), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPlayNow) { Text(stringResource(R.string.player_play_now)) }
                    FilledTonalButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}
