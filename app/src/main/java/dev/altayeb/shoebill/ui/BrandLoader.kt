package dev.altayeb.shoebill.ui

import dev.altayeb.shoebill.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The mark filling up from the bottom and draining again, tinted from the theme.
 * Runs in the draw phase only: one path, two draws, no recomposition and no extra layer, so it stays cheap inside busy screens.
 */
@Composable
fun BrandLoader(modifier: Modifier = Modifier, size: Dp = 56.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val body = remember {
        PathParser().parsePathString(LogoPaths.BODY).toPath().apply {
            fillType = PathFillType.EvenOdd
            transform(androidx.compose.ui.graphics.Matrix().apply { translate(0f, LogoPaths.SIZE); scale(0.1f, -0.1f) })
        }
    }
    val fill = rememberInfiniteTransition(label = "shoebill-loader")
        .animateFloat(0.12f, 0.92f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fill")
    val loading = stringResource(R.string.ui_loading)
    Canvas(modifier.size(size).semantics { contentDescription = loading }) {
        val k = this.size.minDimension / LogoPaths.SIZE
        scale(k, k, pivot = Offset.Zero) {
            drawPath(body, color.copy(alpha = 0.22f))
            clipRect(top = LogoPaths.SIZE * (1f - fill.value)) { drawPath(body, color) }
        }
    }
}
