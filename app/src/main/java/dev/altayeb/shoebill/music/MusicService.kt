package dev.altayeb.shoebill.music

import androidx.media3.common.Player
import android.content.Intent
import android.util.Log
import dev.altayeb.shoebill.BuildConfig
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
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
import kotlinx.coroutines.launch
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.library.logic.GainMode
import dev.altayeb.shoebill.library.logic.NaturalOrder
import dev.altayeb.shoebill.library.logic.ReplayGain
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

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        audioSession = player.audioSessionId
        running = true
        session = MediaLibrarySession.Builder(this, player, callback).build()
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
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) saveQueue(p)
            }

            // Tags arrive with the track's data; a new track starts without the previous track's gain.
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                gainTrack = null
                gainAlbum = null
                applyGain(player)
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

    private val callback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(folder("root", "Shoebill", MediaMetadata.FOLDER_TYPE_MIXED), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
            LibraryResult.ofItemList(ImmutableList.copyOf(browse(parentId)), params)
        }

        // Items picked in a car arrive by id alone, because a controller's copy carries no file address.
        override fun onAddMediaItems(
            session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = later {
            val all = if (mediaItems.all { it.localConfiguration != null }) emptyList()
            else runCatching { querySongs(this@MusicService) }.getOrDefault(emptyList())
            mediaItems.mapNotNullTo(ArrayList()) { item ->
                if (item.localConfiguration != null) item else all.firstOrNull { it.id.toString() == item.mediaId }?.item()
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
