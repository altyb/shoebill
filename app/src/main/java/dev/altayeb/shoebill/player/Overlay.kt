package dev.altayeb.shoebill.player

import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
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
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dev.altayeb.shoebill.ui.theme.PlayerColors
import dev.altayeb.shoebill.ui.theme.Radius
import kotlinx.coroutines.Dispatchers

/** Behind every control, so white icons stay readable on any picture, a white frame included (about 5:1 at worst). */
private val ControlSurface = PlayerColors.Surface

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
    scope: AnimatedVisibilityScope,
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
    val showRemaining = dev.altayeb.shoebill.settings.AppSettings.get(androidx.compose.ui.platform.LocalContext.current).settings.collectAsState().value.showRemaining
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
        ),
    ) {
        // Bars run to the screen edges with nothing floating above or below them. Cutouts are padded inside the surface.
        // top bar
        Surface(Modifier.slideFrom(top = true), shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp), color = ControlSurface) { Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = PlayerColors.On) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, color = PlayerColors.On, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                codec?.let { Text(it, color = PlayerColors.On.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium) }
            }
            IconButton(onClick = { onPanel(Panel.Subs) }) { Icon(Icons.Rounded.Subtitles, stringResource(R.string.player_subtitles), tint = PlayerColors.On) }
            IconButton(onClick = { onPanel(Panel.Audio) }) { Icon(Icons.Rounded.Audiotrack, stringResource(R.string.player_audio), tint = PlayerColors.On) }
            IconButton(onClick = { onPanel(Panel.Options) }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.player_more), tint = PlayerColors.On) }
        } }

        Spacer(Modifier.weight(1f))

        // transport: prev / -10 / play-pause / +10 / next, always in this order
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth().then(popIn), horizontalArrangement = Arrangement.Center) { Surface(shape = RoundedCornerShape(50), color = ControlSurface) {
                // Fixed sizes, so the row is symmetric on every phone: 4 x 48 + 72 + gaps + 8 inset = 296 dp, fits any 328 dp width.
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    @Composable fun Side(icon: ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
                        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
                            Icon(icon, stringResource(label), tint = PlayerColors.On.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(28.dp))
                        }
                    }
                    Side(Icons.Rounded.SkipPrevious, R.string.player_previous, hasPrev, onPrev)
                    Side(Icons.Rounded.Replay10, R.string.player_rewind_10, true) { player.seekBy(-10.0) }
                    // Round when paused, square while playing: the stock toggle shapes.
                    FilledIconToggleButton(
                        checked = !paused && !eof,
                        onCheckedChange = { if (eof) { player.seekTo(0.0); player.setPaused(false) } else player.togglePause() },
                        modifier = Modifier.size(72.dp),
                        shapes = IconButtonDefaults.toggleableShapes(),
                        colors = IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer, checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        AnimatedContent(
                            when { eof -> 2; paused -> 0; else -> 1 },
                            transitionSpec = {
                                (androidx.compose.animation.scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f) + fadeIn(motion.fastEffectsSpec())) togetherWith fadeOut(motion.fastEffectsSpec())
                            },
                            label = "playIcon",
                        ) { state ->
                            Icon(
                                when (state) { 2 -> Icons.Rounded.Replay; 0 -> Icons.Rounded.PlayArrow; else -> Icons.Rounded.Pause },
                                stringResource(if (state == 1) R.string.pause else R.string.play),
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                    Side(Icons.Rounded.Forward10, R.string.player_forward_10, true) { player.seekBy(10.0) }
                    Side(Icons.Rounded.SkipNext, R.string.player_next, hasNext, onNext)
                }
            } }
        }

        Spacer(Modifier.weight(1f))

        // bottom: LTR seek bar, times and actions on one card; the preview floats above it
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            var scrubbing by remember { mutableStateOf(false) }
            SeekPreview(player.shareableUri, shownSec, durationSec, scrubbing)
            Surface(Modifier.slideFrom(top = false), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = ControlSurface) { Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout).padding(vertical = 8.dp)) {
                // Every edge lands 16 in: bar and times directly, icon row at 4 because a 48 dp button holds its icon 12 in.
                Box(Modifier.padding(horizontal = 16.dp)) { SeekBar(durationSec, shownSec, paused, abA, abB, { scrubbing = true; onScrub(it) }, { scrubbing = false; onScrubEnd(it) }) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime((shownSec * 1000).toLong()), color = PlayerColors.On, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (showRemaining) "-" + formatTime((max(0.0, durationSec - shownSec) * 1000).toLong()) else formatTime((durationSec * 1000).toLong()),
                        color = PlayerColors.On, style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Pill {
                        IconButton(onClick = onLock, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Lock, stringResource(R.string.player_lock), tint = PlayerColors.On) }
                        IconButton(onClick = onRotate, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ScreenRotation, stringResource(R.string.player_rotate), tint = PlayerColors.On) }
                        IconButton(onClick = onAspect, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.AspectRatio, stringResource(R.string.player_aspect), tint = PlayerColors.On) }
                    }
                    Pill {
                        TextButton(onClick = { onPanel(Panel.Subs) }, Modifier.widthIn(max = 96.dp)) { Text(subLabel, color = PlayerColors.On, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        TextButton(onClick = { onPanel(Panel.Audio) }) { Text(speedLabel, color = PlayerColors.On) }
                        TextButton(onClick = { onPanel(Panel.Options) }) { Text(decoderLabel, color = PlayerColors.On) }
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
private fun SeekPreview(uri: Uri?, sec: Double, duration: Double, active: Boolean) {
    if (!active || uri == null || uri.authority != "media" || duration <= 0) return
    val ctx = LocalContext.current
    val retriever = remember(uri) { runCatching { MediaMetadataRetriever().apply { setDataSource(ctx, uri) } }.getOrNull() }
    DisposableEffect(retriever) { onDispose { runCatching { retriever?.release() } } }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    val step = (sec / (duration / 200).coerceAtLeast(1.0)).toInt() // about 200 stops along any video, so a drag does not queue a decode per pixel
    LaunchedEffect(step) {
        delay(80)
        frame = kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { retriever?.getFrameAtTime((sec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                Bitmap.createScaledBitmap(it, 320, (320f * it.height / it.width).toInt().coerceAtLeast(1), true)
            } }.getOrNull()
        } ?: frame
    }
    val f = frame ?: return
    val x = (sec / duration).toFloat().coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth(), contentAlignment = BiasAlignment(x * 2 - 1, 0f)) {
        Image(f.asImageBitmap(), null, Modifier.width(160.dp).clip(Radius.S))
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
                color = PlayerColors.On, trackColor = PlayerColors.On.copy(alpha = 0.3f),
            )
        } else {
            LinearWavyProgressIndicator(
                progress = { frac }, modifier = Modifier.fillMaxWidth(),
                color = PlayerColors.On, trackColor = PlayerColors.On.copy(alpha = 0.3f),
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
            CircularProgressIndicator(
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
            stringResource(R.string.player_hold_unlock), color = PlayerColors.On, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 96.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(horizontal = 12.dp, vertical = 8.dp),
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
                Surface(shape = Radius.XL, color = ControlSurface) { Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (left) Icons.Rounded.BrightnessMedium else Icons.Rounded.VolumeUp, null, tint = PlayerColors.On)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        // the track clips the fill, so a short fill stays inside the rounded shape
                        Modifier.width(44.dp).height(170.dp).clip(Radius.L).background(PlayerColors.On.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(hud.fraction.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(hud.label, color = PlayerColors.On, style = MaterialTheme.typography.labelLarge)
                } }
            }
        }
        HudKind.Seek, HudKind.Tap -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(50), color = ControlSurface) {
                Text(hud.label, color = PlayerColors.On, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
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
        Surface(shape = Radius.XL, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(300.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.player_next_in, countdown.toString()), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPlayNow, Modifier.weight(1f)) { Text(stringResource(R.string.player_play_now), maxLines = 1) }
                    FilledTonalButton(onClick = onCancel, Modifier.weight(1f)) { Text(stringResource(R.string.cancel), maxLines = 1) }
                }
            }
        }
    }
}
