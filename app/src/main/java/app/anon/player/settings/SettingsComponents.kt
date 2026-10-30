package app.anon.player.settings

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
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
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
