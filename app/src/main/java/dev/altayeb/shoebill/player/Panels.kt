package dev.altayeb.shoebill.player

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.widthIn
import dev.altayeb.shoebill.ui.ConnectedChoice
import dev.altayeb.shoebill.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.altayeb.shoebill.settings.Decoder
import dev.altayeb.shoebill.settings.SubStyle
import dev.altayeb.shoebill.ui.GlassScrim
import dev.altayeb.shoebill.ui.PanelHeader
import dev.altayeb.shoebill.ui.glass
import java.io.File
import java.util.Locale
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.altayeb.shoebill.player.cast.CastDialog
import dev.altayeb.shoebill.ui.theme.Radius
import java.math.BigDecimal
import java.math.RoundingMode

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
    sleepMinutes: Int?,
    onShot: () -> Unit,
    onDecoder: (Decoder) -> Unit,
    onClose: () -> Unit,
) {
    val title = when (panel) {
        Panel.Subs -> stringResource(R.string.player_subtitles)
        Panel.Audio -> stringResource(R.string.player_audio)
        Panel.Options -> stringResource(R.string.player_more)
        null -> ""
    }
    GlassScrim(visible = panel != null, onDismiss = onClose)
    // the panel sits on the end edge: slide from the right in LTR, from the left in RTL
    val side = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    AnimatedVisibility(
        visible = panel != null,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { side * it / 4 },
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + slideOutHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) { side * it / 4 },
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp).widthIn(max = 372.dp).fillMaxWidth().fillMaxHeight()
                    .glass(Radius.XL),
            ) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    PanelHeader(title, onClose)
                    when (panel) {
                        Panel.Subs -> SubsPanel(
                            player, trackVersion, onTracksChanged, onAddSub, onSearch, subStyle, onSubStyle, subFile, subsStartTab,
                        )
                        Panel.Audio -> AudioPanel(player, trackVersion, speed, volume, onTracksChanged)
                        Panel.Options -> OptionsPanel(player, abA, abB, onAbA, onAbB, onAbClear, onSleep, sleepMinutes, onShot, onDecoder)
                        null -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun trackLabel(t: Track): String {
    val name = t.title ?: t.lang?.uppercase() ?: stringResource(R.string.player_track_n, t.id)
    return if (t.codec != null) "$name · ${t.codec}" else name
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = Radius.L,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.White.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
    Spacer(Modifier.height(8.dp))
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
    SecondaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
        listOf(R.string.sub_tab_track, R.string.sub_tab_style, R.string.sub_tab_timing).forEachIndexed { i, name ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(name)) })
        }
    }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = tab,
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
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
        TrackRow(stringResource(R.string.off), none) { player.setString("sid", "no"); onTracksChanged() }
        subs.forEach { t -> TrackRow(trackLabel(t), t.selected) { player.setString("sid", t.id); onTracksChanged() } }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onSearch, modifier = Modifier.weight(1f).height(64.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Search, null)
                    Text(stringResource(R.string.search), style = MaterialTheme.typography.labelLarge)
                }
            }
            FilledTonalButton(onClick = onAddSub, modifier = Modifier.weight(1f).height(64.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.FileOpen, null)
                    Text(stringResource(R.string.sub_file), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        TextButton(onClick = { secOpen = !secOpen }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (secOpen) R.string.sub_second_open else R.string.sub_second_closed))
        }
        AnimatedVisibility(visible = secOpen) {
            Column(Modifier.fillMaxWidth()) {
                val secondary = player.getString("secondary-sid")
                TrackRow(stringResource(R.string.off), secondary.isNullOrEmpty() || secondary == "no") {
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
    SectionTitle(stringResource(R.string.player_tracks))
    if (audio.isEmpty()) Text(stringResource(R.string.player_no_audio_tracks), style = MaterialTheme.typography.bodyMedium)
    audio.forEach { t -> TrackRow(trackLabel(t), t.selected) { player.setString("aid", t.id); onTracksChanged() } }
    SectionTitle(stringResource(R.string.player_speed))
    val speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
    ConnectedChoice(speeds.map { it to speedText(it) }, speeds.firstOrNull { kotlin.math.abs(speed - it) < 0.01 }) { player.setSpeed(it) }
    Slider(value = speed.toFloat().coerceIn(0.25f, 4f), onValueChange = { player.setSpeed(Math.round(it * 20) / 20.0) }, valueRange = 0.25f..4f)
    AudioDelayAndEq(player)
    val maxVolume = dev.altayeb.shoebill.settings.AppSettings.get(androidx.compose.ui.platform.LocalContext.current).settings.value.maxVolume.toFloat()
    if (maxVolume > 100f) {
        SectionTitle(stringResource(R.string.player_volume_boost, volume.toInt().toString()))
        Slider(value = volume.toFloat().coerceIn(100f, maxVolume), onValueChange = { player.setVolume(it.toDouble()) }, valueRange = 100f..maxVolume)
    }
}

@Composable
private fun AudioDelayAndEq(player: MpvPlayer) {
    var delayS by remember { mutableStateOf(player.getDouble("audio-delay") ?: 0.0) }
    SectionTitle(stringResource(R.string.player_audio_delay, "%+.2f".format(Locale.ROOT, delayS)))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(-0.5, -0.1, 0.1, 0.5).forEach { step ->
            FilledTonalButton(onClick = { delayS += step; player.setDouble("audio-delay", delayS) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                Text((if (step > 0) "+" else "−") + "%.1f".format(Locale.ROOT, kotlin.math.abs(step)), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }
        }
        OutlinedButton(onClick = { delayS = 0.0; player.setDouble("audio-delay", 0.0) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("0", maxLines = 1) }
    }
    val fx = player.fx ?: return
    var on by remember { mutableStateOf(fx.enabled) }
    var version by remember { mutableStateOf(0) }
    SectionTitle(stringResource(R.string.player_equalizer))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { on = !on; fx.setEnabled(on) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(if (on) R.string.player_equalizer_on else R.string.off))
        }
        OutlinedButton(onClick = { fx.reset(); version++ }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.player_equalizer_reset), maxLines = 1) }
    }
    if (on) for (b in 0 until fx.bands) key(version, b) {
        var level by remember { mutableStateOf(fx.level(b).toFloat()) }
        Text("${fx.hz(b)} Hz", style = MaterialTheme.typography.bodySmall)
        Slider(value = level, onValueChange = { level = it; fx.setLevel(b, it.toInt()) },
            valueRange = fx.minLevel.toFloat()..fx.maxLevel.toFloat())
    }
}

