package dev.altayeb.shoebill.player

import dev.altayeb.shoebill.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.altayeb.shoebill.settings.SubStyle
import java.util.Locale
import kotlin.math.roundToInt

fun applySubStyle(player: MpvPlayer, s: SubStyle) {
    player.setDouble("sub-scale", s.scale.toDouble())
    player.setString("sub-color", argb(s.color))
    player.setString("sub-pos", s.pos.toString())
    player.setString("sub-bold", if (s.bold) "yes" else "no")
    player.setString("sub-ass-override", if (s.force) "force" else "scale")
    // One box around all lines (background-box: colour = sub-back-color, padding = shadow offset), or a box per line (opaque-box).
    player.setString("sub-border-style", if (!s.box) "outline-and-shadow" else if (s.boxLines) "opaque-box" else "background-box")
    val boxColor = "#%02X000000".format(Locale.ROOT, (s.boxAlpha * 255).roundToInt())
    // mpv renamed these across versions; the name a build lacks throws and is skipped
    val outlineSize = if (s.box) (if (s.boxLines) 4.0 else 0.0) else s.outline.toDouble()
    for (n in listOf("sub-outline-size", "sub-border-size")) runCatching { player.setDouble(n, outlineSize) }
    for (n in listOf("sub-outline-color", "sub-border-color")) runCatching { player.setString(n, if (s.box && s.boxLines) boxColor else argb(s.outlineColor)) }
    runCatching { player.setString("sub-back-color", if (s.box) boxColor else "#80000000") }
    runCatching { player.setDouble("sub-shadow-offset", if (s.box && !s.boxLines) 6.0 else 0.0) }
    runCatching { player.setString("sub-font", s.font.ifEmpty { "sans-serif" }) }
}

private fun argb(c: Long) = "#%08X".format(Locale.ROOT, c)

val SubPresets = listOf(
    R.string.sub_preset_default to SubStyle(),
    R.string.sub_preset_yellow to SubStyle(color = 0xFFFFE14D),
    R.string.sub_preset_box to SubStyle(box = true),
    R.string.sub_preset_large to SubStyle(scale = 1.4f, outline = 3f),
)

private val SubColors = listOf(0xFFFFFFFF, 0xFFFFE14D, 0xFF4DE8FF, 0xFF7CFF6B, 0xFFFF7AC8, 0xFFFFA64D)

@Composable
fun SubStyleTab(style: SubStyle, onChange: (SubStyle) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubPresets.forEach { (name, preset) ->
                FilterChip(selected = style.copy(pos = preset.pos) == preset, onClick = { onChange(preset.copy(pos = style.pos)) }, label = { Text(stringResource(name)) })
            }
        }
        SectionTitle(stringResource(R.string.sub_color))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SubColors.forEach { c ->
                Box(
                    Modifier.size(36.dp).background(Color(c), CircleShape)
                        .border(2.dp, if (style.color == c) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                        .clickable { onChange(style.copy(color = c)) },
                )
            }
        }
        SectionTitle(stringResource(R.string.sub_size, (style.scale * 100).roundToInt().toString()))
        Slider(value = style.scale, onValueChange = { onChange(style.copy(scale = it)) }, valueRange = 0.6f..2f)
        SectionTitle(stringResource(R.string.sub_outline, style.outline.roundToInt().toString()))
        Slider(value = style.outline, onValueChange = { onChange(style.copy(outline = it)) }, valueRange = 0f..6f, enabled = !style.box)
        SwitchRow(stringResource(R.string.sub_background), style.box) { onChange(style.copy(box = it)) }
        if (style.box) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!style.boxLines, { onChange(style.copy(boxLines = false)) }, { Text(stringResource(R.string.sub_box_one)) })
                FilterChip(style.boxLines, { onChange(style.copy(boxLines = true)) }, { Text(stringResource(R.string.sub_box_lines)) })
            }
            SectionTitle(stringResource(R.string.sub_background_opacity, (style.boxAlpha * 100).roundToInt().toString()))
            Slider(value = style.boxAlpha, onValueChange = { onChange(style.copy(boxAlpha = it)) }, valueRange = 0.2f..1f)
        }
        SectionTitle(stringResource(R.string.sub_position, style.pos.toString()))
        Slider(value = style.pos.toFloat(), onValueChange = { onChange(style.copy(pos = it.roundToInt())) }, valueRange = 0f..100f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.sub_top), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.sub_bottom), style = MaterialTheme.typography.labelMedium)
        }
        SectionTitle(stringResource(R.string.sub_font))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to R.string.sub_font_default, "Noto Naskh Arabic" to R.string.sub_font_naskh, "Noto Sans Arabic" to R.string.sub_font_sans, "serif" to R.string.sub_font_serif).forEach { (f, name) ->
                FilterChip(style.font == f, { onChange(style.copy(font = f)) }, { Text(stringResource(name)) })
            }
        }
        SwitchRow(stringResource(R.string.sub_bold), style.bold) { onChange(style.copy(bold = it)) }
        SwitchRow(stringResource(R.string.sub_force_style), style.force) { onChange(style.copy(force = it)) }
        Text(
            stringResource(R.string.sub_force_style_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
