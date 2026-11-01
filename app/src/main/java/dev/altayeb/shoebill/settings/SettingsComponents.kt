package dev.altayeb.shoebill.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import dev.altayeb.shoebill.ui.theme.Radius

/** Android 16 grouped list: 24 dp outer corners, 4 dp inner corners. */
fun groupShapes(count: Int): List<Shape> = List(count) { i ->
    RoundedCornerShape(
        topStart = if (i == 0) 24.dp else 4.dp, topEnd = if (i == 0) 24.dp else 4.dp,
        bottomStart = if (i == count - 1) 24.dp else 4.dp, bottomEnd = if (i == count - 1) 24.dp else 4.dp,
    )
}

@Composable
fun GroupTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun SettingRow(
    title: String,
    shape: Shape,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val body: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    if (onClick != null) {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = shape, color = color) { body() }
    } else {
        Surface(modifier = Modifier.fillMaxWidth(), shape = shape, color = color) { body() }
    }
}

@Composable
fun SwitchRow(title: String, shape: Shape, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    SettingRow(
        title = title, shape = shape, subtitle = subtitle, onClick = { onChange(!checked) },
        trailing = {
            Switch(
                checked = checked, onCheckedChange = null,
                thumbContent = { if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) },
            )
        },
    )
}

/** A setting with a few fixed answers, shown as chips under its title. */
@Composable
fun <T> ChoiceRow(title: String, shape: Shape, options: List<Pair<T, String>>, selected: T, pick: (T) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.padding(top = 8.dp))
            // Few short answers: one joined control. Long ones: chips that wrap, so none hides off the edge.
            if (options.size <= 4 && options.all { it.second.length <= 9 }) dev.altayeb.shoebill.ui.ConnectedChoice(options, selected, pick = pick)
            else @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                options.forEach { (v, label) -> androidx.compose.material3.FilterChip(v == selected, { pick(v) }, { Text(label) }) }
            }
        }
    }
}

/** One part of the app on the settings hub. Each part keeps its own colour role, so the list reads at a glance. */
@Composable
fun HubRow(icon: ImageVector, container: Color, content: Color, title: String, subtitle: String, shape: Shape, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(44.dp), shape = Radius.M, color = container, contentColor = content) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

/** A short, friendly hint at the top of a settings page, with the bird that belongs to it. */
@Composable
fun TipCard(art: Int, text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = Radius.L, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(androidx.compose.ui.res.painterResource(art), null, Modifier.size(56.dp), colorFilter = ColorFilter.tint(cs.onSecondaryContainer))
            Spacer(Modifier.width(16.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
