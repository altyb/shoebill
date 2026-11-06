@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.altayeb.shoebill.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi

/**
 * A row of equal-width options joined into one control. Use this for any "pick one of a few" row:
 * equal widths and one-line labels mean a button can never squeeze its text into wrapping.
 */
@Composable
fun <T> ConnectedChoice(options: List<Pair<T, String>>, selected: T?, modifier: Modifier = Modifier, unchecked: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified, pick: (T) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (v, label) ->
            ToggleButton(
                checked = v == selected,
                onCheckedChange = { pick(v) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = if (unchecked.isSpecified) androidx.compose.material3.ToggleButtonDefaults.colors(containerColor = unchecked) else androidx.compose.material3.ToggleButtonDefaults.colors(),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                // Shrinks rather than clips, for long translations and large system text.
                Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium, autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize))
            }
        }
    }
}
