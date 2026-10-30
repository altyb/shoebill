package app.anon.player.player

import app.anon.player.R
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer

private const val ACTION_TOGGLE = "app.anon.player.PIP_TOGGLE"

/** Who may enter PiP right now: MainActivity.onUserLeaveHint asks this (pre-Android 12 path). */
object PipGate {
    var params: PictureInPictureParams? = null
}

/**
 * Floating player: leaving the app while a video plays shrinks it into a PiP window with a play/pause action.
 * Off for vault (private) playback. Returns whether the activity is in PiP right now (hide the controls then).
 */
@Composable
fun rememberPip(activity: Activity, player: MpvPlayer, paused: Boolean, enabled: Boolean): Boolean {
    val ctx = activity.applicationContext
    var inPip by remember { mutableStateOf(activity.isInPictureInPictureMode) }

    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        (activity as? ComponentActivity)?.addOnPictureInPictureModeChangedListener(listener)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { player.togglePause() }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            (activity as? ComponentActivity)?.removeOnPictureInPictureModeChangedListener(listener)
            ctx.unregisterReceiver(receiver)
            PipGate.params = null
            if (Build.VERSION.SDK_INT >= 31) {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }
    }

    LaunchedEffect(paused, enabled) {
        if (!enabled) {
            PipGate.params = null
            if (Build.VERSION.SDK_INT >= 31) activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            return@LaunchedEffect
        }
        val w = player.getDouble("dwidth") ?: 16.0
        val h = player.getDouble("dheight") ?: 9.0
        // Android only accepts 1:2.39 .. 2.39:1
        val ratio = Rational((w * 100).toInt(), (h * 100).toInt()).let {
            when {
                it.toFloat() > 2.39f -> Rational(239, 100)
                it.toFloat() < 1 / 2.39f -> Rational(100, 239)
                else -> it
            }
        }
        val action = RemoteAction(
            Icon.createWithResource(ctx, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
            activity.getString(if (paused) R.string.play else R.string.pause), activity.getString(if (paused) R.string.play else R.string.pause),
            PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_TOGGLE).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE),
        )
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(listOf(action))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(!paused).setSeamlessResizeEnabled(true)
        val params = b.build()
        PipGate.params = if (paused) null else params
        activity.setPictureInPictureParams(params)
    }
    return inPip
}
