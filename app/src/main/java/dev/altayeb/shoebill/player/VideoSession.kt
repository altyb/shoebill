package dev.altayeb.shoebill.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.altayeb.shoebill.MainActivity
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.settings.AppSettings

/** What the video player shows the rest of the phone: the notification and its buttons read this. */
object VideoSession {
    var token: MediaSession.Token? = null
    var title = ""
    var paused = true
    var toggle: (() -> Unit)? = null
    var seekBy: ((Int) -> Unit)? = null
}

/** Keeps the process alive and shows lock-screen/notification controls while a (non-private) video is open. */
class VideoPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> VideoSession.toggle?.invoke()
            ACTION_BACK -> VideoSession.seekBy?.invoke(-10)
            ACTION_FWD -> VideoSession.seekBy?.invoke(10)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.video_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, note(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    companion object {
        const val ID = 4201
        const val CHANNEL = "video_playback"
        const val ACTION_TOGGLE = "toggle"; const val ACTION_BACK = "back"; const val ACTION_FWD = "fwd"

        fun refresh(c: Context) = c.getSystemService(NotificationManager::class.java).notify(ID, note(c))

        private fun act(c: Context, a: String, icon: Int, label: Int) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(c, icon), c.getString(label),
            PendingIntent.getService(c, a.hashCode(), Intent(c, VideoPlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()

        private fun note(c: Context): Notification {
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(VideoSession.title.ifEmpty { c.getString(R.string.video_channel) })
                .setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(act(c, ACTION_BACK, android.R.drawable.ic_media_rew, R.string.player_rewind_10))
                .addAction(act(c, ACTION_TOGGLE, if (VideoSession.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause, if (VideoSession.paused) R.string.play else R.string.pause))
                .addAction(act(c, ACTION_FWD, android.R.drawable.ic_media_ff, R.string.player_forward_10))
            b.setStyle(Notification.MediaStyle().setMediaSession(VideoSession.token).setShowActionsInCompactView(0, 1, 2))
            return b.build()
        }
    }
}

/**
 * What a phone expects from any player: pause when a call or another app takes the audio, pause when headphones
 * come out, answer the lock screen and headset buttons, and stay alive in the background. Vault playback ([private])
 * gets the audio rules only: its title must not reach the lock screen or the notification shade.
 */
@Composable
fun rememberVideoSession(player: MpvPlayer, title: String, private: Boolean) {
    val ctx = LocalContext.current.applicationContext
    val settings by AppSettings.get(ctx).settings.collectAsState()
    val paused by player.paused.collectAsState()
    val duration by player.duration.collectAsState()
    val speed by player.speed.collectAsState()
    val position by player.position.collectAsState()
    val am = remember { ctx.getSystemService(AudioManager::class.java) }

    // audio focus: only while playing, only if the setting asks for it
    DisposableEffect(paused, settings.pauseForOthers) {
        var lostToOther = false
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener { c ->
                when (c) {
                    AudioManager.AUDIOFOCUS_LOSS -> { lostToOther = false; player.setPaused(true) }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (!player.paused.value) { lostToOther = true; player.setPaused(true) }
                    AudioManager.AUDIOFOCUS_GAIN -> if (lostToOther) { lostToOther = false; player.setPaused(false) }
                }
            }.build()
        val held = !paused && settings.pauseForOthers && am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        onDispose { if (held) am.abandonAudioFocusRequest(req) }
    }

    // headphones or Bluetooth taken off
    DisposableEffect(settings.pauseOnUnplug) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { player.setPaused(true) } }
        if (settings.pauseOnUnplug) ctx.registerReceiver(r, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        onDispose { if (settings.pauseOnUnplug) ctx.unregisterReceiver(r) }
    }

    if (private) return
    val session = remember { MediaSession(ctx, "shoebill-video") }
    DisposableEffect(session) {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = player.setPaused(false)
            override fun onPause() = player.setPaused(true)
            override fun onSeekTo(pos: Long) = player.seekTo(pos / 1000.0)
            override fun onFastForward() = player.seekBy(10.0)
            override fun onRewind() = player.seekBy(-10.0)
        })
        session.isActive = true
        VideoSession.token = session.sessionToken
        VideoSession.toggle = { player.togglePause() }
        VideoSession.seekBy = { player.seekBy(it.toDouble()) }
        ctx.startForegroundService(Intent(ctx, VideoPlaybackService::class.java))
        onDispose {
            ctx.stopService(Intent(ctx, VideoPlaybackService::class.java))
            session.isActive = false; session.release()
            VideoSession.token = null; VideoSession.toggle = null; VideoSession.seekBy = null
        }
    }
    // the position moves on its own while playing: the system extrapolates, so a refresh every 5 s is enough
    LaunchedEffect(paused, title, duration, speed, (position / 5).toInt()) {
        VideoSession.title = title; VideoSession.paused = paused
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putLong(MediaMetadata.METADATA_KEY_DURATION, (duration * 1000).toLong()).build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND)
            .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, (position * 1000).toLong(), if (paused) 0f else speed.toFloat()).build())
        VideoPlaybackService.refresh(ctx)
    }
}
