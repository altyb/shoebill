@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.altayeb.shoebill.music

import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.SideEffect
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.library.logic.RingtoneLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private fun clock(ms: Long): String = String.format(Locale.US, "%d:%02d.%d", ms / 60_000, ms / 1000 % 60, ms / 100 % 10)

/** Pick a part of a song, fade it in and out, set its volume, hear it, and save it as a ringtone, notification or alarm. */
@Composable
fun RingtoneMaker(song: Song, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dur = song.durationMs.coerceAtLeast(RingtoneLogic.MIN_MS)
    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(minOf(30_000L, dur)) }
    var fadeIn by remember { mutableLongStateOf(0L) }
    var fadeOut by remember { mutableLongStateOf(2_000L) }
    var gain by remember { mutableFloatStateOf(1f) }
    var kind by remember { mutableStateOf(RingtoneCutter.Kind.Ringtone) }
    var setDefault by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(song.title.take(60)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<Uri?>(null) }
    val len = end - start
    fun set(s: Long, e: Long) { val (a, b) = RingtoneLogic.clamp(s, e, dur); start = a; end = b; saved = null }

    val peaks by produceState<FloatArray?>(null, song.id) {
        value = withContext(Dispatchers.IO) { runCatching { RingtoneCutter.peaks(ctx, song.uri, dur) }.getOrElse { FloatArray(0) } }
    }

    // Preview: the music service's player keeps its queue; this one plays only the cut, with the same curve.
    var previewing by remember { mutableStateOf(false) }
    var playhead by remember { mutableLongStateOf(-1L) }
    val mp = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { runCatching { mp.release() } } }
    LaunchedEffect(previewing) {
        if (!previewing) { runCatching { if (mp.isPlaying) mp.stop() }; playhead = -1; return@LaunchedEffect }
        val ok = withContext(Dispatchers.IO) { runCatching { mp.reset(); mp.setDataSource(ctx, song.uri); mp.prepare(); mp.seekTo(start.toInt()) }.isSuccess }
        if (!ok) { previewing = false; return@LaunchedEffect }
        mp.start()
        val (fi, fo) = RingtoneLogic.fades(fadeIn, fadeOut, len)
        while (isActive) {
            val p = mp.currentPosition.toLong()
            if (p >= end || !mp.isPlaying) break
            playhead = p
            val v = RingtoneLogic.envelope(p - start, len, fi, fo, gain).coerceIn(0f, 1f) // a player volume above 1 does nothing
            mp.setVolume(v, v)
            delay(40)
        }
        previewing = false
    }

    var askWrite by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Edge to edge dialog has its own window: give its status bar icons the page's contrast, not white on white.
        val view = androidx.compose.ui.platform.LocalView.current
        val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect { (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view).run { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light } } }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.ring_title)) }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) } })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val cs = MaterialTheme.colorScheme
                val p = peaks
                if (p == null) Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    LoadingIndicator(Modifier.size(32.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.ring_reading))
                } else Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                    // The whole song as bars; the chosen part is bright, the playhead a line.
                    val n = p.size.coerceAtLeast(1); val w = size.width / n; val mid = size.height / 2
                    for (i in 0 until p.size) {
                        val t = i.toLong() * dur / n
                        val on = t in start until end
                        val h = (p[i].coerceAtLeast(0.02f)) * size.height / 2
                        drawLine(if (on) cs.primary else cs.outlineVariant, Offset(i * w + w / 2, mid - h), Offset(i * w + w / 2, mid + h), strokeWidth = (w * 0.7f).coerceAtLeast(1f))
                    }
                    if (playhead >= 0) { val x = playhead.toFloat() / dur * size.width; drawLine(cs.tertiary, Offset(x, 0f), Offset(x, size.height), 3f) }
                }
                RangeSlider(value = start.toFloat()..end.toFloat(), onValueChange = { r -> set(r.start.toLong(), r.endInclusive.toLong()) }, valueRange = 0f..dur.toFloat())
                // Fine steps for the exact beat; the slider alone is too coarse on a long song.
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ring_start, clock(start)), style = MaterialTheme.typography.labelLarge)
                        Row { TextButton(onClick = { set(start - 100, end) }) { Text("−0.1") }; TextButton(onClick = { set(start + 100, end) }) { Text("+0.1") } }
                    }
                    Text(clock(len), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium, color = cs.primary) // level with the ±0.1 buttons
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(stringResource(R.string.ring_end, clock(end)), style = MaterialTheme.typography.labelLarge)
                        Row { TextButton(onClick = { set(start, end - 100) }) { Text("−0.1") }; TextButton(onClick = { set(start, end + 100) }) { Text("+0.1") } }
                    }
                }
                val maxFade = (len / 2).coerceAtMost(10_000)
                Text(stringResource(R.string.ring_fade_in, fadeIn / 1000f), style = MaterialTheme.typography.labelLarge)
                Slider(fadeIn.coerceAtMost(maxFade).toFloat(), { fadeIn = (it / 250).toLong() * 250; saved = null }, valueRange = 0f..maxFade.toFloat().coerceAtLeast(1f))
                Text(stringResource(R.string.ring_fade_out, fadeOut / 1000f), style = MaterialTheme.typography.labelLarge)
                Slider(fadeOut.coerceAtMost(maxFade).toFloat(), { fadeOut = (it / 250).toLong() * 250; saved = null }, valueRange = 0f..maxFade.toFloat().coerceAtLeast(1f))
                Text(stringResource(R.string.ring_volume, (gain * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(gain, { gain = (it * 20).toInt() / 20f; saved = null }, valueRange = 0.5f..2f)
                FilledTonalButton(onClick = { previewing = !previewing }, enabled = !busy) {
                    Icon(if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (previewing) R.string.ring_stop else R.string.ring_preview))
                }
                OutlinedTextField(name, { name = it.take(80); saved = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.ring_name)) })
                Text(stringResource(R.string.ring_save_as), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RingtoneCutter.Kind.entries.forEach { k ->
                        FilterChip(kind == k, { kind = k; saved = null }, { Text(stringResource(when (k) {
                            RingtoneCutter.Kind.Ringtone -> R.string.ring_kind_ringtone; RingtoneCutter.Kind.Notification -> R.string.ring_kind_notification; RingtoneCutter.Kind.Alarm -> R.string.ring_kind_alarm
                        })) })
                    }
                }
                Text(stringResource(R.string.ring_where, kind.dir), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(Modifier.offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(setDefault, { setDefault = it }); Text(stringResource(R.string.ring_set_default)) }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (saved != null) cs.primary else cs.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    OutlinedButton(onClick = close, Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = {
                        previewing = false; busy = true; message = null
                        scope.launch {
                            val tmp = File(ctx.cacheDir, "ringtone.m4a")
                            val (fi, fo) = RingtoneLogic.fades(fadeIn, fadeOut, len)
                            val r = withContext(Dispatchers.IO) { runCatching {
                                RingtoneCutter.cut(ctx, song.uri, start, end, fi, fo, gain, tmp)
                                RingtoneCutter.save(ctx, tmp, RingtoneLogic.fileName(name, song.title), kind)
                            }.also { tmp.delete() } }
                            busy = false
                            r.onSuccess { uri ->
                                saved = uri
                                message = ctx.getString(R.string.ring_saved, kind.dir)
                                if (setDefault) {
                                    // Changing the phone's sound needs "modify system settings", given once in Android's own page.
                                    if (Settings.System.canWrite(ctx)) { RingtoneManager.setActualDefaultRingtoneUri(ctx, kind.type, uri); message = ctx.getString(R.string.ring_set_done) }
                                    else askWrite = true
                                }
                            }.onFailure { message = ctx.getString(R.string.ring_failed) }
                        }
                    }, Modifier.weight(1f), enabled = !busy && peaks != null) {
                        if (busy) LoadingIndicator(Modifier.size(20.dp)) else Text(stringResource(R.string.ring_save))
                    }
                }
                if (askWrite) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ring_need_permission), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))) } }) { Text(stringResource(R.string.ring_allow)) }
                        Button(onClick = {
                            val u = saved ?: return@Button
                            if (Settings.System.canWrite(ctx)) { RingtoneManager.setActualDefaultRingtoneUri(ctx, kind.type, u); askWrite = false; message = ctx.getString(R.string.ring_set_done) }
                        }) { Text(stringResource(R.string.ring_set_now)) }
                    }
                }
            }
        }
    }
}
