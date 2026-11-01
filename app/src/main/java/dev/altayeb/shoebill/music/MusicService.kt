package dev.altayeb.shoebill.music

import androidx.media3.common.Player
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.launch
import dev.altayeb.shoebill.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope

/** Music keeps playing with the app closed; lock screen, headset and notification controls come from the media session. */
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null
    private val settingsScope = MainScope()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        audioSession = player.audioSessionId
        running = true
        session = MediaSession.Builder(this, player).build()
        val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        settingsScope.launch {
            AppSettings.get(this@MusicService).settings.collect {
                player.skipSilenceEnabled = it.skipSilence
                player.setHandleAudioBecomingNoisy(it.pauseOnUnplug)
                player.setAudioAttributes(attrs, it.pauseForOthers)
            }
        }
        restoreQueue(player)
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) saveQueue(p)
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    private val queuePrefs by lazy { getSharedPreferences("music_queue", MODE_PRIVATE) }

    private fun saveQueue(p: Player) {
        if (!AppSettings.get(this).settings.value.rememberQueue) { queuePrefs.edit().clear().apply(); return }
        if (p.mediaItemCount == 0) return // a restore still on its way must not lose the saved queue
        val ids = (0 until minOf(p.mediaItemCount, 2000)).joinToString(",") { p.getMediaItemAt(it).mediaId }
        queuePrefs.edit().putString("ids", ids).putInt("index", p.currentMediaItemIndex).putLong("pos", p.currentPosition.coerceAtLeast(0))
            .putBoolean("shuffle", p.shuffleModeEnabled).putInt("repeat", p.repeatMode).apply()
    }

    private fun restoreQueue(player: ExoPlayer) {
        if (!AppSettings.get(this).settings.value.rememberQueue) return
        val ids = queuePrefs.getString("ids", null)?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (ids.isEmpty()) return
        settingsScope.launch {
            val byId = runCatching { querySongs(this@MusicService, raw = true) }.getOrNull()?.associateBy { it.id } ?: return@launch
            // Songs deleted since are skipped; the index then points at the same song only if none before it went.
            val items = ids.mapNotNull { byId[it]?.item() }
            if (items.isEmpty() || player.mediaItemCount != 0) return@launch
            player.setMediaItems(items, queuePrefs.getInt("index", 0).coerceIn(0, items.lastIndex), queuePrefs.getLong("pos", 0L))
            player.shuffleModeEnabled = queuePrefs.getBoolean("shuffle", false)
            player.repeatMode = queuePrefs.getInt("repeat", Player.REPEAT_MODE_OFF)
            player.prepare()
        }
    }

    /** Swiping the app away stops music only if nothing is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p != null && AppSettings.get(this).settings.value.stopOnClose) p.pause()
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.player?.let { saveQueue(it) }
        running = false
        settingsScope.coroutineContext[Job]?.cancel()
        session?.run { player.release(); release() }
        session = null
        audioSession = 0
        super.onDestroy()
    }

    companion object { @Volatile var audioSession = 0; @Volatile var running = false; private set }
}
