package app.anon.player.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.anon.player.settings.Decoder
import app.anon.player.settings.SubStyle
import app.anon.player.ui.GlassScrim
import app.anon.player.ui.PanelHeader
import app.anon.player.ui.glass
import java.io.File
import java.util.Locale

/** Side sheet, 372 dp, 28 dp corners, over the video, which keeps playing (ق-07). */
@Composable
fun SidePanel(
    panel: Panel?,
    player: MpvPlayer,
    trackVersion: Int,
    speed: Double,
    volume: Double,
    abA: Double?,
    abB: Double?,
    onTracksChanged: () -> Unit,
    onAddSub: () -> Unit,
    onSearch: () -> Unit,
    subStyle: SubStyle,
    onSubStyle: (SubStyle) -> Unit,
    subFile: File?,
    subsStartTab: Int,
    onAbA: () -> Unit,
    onAbB: () -> Unit,
    onAbClear: () -> Unit,
    onSleep: (Int?) -> Unit,
    onShot: () -> Unit,
    onDecoder: (Decoder) -> Unit,
    onClose: () -> Unit,
) {
    val title = when (panel) {
        Panel.Subs -> "الترجمة"
        Panel.Audio -> "الصوت"
        Panel.Options -> "المزيد"
        null -> ""
    }
    GlassScrim(visible = panel != null, onDismiss = onClose)
    AnimatedVisibility(
        visible = panel != null,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 4 },
        exit = fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 4 },
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp).width(372.dp).fillMaxHeight()
                    .glass(RoundedCornerShape(28.dp)),
            ) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    PanelHeader(title, onClose)
                    when (panel) {
                        Panel.Subs -> SubsPanel(
                            player, trackVersion, onTracksChanged, onAddSub, onSearch, subStyle, onSubStyle, subFile, subsStartTab,
                        )
                        Panel.Audio -> AudioPanel(player, trackVersion, speed, volume, onTracksChanged)
                        Panel.Options -> OptionsPanel(player, abA, abB, onAbA, onAbB, onAbClear, onSleep, onShot, onDecoder)
                        null -> {}
                    }
                }
            }
        }
    }
}

private fun trackLabel(t: Track): String {
    val name = t.title ?: t.lang?.uppercase() ?: "مسار ${t.id}"
    return if (t.codec != null) "$name · ${t.codec}" else name
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.White.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (selected) Icon(Icons.Rounded.Check, null)
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SubsPanel(
    player: MpvPlayer,
    trackVersion: Int,
    onTracksChanged: () -> Unit,
    onAddSub: () -> Unit,
    onSearch: () -> Unit,
    style: SubStyle,
    onStyle: (SubStyle) -> Unit,
    subFile: File?,
    startTab: Int,
) {
    var tab by remember { mutableStateOf(startTab) }
    SecondaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
        listOf("المسار", "الشكل", "التوقيت").forEachIndexed { i, name ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) })
        }
    }
    AnimatedContent(
        targetState = tab,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label = "subsTab",
    ) { t ->
        when (t) {
            0 -> TrackTab(player, trackVersion, onTracksChanged, onAddSub, onSearch)
            1 -> SubStyleTab(style, onStyle)
            else -> SubTimingTab(player, subFile)
        }
    }
}

