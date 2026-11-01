package dev.altayeb.shoebill.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.altayeb.shoebill.ui.SecureWindow

/** Full-screen window for the player: orientation from [orientation] (settings value), keep-screen-on, hidden bars, secure flag for the vault. Undone on dispose. */
@Composable
internal fun PlayerWindowEffects(activity: Activity, window: Window, private: Boolean, orientation: Int) {
    DisposableEffect(Unit) {
        val oldOrientation = activity.requestedOrientation
        activity.requestedOrientation = when (orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (private) SecureWindow.acquire(activity)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity.requestedOrientation = oldOrientation
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (private) SecureWindow.release(activity)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
