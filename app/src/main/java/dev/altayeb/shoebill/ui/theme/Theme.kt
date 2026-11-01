package dev.altayeb.shoebill.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.altayeb.shoebill.settings.AppSettings
import dev.altayeb.shoebill.settings.ColorSource
import dev.altayeb.shoebill.settings.Contrast
import dev.altayeb.shoebill.settings.DarkMode
import dev.altayeb.shoebill.settings.SchemeStyle
import dev.altayeb.shoebill.settings.Settings
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

// From design/scheme.json "amber" (not an M3 role; used by app code directly).
val BrandAmber = Color(0xFFE0AE27)

/** Seed used when the wallpaper source is asked for on Android < 12 (design ش-18). */
const val BRAND_SEED = 0xFF2F6F8F.toInt() // the logo's slate blue, so app and logo read as one

data class Preset(val name: String, val seed: Int)

/** The 12 ready palettes and the ش-18 spec. */
val BrandPresets = listOf(
    Preset("Shoebill", BRAND_SEED),
    Preset("Ocean", 0xFF1E5BC6.toInt()),
    Preset("Indigo", 0xFF4C4FB8.toInt()),
    Preset("Violet", 0xFF7A4BC4.toInt()),
    Preset("Rose", 0xFFC2185B.toInt()),
    Preset("Ruby", 0xFFC62828.toInt()),
    Preset("Ember", 0xFFE65100.toInt()),
    Preset("Amber", 0xFFC99400.toInt()),
    Preset("Forest", 0xFF2E7D32.toInt()),
    Preset("Lime", 0xFF7C9A1E.toInt()),
    Preset("Slate", 0xFF546E7A.toInt()),
    Preset("Mono", 0xFF777777.toInt()),
)

internal fun SchemeStyle.palette(): PaletteStyle = when (this) {
    SchemeStyle.TonalSpot -> PaletteStyle.TonalSpot
    SchemeStyle.Vibrant -> PaletteStyle.Vibrant
    SchemeStyle.Expressive -> PaletteStyle.Expressive
    SchemeStyle.Fidelity -> PaletteStyle.Fidelity
    SchemeStyle.Monochrome -> PaletteStyle.Monochrome
}

internal val Contrast.level: Double
    get() = when (this) {
        Contrast.Standard -> 0.0
        Contrast.Medium -> 0.5
        Contrast.High -> 1.0
    }

fun Settings.isDark(systemDark: Boolean): Boolean = when (darkMode) {
    DarkMode.System -> systemDark
    DarkMode.Light -> false
    DarkMode.Dark -> true
}

/** Material-kolor scheme for one seed. Also used by the preset circles in ش-18. */
fun seedScheme(seed: Int, dark: Boolean, amoled: Boolean, style: SchemeStyle, contrast: Contrast): ColorScheme =
    dynamicColorScheme(Color(seed), dark, amoled, style = style.palette(), contrastLevel = contrast.level)

/** The scheme for the current settings. Wallpaper colors on API 31+, otherwise the seed path. */
fun buildScheme(context: Context, s: Settings, dark: Boolean): ColorScheme {
    val amoled = dark && s.amoled
    if (s.colorSource == ColorSource.Wallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return if (amoled) {
            base.copy(background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black)
        } else base
    }
    val seed = if (s.colorSource == ColorSource.Wallpaper) BRAND_SEED else s.seed
    return seedScheme(seed, dark, amoled, s.style, s.contrast)
}

@Composable
private fun animatedRole(target: Color): Color = animateColorAsState(target, tween(400)).value

/** Animates the main roles so a change of color source/seed/mode fades instead of jumping. */
@Composable
private fun ColorScheme.animated(): ColorScheme = copy(
    primary = animatedRole(primary), onPrimary = animatedRole(onPrimary),
    primaryContainer = animatedRole(primaryContainer), onPrimaryContainer = animatedRole(onPrimaryContainer),
    secondary = animatedRole(secondary), onSecondary = animatedRole(onSecondary),
    secondaryContainer = animatedRole(secondaryContainer), onSecondaryContainer = animatedRole(onSecondaryContainer),
    tertiary = animatedRole(tertiary), onTertiary = animatedRole(onTertiary),
    tertiaryContainer = animatedRole(tertiaryContainer), onTertiaryContainer = animatedRole(onTertiaryContainer),
    background = animatedRole(background), onBackground = animatedRole(onBackground),
    surface = animatedRole(surface), onSurface = animatedRole(onSurface),
    surfaceVariant = animatedRole(surfaceVariant), onSurfaceVariant = animatedRole(onSurfaceVariant),
    surfaceContainer = animatedRole(surfaceContainer), surfaceContainerHigh = animatedRole(surfaceContainerHigh),
    surfaceContainerLow = animatedRole(surfaceContainerLow), outline = animatedRole(outline),
)

/** App theme: reads AppSettings reactively, so changes apply at once without a restart. */
@Composable
fun BrandTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val s by AppSettings.get(context).settings.collectAsState()
    val dark = s.isDark(isSystemInDarkTheme())
    val scheme = remember(s, dark) { buildScheme(context, s, dark) }
    Themed(scheme.animated(), content)
}

/** The player is always dark (ق-18), with the same seed and style as the app. */
@Composable
fun ShoebillTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val s by AppSettings.get(context).settings.collectAsState()
    val scheme = remember(s) { buildScheme(context, s, dark = true) }
    Themed(scheme.animated(), content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Themed(scheme: ColorScheme, content: @Composable () -> Unit) =
    MaterialExpressiveTheme(colorScheme = scheme, typography = Typography(), shapes = AppShapes, content = content)
