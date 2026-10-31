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
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The logo, alive: breathes, glances right then left, blinks. Drawn in code from [LogoPaths] so it takes the
 * theme colour; the eye is cut out, so any background shows through.
 */
@Composable
fun BrandLoader(modifier: Modifier = Modifier, size: Dp = 56.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val body = remember { PathParser().parsePathString(LogoPaths.BODY).toPath().apply { fillType = PathFillType.EvenOdd } }
    val eye = remember { PathParser().parsePathString(LogoPaths.EYE).toPath() }
    val t = rememberInfiniteTransition(label = "shoebill-loader")
    val breathe by t.animateFloat(1f, 0.9f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val glance by t.animateFloat(
        0f, 0f,
        infiniteRepeatable(keyframes {
            durationMillis = 1600
            0f at 250 using LinearEasing
            3f at 470 using FastOutSlowInEasing
            -2f at 960 using FastOutSlowInEasing
            0f at 1160
        }),
        label = "glance",
    )
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 1600
            1f at 1340
            0.1f at 1410
            1f at 1520
        }),
        label = "blink",
    )
    val loading = stringResource(R.string.ui_loading)
    Canvas(
        modifier.size(size)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen) // so BlendMode.Clear cuts the eyes only
            .semantics { contentDescription = loading },
    ) {
        val k = this.size.minDimension / LogoPaths.SIZE
        scale(k * breathe, k * breathe, pivot = Offset.Zero) {
            // keep the mark centred while it breathes
            val shift = LogoPaths.SIZE * (1 - breathe) / 2 / breathe
            translate(shift, shift) {
                drawPath(body, color)
                translate(glance, 0f) {
                    scale(1f, blink, pivot = Offset(LogoPaths.EYE_X, LogoPaths.EYE_Y)) { drawPath(eye, Color.Black, blendMode = BlendMode.Clear) }
                }
            }
        }
    }
}
