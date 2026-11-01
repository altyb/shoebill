package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.R
import androidx.compose.ui.res.stringResource

import dev.altayeb.shoebill.ui.BrandLoader
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.altayeb.shoebill.data.FileState
import dev.altayeb.shoebill.ui.EmptyState
import dev.altayeb.shoebill.ui.theme.BrandAmber
import kotlinx.coroutines.launch

private enum class Access { Full, Partial, None }

private fun accessOf(ctx: Context, permission: String): Access = when {
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED -> Access.Full
    Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED -> Access.Partial
    else -> Access.None
}

/**
 * Permission + scanning + empty states around the list. Content gets the video list.
 * Denied: explanation + "افتح الإعدادات". Partial (Android 14): banner with "اختر المزيد". Re-checked on every resume.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoGate(content: @Composable (List<Video>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                     else Manifest.permission.READ_EXTERNAL_STORAGE
    var access by remember { mutableStateOf(accessOf(ctx, permission)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        access = accessOf(ctx, permission)
        if (access != Access.None) scope.launch { VideoIndex.refresh(ctx) }
    }
    val videos by VideoIndex.videos.collectAsStateWithLifecycle()

    // Re-check on every resume (the user may have changed it in Settings); scan whenever there is access.
    LifecycleResumeEffect(Unit) {
        access = accessOf(ctx, permission)
        if (access != Access.None) scope.launch { VideoIndex.refresh(ctx) }
        onPauseOrDispose {}
    }

    if (access == Access.None) {
        EmptyState(dev.altayeb.shoebill.R.drawable.art_video,
            if (asked) stringResource(R.string.lib_access_denied)
            else stringResource(R.string.lib_access_needed),
            action = {
                if (asked) {
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                    }) { Text(stringResource(R.string.lib_open_settings)) }
                } else {
                    Button(onClick = { launcher.launch(permission) }) { Text(stringResource(R.string.lib_allow_access)) }
                }
            },
        )
        return
    }

    val list = videos
    Column(Modifier.fillMaxSize()) {
        if (access == Access.Partial) {
            Surface(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.lib_partial_access), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                    TextButton(onClick = { launcher.launch(permission) }) { Text(stringResource(R.string.lib_pick_more)) }
                }
            }
        }
        // The banner already took the status bar; stop the content's Scaffold from padding it again.
        val inset = if (access == Access.Partial) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
        Box(Modifier.weight(1f).then(inset)) {
            when {
                list == null -> CenteredGate { BrandLoader() }
                list.isEmpty() -> EmptyState(dev.altayeb.shoebill.R.drawable.art_video, stringResource(R.string.lib_no_videos))
                else -> content(list)
            }
        }
    }
}

@Composable
private fun CenteredGate(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** Grouped-list corners: 24 dp on the outer ends of the group, 4 dp between items. */
fun groupedShape(index: Int, count: Int): Shape {
    val top = if (index == 0) 24.dp else 4.dp
    val bottom = if (index == count - 1) 24.dp else 4.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Playback progress derived from the stored state. Finished = within 30 s of the end (rule ق-12). */
class Progress(val fraction: Float, val partial: Boolean, val finished: Boolean)

fun progressOf(v: Video, s: FileState?): Progress {
    val pos = s?.positionMs ?: 0L
    val dur = v.durationMs
    val finished = dur > 0 && pos >= dur - 30_000
    val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    return Progress(fraction, partial = !finished && pos >= 5_000, finished = finished)
}

/** Name with the match bolded (amber). */
fun highlight(name: String, query: String): AnnotatedString = buildAnnotatedString {
    append(name)
    if (query.isNotBlank()) {
        var i = name.indexOf(query, ignoreCase = true)
        while (i >= 0) {
            addStyle(SpanStyle(background = BrandAmber.copy(alpha = 0.45f), fontWeight = FontWeight.SemiBold), i, i + query.length)
            i = name.indexOf(query, i + query.length, ignoreCase = true)
        }
    }
}

/** File names are LTR even inside the RTL UI. */
@Composable
fun LtrText(text: AnnotatedString, style: TextStyle, maxLines: Int, color: Color = Color.Unspecified) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = text, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start, color = color,
        )
    }
}

@Composable
fun DurationBadge(ms: Long, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.7f)) {
        Text(formatTime(ms), color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.4f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(BrandAmber))
    }
}

@Composable
fun TagChip(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** Press feedback: shrink to 0.97 on a stiff spring while the source is pressed. Ripple comes from the clickable. */
fun Modifier.pressScale(source: InteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 800f), label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** List row in a grouped list: 128x72 thumb, name, tags, watched state, ⋮. */
@Composable
fun VideoRow(
    v: Video,
    state: FileState?,
    query: String,
    shape: Shape,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = progressOf(v, state)
    val src = remember { MutableInteractionSource() }
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().pressScale(src).combinedClickable(
            interactionSource = src, indication = LocalIndication.current, onClick = onClick, onLongClick = onLongClick,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 128.dp, height = 72.dp)) {
                Thumb(v.uri, Modifier.fillMaxSize(), RoundedCornerShape(12.dp),
                    durationMs = v.durationMs, savedPosMs = state?.positionMs ?: -1, shared = true)
                DurationBadge(v.durationMs, Modifier.align(Alignment.BottomEnd).padding(4.dp))
                if (p.partial) ProgressLine(p.fraction, Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                LtrText(highlight(v.name, query), MaterialTheme.typography.bodyLarge, maxLines = 2)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (v.quality.isNotEmpty()) TagChip(v.quality)
                    TagChip(formatSize(v.sizeBytes))
                }
                if (p.finished) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.lib_watched), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.lib_options)) }
        }
    }
}

/** Grid tile: 16:9 thumb with duration and progress, name below. */
@Composable
fun VideoCard(
    v: Video,
    state: FileState?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = progressOf(v, state)
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.fillMaxWidth().pressScale(src).combinedClickable(
            interactionSource = src, indication = LocalIndication.current, onClick = onClick, onLongClick = onLongClick,
        )
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp))
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)) else Modifier)
        ) {
            Thumb(v.uri, Modifier.fillMaxSize(), durationMs = v.durationMs, savedPosMs = state?.positionMs ?: -1, shared = true)
            DurationBadge(v.durationMs, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            if (p.partial) ProgressLine(p.fraction, Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.height(8.dp))
        LtrText(AnnotatedString(v.name), MaterialTheme.typography.bodyMedium, maxLines = 2)
        Text(
            listOfNotNull(v.quality.ifEmpty { null }, formatSize(v.sizeBytes)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