internal fun speedText(s: Double) = BigDecimal(s).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "×"

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
    sleepMinutes: Int?,
    onShot: () -> Unit,
    onDecoder: (Decoder) -> Unit,
) {
    var hw by remember { mutableStateOf(player.getString("hwdec") ?: "") }
    var aspect by remember { mutableStateOf(player.getString("video-aspect-override") ?: "-1") }
    var crop by remember { mutableStateOf((player.getDouble("panscan") ?: 0.0) > 0.0) }
    var casting by remember { mutableStateOf(false) }
    player.shareableUri?.let { u ->
        FilledTonalButton(onClick = { casting = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cast_title)) }
        if (casting) CastDialog(u, player.shareableTitle, { player.setPaused(true) }) { casting = false }
    }
    SectionTitle(stringResource(R.string.player_decoder))
    ConnectedChoice(Decoder.values().map { it to decoderName(it) }, Decoder.values().firstOrNull { it.hwdec == hw }) { d -> hw = d.hwdec; player.setString("hwdec", d.hwdec); onDecoder(d) }
    val chapters = remember { player.chapters() }
    if (chapters.isNotEmpty()) {
        SectionTitle(stringResource(R.string.player_chapters))
        chapters.forEachIndexed { i, (title, sec) ->
            TextButton(onClick = { player.seekTo(sec) }, modifier = Modifier.fillMaxWidth()) {
                Text(title.ifEmpty { stringResource(R.string.player_chapter_n, i + 1) }, Modifier.weight(1f), maxLines = 1)
                Text("%d:%02d".format(Locale.ROOT, sec.toInt() / 60, sec.toInt() % 60))
            }
        }
    }
    SectionTitle(stringResource(R.string.player_frame_step))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Short visible labels under the "Frame by frame" title, full names for screen readers.
        val back = stringResource(R.string.player_frame_back); val next = stringResource(R.string.player_frame_next)
        OutlinedButton(onClick = { player.command("frame-back-step") }, modifier = Modifier.weight(1f).semantics { contentDescription = back }) { Text(stringResource(R.string.player_previous), maxLines = 1, softWrap = false) }
        OutlinedButton(onClick = { player.command("frame-step") }, modifier = Modifier.weight(1f).semantics { contentDescription = next }) { Text(stringResource(R.string.player_next), maxLines = 1, softWrap = false) }
    }
    SectionTitle(stringResource(R.string.player_ab_loop))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val tight = PaddingValues(horizontal = 4.dp)
        OutlinedButton(onClick = onAbA, Modifier.weight(1f), contentPadding = tight) { Text(if (abA == null) stringResource(R.string.player_ab_set_a) else "A ${fmtSec(abA)}", maxLines = 1, softWrap = false) }
        OutlinedButton(onClick = onAbB, Modifier.weight(1f), contentPadding = tight) { Text(if (abB == null) stringResource(R.string.player_ab_set_b) else "B ${fmtSec(abB)}", maxLines = 1, softWrap = false) }
        OutlinedButton(onClick = onAbClear, Modifier.weight(1f), contentPadding = tight) { Text(stringResource(R.string.clear), maxLines = 1, softWrap = false) }
    }
    SectionTitle(stringResource(R.string.player_sleep_timer))
    ConnectedChoice(listOf<Pair<Int?, String>>(null to stringResource(R.string.off)) + listOf(15, 30, 60).map { it to stringResource(R.string.player_minutes_short, it.toString()) }, sleepMinutes, pick = onSleep)
    SectionTitle(stringResource(R.string.player_aspect))
    ConnectedChoice(listOf("-1" to stringResource(R.string.player_aspect_auto), "16:9" to "16:9", "4:3" to "4:3", "2.35:1" to "2.35"), aspect) { v -> aspect = v; player.setString("video-aspect-override", v) }
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(onClick = {
        crop = !crop
        player.setDouble("panscan", if (crop) 1.0 else 0.0)
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (crop) R.string.player_crop_on else R.string.player_crop_off)) }
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(onClick = onShot, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.player_screenshot)) }
}

private fun fmtSec(s: Double) = "%.1f".format(Locale.ROOT, s)

fun decoderName(d: Decoder) = when (d) {
    Decoder.HW -> "HW"
    Decoder.HWPlus -> "HW+"
    Decoder.SW -> "SW"
}
