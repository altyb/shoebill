package app.anon.player.player

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
import app.anon.player.subs.SrtParser
import app.anon.player.ui.PanelHeader
import app.anon.player.ui.glass
import app.anon.player.subs.scaleFps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun SubTimingTab(player: MpvPlayer, subFile: File?) {
    val scope = rememberCoroutineScope()
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

    fun nudge(step: Double) {
        player.setDouble("sub-delay", (player.getDouble("sub-delay") ?: 0.0) + step)
        delaySec = player.getDouble("sub-delay") ?: 0.0
    }

    fun fixFps(from: Double, to: Double) {
        val f = subFile
        if (f == null || f.extension.lowercase() != "srt") {
            message = "تحويل الإطارات يعمل على ملف SRT خارجي فقط"
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                f.writeText(SrtParser.write(SrtParser.parse(f.readText()).scaleFps(from, to)), Charsets.UTF_8)
            }
            player.command("sub-reload")
            message = "أُعيد حساب التوقيت"
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%+.2f ث".format(Locale.ROOT, delaySec), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { player.setDouble("sub-delay", 0.0); delaySec = 0.0 }) { Text("صفر") }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-0.5, -0.1, 0.1, 0.5).forEach { step ->
                FilledTonalButton(onClick = { nudge(step) }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text((if (step > 0) "+" else "−") + "%.1f".format(Locale.ROOT, kotlin.math.abs(step)))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cue.ifBlank { "لا ترجمة ظاهرة الآن" }, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { player.command("sub-seek", "-1") }) { Text("السابق") }
                    OutlinedButton(onClick = { player.command("sub-seek", "1") }) { Text("التالي") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = {
            // ponytail: manual tap-sync only. Auto-sync from the audio waveform is not built.
            val now = player.getDouble("time-pos")
            val start = player.getDouble("sub-start")
            message = if (start == null || start < 0 || now == null) {
                "لا سطر ظاهر الآن. انتظر حتى يظهر السطر ثم انقر"
            } else {
                player.setDouble("sub-delay", now - start)
                "ضُبط التأخير على هذا السطر"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("هذا السطر الآن") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("مزامنة تلقائية: قريباً") }

        Spacer(Modifier.height(16.dp))
        Text("تحويل الإطارات", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { fixFps(23.976, 25.0) }, modifier = Modifier.weight(1f)) { Text("23.976 → 25") }
            OutlinedButton(onClick = { fixFps(25.0, 23.976) }, modifier = Modifier.weight(1f)) { Text("25 → 23.976") }
        }
        message?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
