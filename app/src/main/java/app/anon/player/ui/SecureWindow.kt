package app.anon.player.ui

import android.app.Activity
import android.view.WindowManager

/**
 * FLAG_SECURE by reference count: the vault screen and the private player overlap during route transitions, and
 * the one leaving must not clear the flag the one arriving just set.
 */
object SecureWindow {
    private var holders = 0

    fun acquire(activity: Activity) {
        if (holders++ == 0) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) {
        if (--holders <= 0) {
            holders = 0
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
