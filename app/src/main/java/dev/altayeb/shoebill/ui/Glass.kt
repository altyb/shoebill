package dev.altayeb.shoebill.ui

import dev.altayeb.shoebill.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import dev.altayeb.shoebill.ui.theme.Radius

// ponytail: translucent tint + hairline border, not a real backdrop blur. Blur can't sample the mpv SurfaceView;
// add Haze/RenderEffect only for screens without video behind.
/** Frosted-glass surface: translucent tinted gradient, 1 dp light edge. */
@Composable
fun Modifier.glass(shape: Shape = Radius.XL): Modifier {
    val c = MaterialTheme.colorScheme.surfaceContainer
    return clip(shape)
        .background(Brush.verticalGradient(listOf(c.copy(alpha = 0.90f), c.copy(alpha = 0.80f))))
        .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
}

/** Dimmed backdrop behind a panel; fades in/out, a tap anywhere on it closes the panel. */
@Composable
fun GlassScrim(visible: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
}

/** Panel title row with a visible close button. */
@Composable
fun PanelHeader(title: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        actions()
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
    }
}

/** Rounded pill search field: search icon, placeholder, clear button once there is text. */
@Composable
fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(52.dp).clip(CircleShape).background(cs.surfaceContainerHighest).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            )
        }
        AnimatedVisibility(value.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut()) {
            IconButton(onClick = { onValueChange("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.clear), tint = cs.onSurfaceVariant) }
        }
    }
}

/** Centered empty state: tonal icon disc, title, optional body and action. Fades and scales in. */
@Composable
fun EmptyState(art: Int, title: String, modifier: Modifier = Modifier, body: String? = null, action: @Composable () -> Unit = {}) =
    EmptyState(title, modifier, body, action) {
        // Brand art is one flat shape, tinted from the theme so it works in every colour scheme.
        Image(androidx.compose.ui.res.painterResource(art), null, Modifier.size(160.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
    }

@Composable
fun EmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier, body: String? = null, action: @Composable () -> Unit = {}) =
    EmptyState(title, modifier, body, action) {
        val cs = MaterialTheme.colorScheme
        Box(Modifier.size(96.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(44.dp), tint = cs.onSecondaryContainer)
        }
    }

@Composable
private fun EmptyState(title: String, modifier: Modifier, body: String?, action: @Composable () -> Unit, graphic: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, modifier, enter = fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.92f)) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            graphic()
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            body?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}