@Composable
private fun TrackTab(
    player: MpvPlayer,
    trackVersion: Int,
    onTracksChanged: () -> Unit,
    onAddSub: () -> Unit,
    onSearch: () -> Unit,
) {
    val subs = remember(trackVersion) { player.tracks().filter { it.type == "sub" } }
    val none = subs.none { it.selected }
    var secOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TrackRow("إيقاف", none) { player.setString("sid", "no"); onTracksChanged() }
        subs.forEach { t -> TrackRow(trackLabel(t), t.selected) { player.setString("sid", t.id); onTracksChanged() } }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = onSearch, modifier = Modifier.weight(1f).height(64.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Search, null)
                    Text("بحث", style = MaterialTheme.typography.labelLarge)
                }
            }
            FilledTonalButton(onClick = onAddSub, modifier = Modifier.weight(1f).height(64.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.FileOpen, null)
                    Text("ملف", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        TextButton(onClick = { secOpen = !secOpen }, modifier = Modifier.fillMaxWidth()) {
            Text(if (secOpen) "ترجمة ثانية ▴" else "ترجمة ثانية ▾")
        }
        AnimatedVisibility(visible = secOpen) {
            Column(Modifier.fillMaxWidth()) {
                val secondary = player.getString("secondary-sid")
                TrackRow("إيقاف", secondary.isNullOrEmpty() || secondary == "no") {
                    player.setString("secondary-sid", "no"); onTracksChanged()
                }
                val primary = player.getString("sid")
                subs.filter { it.id != primary }.forEach { t ->
                    TrackRow(trackLabel(t), secondary == t.id) {
                        // top of the frame, so it does not stack on the primary line
                        player.setString("secondary-sub-pos", "0")
                        player.setString("secondary-sid", t.id)
                        onTracksChanged()
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPanel(player: MpvPlayer, trackVersion: Int, speed: Double, volume: Double, onTracksChanged: () -> Unit) {
    val audio = remember(trackVersion) { player.tracks().filter { it.type == "audio" } }
    SectionTitle("المسارات")
    if (audio.isEmpty()) Text("لا مسارات صوت", style = MaterialTheme.typography.bodyMedium)
    audio.forEach { t -> TrackRow(trackLabel(t), t.selected) { player.setString("aid", t.id); onTracksChanged() } }
    SectionTitle("السرعة")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { s ->
            val on = kotlin.math.abs(speed - s) < 0.01
            if (on) {
                androidx.compose.material3.Button(onClick = { player.setSpeed(s) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp, 0.dp)) { Text(speedText(s)) }
            } else {
                FilledTonalButton(onClick = { player.setSpeed(s) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp, 0.dp)) { Text(speedText(s)) }
            }
        }
    }
    Slider(value = speed.toFloat().coerceIn(0.25f, 4f), onValueChange = { player.setSpeed(Math.round(it * 20) / 20.0) }, valueRange = 0.25f..4f)
    SectionTitle("تضخيم الصوت \u2066${(volume).toInt()}%\u2069")
    Slider(value = volume.toFloat().coerceIn(100f, 200f), onValueChange = { player.setVolume(it.toDouble()) }, valueRange = 100f..200f)
}

internal fun speedText(s: Double) = java.math.BigDecimal(s).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "×"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptionsPanel(
    player: MpvPlayer,
    abA: Double?,
    abB: Double?,
    onAbA: () -> Unit,
    onAbB: () -> Unit,
    onAbClear: () -> Unit,
    onSleep: (Int?) -> Unit,
    onShot: () -> Unit,
    onDecoder: (Decoder) -> Unit,
) {
    var hw by remember { mutableStateOf(player.getString("hwdec") ?: "") }
    var aspect by remember { mutableStateOf(player.getString("video-aspect-override") ?: "-1") }
    var crop by remember { mutableStateOf((player.getDouble("panscan") ?: 0.0) > 0.0) }
    SectionTitle("فك الترميز")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Decoder.values().forEach { d ->
            val on = hw == d.hwdec
            val onClick = { hw = d.hwdec; player.setString("hwdec", d.hwdec); onDecoder(d) }
            if (on) androidx.compose.material3.Button(onClick = onClick) { Text(decoderName(d)) }
            else FilledTonalButton(onClick = onClick) { Text(decoderName(d)) }
        }
    }
    SectionTitle("تكرار A-B")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onAbA) { Text(if (abA == null) "A هنا" else "A ${fmtSec(abA)}") }
        OutlinedButton(onClick = onAbB) { Text(if (abB == null) "B هنا" else "B ${fmtSec(abB)}") }
        OutlinedButton(onClick = onAbClear) { Text("مسح") }
    }
    SectionTitle("مؤقت النوم")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(15, 30, 60).forEach { m -> OutlinedButton(onClick = { onSleep(m) }) { Text("$m د") } }
        OutlinedButton(onClick = { onSleep(null) }) { Text("إيقاف") }
    }
    SectionTitle("نسبة العرض")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("-1" to "تلقائي", "16:9" to "16:9", "4:3" to "4:3", "2.35:1" to "2.35").forEach { (v, label) ->
            val onClick = { aspect = v; player.setString("video-aspect-override", v) }
            if (aspect == v) androidx.compose.material3.Button(onClick = onClick) { Text(label) }
            else FilledTonalButton(onClick = onClick) { Text(label) }
        }
    }
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(onClick = {
        crop = !crop
        player.setDouble("panscan", if (crop) 1.0 else 0.0)
    }, modifier = Modifier.fillMaxWidth()) { Text(if (crop) "قص الإطار: مفعّل" else "قص الإطار: معطّل") }
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(onClick = onShot, modifier = Modifier.fillMaxWidth()) { Text("لقطة شاشة") }
}

private fun fmtSec(s: Double) = "%.1f".format(Locale.ROOT, s)

fun decoderName(d: Decoder) = when (d) {
    Decoder.HW -> "HW"
    Decoder.HWPlus -> "HW+"
    Decoder.SW -> "SW"
}
