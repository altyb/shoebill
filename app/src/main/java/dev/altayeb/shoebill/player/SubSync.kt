package dev.altayeb.shoebill.player

import dev.altayeb.shoebill.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.altayeb.shoebill.subs.SrtParser
import dev.altayeb.shoebill.ui.PanelHeader
import dev.altayeb.shoebill.ui.glass
import dev.altayeb.shoebill.subs.scaleFps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun SubTimingTab(player: MpvPlayer, subFile: File?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var cue by remember { mutableStateOf("") }
    var delaySec by remember { mutableStateOf(player.getDouble("sub-delay") ?: 0.0) }
    var message by remember { mutableStateOf<String?>(null) }

    // the cue on screen right now; refreshed a few times a second while the tool is open
    LaunchedEffect(Unit) {
        while (true) {
            cue = player.getString("sub-text").orEmpty()
            delaySec = player.getDouble("sub-delay") ?: 0.0
            delay(250)
        }
    }

    // whichever way the subtitle was added (picked, found beside the video, downloaded), mpv knows the file in use
    fun activeSub(): File? = player.getString("current-tracks/sub/external-filename")?.let(::File)?.takeIf { it.exists() } ?: subFile

    fun nudge(step: Double) {
        player.setDouble("sub-delay", (player.getDouble("sub-delay") ?: 0.0) + step)
        delaySec = player.getDouble("sub-delay") ?: 0.0
    }

    fun fixFps(from: Double, to: Double) {
        val f = activeSub()
        if (f == null || f.extension.lowercase() != "srt") {
            message = context.getString(R.string.sub_fps_srt_only)
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                f.writeText(SrtParser.write(SrtParser.parse(f.readText()).scaleFps(from, to)), Charsets.UTF_8)
            }
            player.command("sub-reload")
            message = context.getString(R.string.sub_fps_done)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.player_seconds, "%+.2f".format(Locale.ROOT, delaySec)), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { player.setDouble("sub-delay", 0.0); delaySec = 0.0 }) { Text(stringResource(R.string.sub_reset)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-0.5, -0.1, 0.1, 0.5).forEach { step ->
                FilledTonalButton(onClick = { nudge(step) }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text((if (step > 0) "+" else "−") + "%.1f".format(Locale.ROOT, kotlin.math.abs(step)))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cue.ifBlank { stringResource(R.string.sub_no_cue) }, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { player.command("sub-seek", "-1") }) { Text(stringResource(R.string.player_previous)) }
                    OutlinedButton(onClick = { player.command("sub-seek", "1") }) { Text(stringResource(R.string.player_next)) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = {
            val now = player.getDouble("time-pos")
            val start = player.getDouble("sub-start")
            message = if (start == null || start < 0 || now == null) {
                context.getString(R.string.sub_sync_no_line)
            } else {
                player.setDouble("sub-delay", now - start)
                context.getString(R.string.sub_sync_done)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sub_sync_this_line)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            val u = player.shareableUri
            val f = activeSub()
            val from = (player.getDouble("time-pos") ?: 0.0)
            if (u == null || f == null || f.extension.lowercase() != "srt") {
                message = context.getString(R.string.sub_auto_srt_only)
            } else {
                message = context.getString(R.string.sub_auto_busy)
                scope.launch {
                    val ms = withContext(Dispatchers.Default) { AudioSync.find(context, u, from, f) }
                    if (ms == null) {
                        message = context.getString(R.string.sub_auto_failed)
                    } else {
                        player.setDouble("sub-delay", ms / 1000.0)
                        message = context.getString(R.string.sub_auto_done, "%+.1f".format(Locale.ROOT, ms / 1000.0))
                    }
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sub_auto_sync)) }
        message?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.sub_fps_convert), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { fixFps(23.976, 25.0) }, modifier = Modifier.weight(1f)) { Text("23.976 → 25") }
            OutlinedButton(onClick = { fixFps(25.0, 23.976) }, modifier = Modifier.weight(1f)) { Text("25 → 23.976") }
        }
    }
}
