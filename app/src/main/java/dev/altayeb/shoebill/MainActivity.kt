package dev.altayeb.shoebill

import dev.altayeb.shoebill.player.PipGate
import dev.altayeb.shoebill.ui.LocalRouteScope
import dev.altayeb.shoebill.ui.LocalSharedScope
import androidx.compose.animation.SharedTransitionLayout
import android.view.ViewTreeObserver
import android.view.View
import android.os.SystemClock
import android.os.Build
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.altayeb.shoebill.library.FolderScreen
import dev.altayeb.shoebill.library.LibraryHome
import dev.altayeb.shoebill.library.SearchScreen
import dev.altayeb.shoebill.player.PlayerScreen
import dev.altayeb.shoebill.settings.AppearanceScreen
import dev.altayeb.shoebill.settings.SettingsScreen
import dev.altayeb.shoebill.ui.theme.ShoebillTheme
import dev.altayeb.shoebill.vault.VaultScreen
import dev.altayeb.shoebill.ui.theme.BrandTheme

/** Every screen of the app. Only MainActivity pushes/pops routes. */
sealed interface Route {
    data object Home : Route
    data class Folder(val name: String) : Route
    data object Search : Route
    data class Player(val uri: Uri, val title: String, val private: Boolean = false, val queue: List<Pair<Uri, String>>? = null) : Route
    data object Settings : Route
    data object Appearance : Route
    data object Vault : Route
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
class MainActivity : FragmentActivity() { // FragmentActivity: BiometricPrompt (vault) needs it
    // Wrapper so each VIEW intent is a new value, even for the same uri.
    private class Incoming(val uri: Uri)
    private val incoming = mutableStateOf<Incoming?>(null)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Lang.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Leaves with a quick grow-and-fade instead of the system's plain cut.
            splashScreen.setOnExitAnimationListener { v ->
                v.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setDuration(220).withEndAction { v.remove() }.start()
            }
        } else {
            window.setBackgroundDrawableResource(android.R.color.black) // the splash picture must not sit behind the app
        }
        takeViewIntent(intent)
        // Android 12+: keep the splash icon up briefly on a normal launch;
        // opening a video from another app skips it
        if (Build.VERSION.SDK_INT >= 31 && savedInstanceState == null && intent?.action != Intent.ACTION_VIEW) {
            val until = SystemClock.uptimeMillis() + 300
            val content = findViewById<View>(android.R.id.content)
            content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (SystemClock.uptimeMillis() < until) return false.also { content.postInvalidateDelayed(50) }
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            })
        }
        setContent {
            // layout direction comes from the locale (Lang): RTL in Arabic, LTR in English; times/seek bars stay LTR
            BrandTheme {
                Surface(Modifier, color = MaterialTheme.colorScheme.background) {
                    // debug/test entry: adb shell am start -n <pkg>/dev.altayeb.shoebill.MainActivity --es route settings|appearance|search|vault
                    val start = remember { startRoute(routeOf(intent)) }
                    val stack = remember { mutableStateListOf<Route>(Route.Home).apply { start?.let(::add) } }
                    val push: (Route) -> Unit = { stack.add(it) }
                    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
                    val play: (Uri, String) -> Unit = { u, t -> push(Route.Player(u, t)) }
                    val inc = incoming.value
                    LaunchedEffect(inc) { inc?.let { push(Route.Player(it.uri, displayName(it.uri))) } }
                    val req = routeRequest.value
                    LaunchedEffect(req) { req?.let { if (stack.last() != it.route) push(it.route) } }
                    BackHandler(stack.size > 1) { pop() }

                    // M3 shared-axis X between pages (forward from the end side, back to it); the player fades + scales in.
                    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                    SharedTransitionLayout { CompositionLocalProvider(LocalSharedScope provides this) {
                    AnimatedContent(
                        targetState = stack.size to stack.last(),
                        transitionSpec = {
                            val forward = targetState.first >= initialState.first
                            val player = targetState.second is Route.Player || initialState.second is Route.Player
                            val dur = 320
                            val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f) // emphasized
                            if (player) {
                                // the tapped thumbnail grows into the player (ui/Shared.kt); the pages only cross-fade under it
                                fadeIn(tween(dur, easing = ease)) togetherWith fadeOut(tween(dur, delayMillis = 120))
                            } else {
                                val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1) // the next page comes from the end side (left in RTL)
                                (slideInHorizontally(tween(dur, easing = ease)) { sign * it / 6 } + fadeIn(tween(dur, delayMillis = dur / 4))) togetherWith
                                    (slideOutHorizontally(tween(dur, easing = ease)) { -sign * it / 6 } + fadeOut(tween(dur / 3)))
                            }
                        },
                        contentKey = { it.second },
                        label = "route",
                    ) { (_, r) -> CompositionLocalProvider(LocalRouteScope provides this) {
                    when (r) {
                        Route.Home -> LibraryHome(
                            onPlay = play, onOpenFolder = { push(Route.Folder(it)) }, onSearch = { push(Route.Search) },
                            onSettings = { push(Route.Settings) }, onVault = { push(Route.Vault) },
                            onPlayQueue = { q, i -> push(Route.Player(q[i].first, q[i].second, queue = q)) },
                        )
                        is Route.Folder -> FolderScreen(r.name, onBack = pop, onPlay = play)
                        Route.Search -> SearchScreen(onBack = pop, onPlay = play)
                        is Route.Player -> key(r) { ShoebillTheme { PlayerScreen(uri = r.uri, title = r.title, onBack = pop, private = r.private, queue = r.queue) } }
                        Route.Settings -> SettingsScreen(onBack = pop, onAppearance = { push(Route.Appearance) })
                        Route.Appearance -> AppearanceScreen(onBack = pop)
                        Route.Vault -> VaultScreen(onBack = pop, onPlay = { u, title -> push(Route.Player(u, title, private = true)) })
                    }
                    } }
                    } }
                }
            }
        }
    }

    // Android 8-11: enter the floating player by hand when the user leaves mid-video (12+ auto-enters)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31) PipGate.params?.let { runCatching { enterPictureInPictureMode(it) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeViewIntent(intent)
        // a running app asked for a page (e.g. the vault, from the dialer code)
        startRoute(routeOf(intent))?.let { routeRequest.value = RouteRequest(it) }
    }

    // wrapper so the same route asked twice is still a new value
    private class RouteRequest(val route: Route)
    private val routeRequest = mutableStateOf<RouteRequest?>(null)

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /** The dialer code (in-process flag) opens the vault; the "route" extra is a debug/test entry only. */
    private fun routeOf(i: Intent?): String? {
        if (dev.altayeb.shoebill.vault.SecretCodeReceiver.vaultAsked) {
            dev.altayeb.shoebill.vault.SecretCodeReceiver.vaultAsked = false
            return "vault"
        }
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) i?.getStringExtra("route") else null
    }

    private fun startRoute(name: String?): Route? = when (name) {
        "settings" -> Route.Settings
        "appearance" -> Route.Appearance
        "search" -> Route.Search
        "vault" -> Route.Vault
        else -> null
    }

    private fun takeViewIntent(i: Intent?) {
        dev.altayeb.shoebill.download.DownloadFeature.linkFrom(i)?.let { dev.altayeb.shoebill.download.SharedLink.value.value = it; return }
        val uri = i?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        incoming.value = Incoming(uri)
    }
}
