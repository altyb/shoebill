package dev.altayeb.shoebill.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Set by MainActivity around the route switcher; null in previews and sheets, where sharing is skipped. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalRouteScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** The thumbnail of [uri] and the player of [uri] share bounds: opening grows the thumbnail into the player. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedVideo(uri: Uri): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val route = LocalRouteScope.current ?: return this
    return with(shared) {
        this@sharedVideo.sharedBounds(
            rememberSharedContentState("video:$uri"),
            route,
            boundsTransform = { _, _ -> tween(380, easing = Emphasized) },
        )
    }
}
