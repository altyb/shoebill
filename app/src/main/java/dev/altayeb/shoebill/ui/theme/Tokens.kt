package dev.altayeb.shoebill.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The whole corner scale. Use these, never a new number. Spacing stays on the 4 dp grid (design/brand/README.md). */
object Radius {
    val XS = RoundedCornerShape(8.dp)
    val S = RoundedCornerShape(12.dp)
    val M = RoundedCornerShape(16.dp)
    val L = RoundedCornerShape(24.dp)
    val XL = RoundedCornerShape(28.dp)
}

val AppShapes = Shapes(extraSmall = Radius.XS, small = Radius.S, medium = Radius.M, large = Radius.L, extraLarge = Radius.XL)

/** The player is always dark and sits on any picture, so its colours are fixed on purpose. */
object PlayerColors {
    val On = Color.White
    val Surface = Color.Black.copy(alpha = 0.62f)
}
