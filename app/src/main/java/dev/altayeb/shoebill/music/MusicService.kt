package dev.altayeb.shoebill.music

import androidx.media3.common.Player
import android.content.Intent
import dev.altayeb.shoebill.MainActivity
import android.util.Log
import android.widget.Toast
import dev.altayeb.shoebill.BuildConfig
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.library.logic.GainMode
import dev.altayeb.shoebill.library.logic.MusicAccess
import dev.altayeb.shoebill.library.logic.NaturalOrder
import dev.altayeb.shoebill.library.logic.QueueRestore
import dev.altayeb.shoebill.library.logic.ReplayGain
import dev.altayeb.shoebill.library.logic.skipAfterError
import dev.altayeb.shoebill.settings.AppSettings
import dev.altayeb.shoebill.settings.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope

/**
 * Music keeps playing with the app closed; lock screen, headset, notification and Android Auto controls come from the library session.
 * Android Auto browses root > songs / albums / artists / playlists; ids are "songs", "album:<name>", "artist:<name>", "playlist:<name>".
 */
class MusicService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val settingsScope = MainScope()
    private var gainTrack: Double? = null
    private var gainAlbum: Double? = null
    private var failStreak = 0
    private var sleepJob: Job? = null
    private var idsJob: Job? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        audioSession = player.audioSessionId
        running = true
        instance = this
        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(android.app.PendingIntent.getActivity(this, 0, Intent(Intent.ACTION_VIEW, android.net.Uri.parse("shoebill://music"), this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        settingsScope.launch {
            AppSettings.get(this@MusicService).settings.collect {
                player.skipSilenceEnabled = it.skipSilence
                player.setHandleAudioBecomingNoisy(it.pauseOnUnplug)
                player.setAudioAttributes(attrs, it.pauseForOthers)
                applyGain(player, it)
            }
        }
        restoreQueue(player)
        // A kill mid-song resumes near where it was: the position is a few ints, every 15 s while playing.
        settingsScope.launch { while (true) { delay(15_000); if (player.isPlaying) saveState(player) } }
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) saveIds(p)
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) saveState(p)
            }

            // Tags arrive with the track's data; a new track starts without the previous track's gain.
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                MusicWidget.update(this@MusicService, item?.mediaMetadata?.title?.toString().orEmpty(), player.isPlaying)
                gainTrack = null
                gainAlbum = null
                applyGain(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) failStreak = 0; MusicWidget.update(this@MusicService, player.mediaMetadata.title?.toString().orEmpty(), isPlaying) }

            // A file deleted or no longer readable: say so once, move on, and stop when the whole queue is like that.
            override fun onPlayerError(error: PlaybackException) {
                failStreak++
                if (failStreak == 1) Toast.makeText(this@MusicService, getString(R.string.music_play_failed, player.currentMediaItem?.mediaMetadata?.title ?: ""), Toast.LENGTH_SHORT).show()
                if (skipAfterError(failStreak, player.mediaItemCount, player.hasNextMediaItem(), player.repeatMode == Player.REPEAT_MODE_ONE)) {
                    player.seekToNextMediaItem(); player.prepare(); player.play()
                } else player.stop()
            }

            // MP3, FLAC, Ogg and MP4 tags are static: they sit on the track format, and never reach onMetadata.
            override fun onTracksChanged(tracks: Tracks) {
                val tags = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }.flatMap { group ->
                    (0 until group.length).filter(group::isTrackSelected).flatMap { i -> group.getTrackFormat(i).metadata?.let(::tagPairs).orEmpty() }
                }
                val (track, album) = ReplayGain.fromTags(tags)
                gainTrack = track
                gainAlbum = album
                applyGain(player)
            }
        })
    }

    /** ReplayGain as player volume: attenuation only, see ReplayGain.volume. */
    private fun applyGain(p: Player, s: Settings = AppSettings.get(this).settings.value) {
        p.volume = ReplayGain.volume(s.replayGain, gainTrack, gainAlbum, s.replayGainPreamp)
        if (BuildConfig.DEBUG) Log.d("BrandState", "replayGain mode=${s.replayGain} track=$gainTrack album=$gainAlbum volume=${p.volume}")
    }

    private fun tagPairs(md: Metadata): List<Pair<String, String>> = (0 until md.length()).mapNotNull { i ->
        when (val e = md[i]) {
            is TextInformationFrame -> if (e.id == "TXXX" && e.description != null) e.description!! to e.value else null // ID3 user text
            is InternalFrame -> e.description to e.text // ID3 and MP4 "----" freeform
            is VorbisComment -> e.key to e.value // FLAC, Ogg, Opus
            else -> null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    private val queuePrefs by lazy { getSharedPreferences("music_queue", MODE_PRIVATE) }
    private val rememberQueue get() = AppSettings.get(this).settings.value.rememberQueue

    /** Index, song and position: a few ints on every play, pause and track change. */
    private fun saveState(p: Player) {
        if (!rememberQueue) { queuePrefs.edit().clear().apply(); return }
        if (p.mediaItemCount == 0) return // a restore still on its way must not lose the saved queue
        queuePrefs.edit().putInt("index", p.currentMediaItemIndex).putString("current", p.currentMediaItem?.mediaId).putLong("pos", p.currentPosition.coerceAtLeast(0))
            .putBoolean("shuffle", p.shuffleModeEnabled).putInt("repeat", p.repeatMode).apply()
    }

    /** The ids: only when the queue itself changes, joined and written off the main thread once it has settled for a second. */
    private fun saveIds(p: Player, now: Boolean = false) {
        if (!rememberQueue || p.mediaItemCount == 0) return
        val ids = List(minOf(p.mediaItemCount, 2000)) { p.getMediaItemAt(it).mediaId }
        idsJob?.cancel()
        if (now) queuePrefs.edit().putString("ids", ids.joinToString(",")).apply()
        else idsJob = settingsScope.launch(Dispatchers.IO) { delay(1000); queuePrefs.edit().putString("ids", ids.joinToString(",")).apply() }
    }

    private fun restoreQueue(player: ExoPlayer) {
        if (!rememberQueue) return
        val ids = queuePrefs.getString("ids", null)?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (ids.isEmpty()) return
        settingsScope.launch {
            val byId = runCatching { querySongs(this@MusicService, raw = true) }.getOrNull()?.associateBy { it.id } ?: return@launch
            // Songs deleted since are skipped; the index follows the song that was playing.
            val (kept, index) = QueueRestore.restore(ids, queuePrefs.getString("current", null)?.toLongOrNull(), queuePrefs.getInt("index", 0), byId::containsKey)
            if (kept.isEmpty() || player.mediaItemCount != 0) return@launch
            player.setMediaItems(kept.map { byId.getValue(it).item() }, index, queuePrefs.getLong("pos", 0L))
            player.shuffleModeEnabled = queuePrefs.getBoolean("shuffle", false)
            player.repeatMode = queuePrefs.getInt("repeat", Player.REPEAT_MODE_OFF)
            player.prepare()
        }
    }

    private fun armSleep() {
        sleepJob?.cancel()
        val at = sleepAt
        if (at > 0L) sleepJob = settingsScope.launch { delay((at - System.currentTimeMillis()).coerceAtLeast(0)); session?.player?.pause(); sleepAt = 0L }
    }

    @OptIn(UnstableApi::class)
    private val callback = object : MediaLibrarySession.Callback {
        // The service is exported for Android Auto, so every song name would otherwise be readable by any installed app.
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val ok = MusicAccess.allowed(controller.packageName, packageName, controller.isTrusted) ||
                session.isMediaNotificationController(controller) || session.isAutomotiveController(controller) || session.isAutoCompanionController(controller)
            return if (ok) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(folder("root", "Shoebill", MediaMetadata.FOLDER_TYPE_MIXED), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
            LibraryResult.ofItemList(ImmutableList.copyOf(browse(parentId)), params)
        }

        // Items picked in a car arrive by id alone, because a controller's copy carries no file address. Raw, like the restore: a hidden folder must not drop an item silently.
        override fun onAddMediaItems(
            session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = later {
            val all = if (mediaItems.all { it.localConfiguration != null }) emptyList()
            else runCatching { querySongs(this@MusicService, raw = true) }.getOrDefault(emptyList())
            mediaItems.flatMapTo(ArrayList()) { item ->
                val q = item.requestMetadata.searchQuery
                when {
                    item.localConfiguration != null -> listOf(item)
                    // "Hey Google, play X in Shoebill": a title, artist, album or genre; an empty query is "play music"
                    q != null -> all.filter { q.isBlank() || listOf(it.title, it.artist, it.album, it.genre).any { f -> f.contains(q, ignoreCase = true) } }.map { it.item() }
                    else -> listOfNotNull(all.firstOrNull { it.id.toString() == item.mediaId }?.item())
                }
            }
        }
    }

    /** Runs a suspend query for a Media3 callback, which needs a future. */
    private fun <T> later(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        settingsScope.launch { runCatching { block() }.onSuccess { f.set(it) }.onFailure { f.setException(it) } }
        return f
    }

    /** Same grouping and filters as the Music tab. No permission or no songs = empty folders, not a crash. */
    private suspend fun browse(parentId: String): List<MediaItem> {
        val kind = parentId.substringBefore(':')
        val arg = parentId.substringAfter(':', "")
        val all = runCatching { querySongs(this) }.getOrDefault(emptyList())
        val byTrack = compareBy<Song> { it.track }.thenBy(NaturalOrder) { it.title }
        return when (kind) {
            "root" -> listOf(
                folder("songs", getString(R.string.music_songs), MediaMetadata.FOLDER_TYPE_TITLES),
                folder("albums", getString(R.string.music_albums), MediaMetadata.FOLDER_TYPE_ALBUMS),
                folder("artists", getString(R.string.music_artists), MediaMetadata.FOLDER_TYPE_ARTISTS),
                folder("playlists", getString(R.string.music_lists), MediaMetadata.FOLDER_TYPE_PLAYLISTS),
            )
            "songs" -> all.map { it.item() }
            "albums" -> all.map { it.album }.distinct().sortedWith(NaturalOrder).map { folder("album:$it", it, MediaMetadata.FOLDER_TYPE_ALBUMS) }
            "album" -> all.filter { it.album == arg }.sortedWith(byTrack).map { it.item() }
            "artists" -> all.map { it.artist }.distinct().sortedWith(NaturalOrder).map { folder("artist:$it", it, MediaMetadata.FOLDER_TYPE_ARTISTS) }
            "artist" -> all.filter { it.artist == arg }.sortedWith(byTrack).map { it.item() }
            "playlists" -> MusicLists(this).names().map { folder("playlist:$it", it, MediaMetadata.FOLDER_TYPE_PLAYLISTS) }
            "playlist" -> MusicLists(this).songs(arg, all).map { it.item() }
            else -> emptyList()
        }
    }

    private fun folder(id: String, title: String, type: Int) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setFolderType(type).build(),
    ).build()

    /** Swiping the app away stops music only if nothing is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p != null && AppSettings.get(this).settings.value.stopOnClose) p.pause()
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.player?.let { saveState(it); saveIds(it, now = true) }
        running = false
        instance = null
        sleepAt = 0L
        settingsScope.coroutineContext[Job]?.cancel()
        session?.run { player.release(); release() }
        session = null
        audioSession = 0
        super.onDestroy()
    }

    companion object {
        @Volatile var audioSession = 0; private set
        @Volatile var running = false; private set
        private var instance: MusicService? = null

        /** Sleep timer as wall-clock ms, 0 = off. Lives with the service, so closing the sheet, rotating or a theme change keeps it. */
        var sleepAt by mutableLongStateOf(0L); private set
        var sleepMinutes by mutableIntStateOf(0); private set
        fun setSleep(minutes: Int) {
            sleepMinutes = minutes
            sleepAt = if (minutes == 0) 0L else System.currentTimeMillis() + minutes * 60_000L
            instance?.armSleep()
        }
    }
}
