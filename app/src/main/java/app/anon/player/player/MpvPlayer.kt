package app.anon.player.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Track(val id: String, val type: String, val title: String?, val lang: String?, val codec: String?, val selected: Boolean)

/** Thin wrapper over libmpv. Observer callbacks run on an mpv thread: they only write StateFlows. */
class MpvPlayer(context: Context) : MPVLib.EventObserver {
    // Debug builds log one state line per second (tag AnonState) so qa/player-e2e.sh can assert real player state.
    private val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    // own thread: these reads wait on mpv's core lock, and the UI thread must never wait on mpv for a debug log
    private val stateThread by lazy { android.os.HandlerThread("anon-state").apply { start() } }
    private val stateHandler by lazy { android.os.Handler(stateThread.looper) }
    private val stateTick = object : Runnable {
        override fun run() {
            val keys = listOf("filename", "duration", "time-pos", "pause", "sid", "aid", "secondary-sid", "sub-delay", "hwdec-current", "volume", "speed",
                "video-zoom", "ab-loop-a", "ab-loop-b", "video-aspect-override", "eof-reached", "af", "sub-pos", "sub-start", "user-data/anon/ui")
            Log.d("AnonState", keys.joinToString(" ") { k -> "$k=${runCatching { mpv.getPropertyString(k) }.getOrNull()}" })
            stateHandler.postDelayed(this, 1000)
        }
    }
    private val appContext = context.applicationContext
    private val _position = MutableStateFlow(0.0)
    private val _duration = MutableStateFlow(0.0)
    private val _paused = MutableStateFlow(true)
    private val _eof = MutableStateFlow(false)
    private val _speed = MutableStateFlow(1.0)
    private val _volume = MutableStateFlow(100.0)
    private val _loads = MutableStateFlow(0)
    private val _buffering = MutableStateFlow(false)
    /** Waiting for data (network cache) or a seek to land. */
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private var cacheWait = false
    private var seeking = false
    val position: StateFlow<Double> = _position.asStateFlow()
    val duration: StateFlow<Double> = _duration.asStateFlow()
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    val eof: StateFlow<Boolean> = _eof.asStateFlow()
    val speed: StateFlow<Double> = _speed.asStateFlow()
    val volume: StateFlow<Double> = _volume.asStateFlow()
    /** Bumps on every MPV_EVENT_FILE_LOADED, so the screen can restore per-file state. */
    val loads: StateFlow<Int> = _loads.asStateFlow()

    @Volatile private var lastLogMs = 0L

    /** Called off the main thread when a file opened in the background turns out unreadable. */
    @Volatile var onOpenFailed: (() -> Unit)? = null
    @Volatile private var destroyed = false

    /** Audio session the equalizer attaches to. */
    private val audioSession = appContext.getSystemService(android.media.AudioManager::class.java).generateAudioSessionId()
    /** Null on phones without the effect. */
    val fx: AudioFx? by lazy { AudioFx.open(appContext, audioSession) }
    /** The file as other tools may read it; null in the vault, where nothing may leave the player. */
    @Volatile var shareableUri: Uri? = null
    @Volatile var shareableTitle: String = ""

    private val mpv: MPVLib = requireNotNull(MPVLib.create(appContext)) { "libmpv create failed" }

    init {
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("hwdec", "mediacodec-copy")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audiotrack-session-id", audioSession.toString())
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("volume-max", "200")
        mpv.setOptionString("msg-level", if (debuggable) "all=warn" else "all=no")
        mpv.init()
        // debug builds: mpv's own warnings/errors (failed opens, demuxer/decoder errors) go to logcat under "mpv-log".
        // Release logs nothing: file names and paths are private.
        if (debuggable) mpv.addLogObserver(object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) {
                Log.w("mpv-log", "[$prefix] ${text.trimEnd()}")
            }
        })
        if (debuggable) stateHandler.postDelayed(stateTick, 1000)
        mpv.addObserver(this)
        mpv.observeProperty("time-pos", 5)
        mpv.observeProperty("duration", 5)
        mpv.observeProperty("pause", 3)
        mpv.observeProperty("eof-reached", 3)
        mpv.observeProperty("speed", 5)
        mpv.observeProperty("volume", 5)
        mpv.observeProperty("paused-for-cache", 3)
        mpv.observeProperty("seeking", 3)
    }

    /** False when the file cannot be opened (deleted, moved into the vault, permission gone). */
    fun load(uri: Uri): Boolean {
        if (uri.authority?.endsWith(".smb") == true) {
            // a network share: opening it talks to the server, which must not happen on the main thread
            Thread({
                val fd = runCatching { appContext.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()?.detachFd()
                if (destroyed) { fd?.let { runCatching { android.os.ParcelFileDescriptor.adoptFd(it).close() } } }
                else if (fd == null) onOpenFailed?.invoke()
                else mpv.command(arrayOf("loadfile", "fdclose://$fd"))
            }, "smb-open").start()
        } else if (uri.scheme == "content") {
            val fd = runCatching { appContext.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()?.detachFd()
                ?: return false
            mpv.command(arrayOf("loadfile", "fdclose://$fd"))
        } else if (uri.scheme == "anonfd") {
            mpv.command(arrayOf("loadfile", "fdclose://${uri.host}")) // vault: decrypting proxy fd, see vault/VaultFd.kt
        } else {
            mpv.command(arrayOf("loadfile", uri.toString()))
        }
        return true
    }

    private val fxOpened: Boolean get() = (::fx.getDelegate() as Lazy<*>).isInitialized()

    fun command(vararg args: String) = mpv.command(arrayOf(*args))
    fun getString(name: String): String? = mpv.getPropertyString(name)
    fun getDouble(name: String): Double? = mpv.getPropertyDouble(name)
    fun setString(name: String, value: String) = mpv.setPropertyString(name, value)
    fun setDouble(name: String, value: Double) = mpv.setPropertyDouble(name, value)

    fun togglePause() = setPaused(!_paused.value)
    fun setPaused(p: Boolean) = mpv.setPropertyBoolean("pause", p)
    fun seekTo(sec: Double) = mpv.command(arrayOf("seek", sec.coerceAtLeast(0.0).toString(), "absolute"))
    fun seekBy(sec: Double) = mpv.command(arrayOf("seek", sec.toString(), "relative+exact"))
    fun setSpeed(v: Double) = mpv.setPropertyDouble("speed", v)
    fun setVolume(v: Double) = mpv.setPropertyDouble("volume", v)

    /** Scalar sub-properties only: track-list as a node would need a node reader this wrapper lacks. */
    fun tracks(): List<Track> {
        val n = mpv.getPropertyInt("track-list/count") ?: 0
        return (0 until n).map { i ->
            val p = "track-list/$i/"
            Track(
                id = mpv.getPropertyInt(p + "id")?.toString() ?: "",
                type = mpv.getPropertyString(p + "type") ?: "",
                title = mpv.getPropertyString(p + "title"),
                lang = mpv.getPropertyString(p + "lang"),
                codec = mpv.getPropertyString(p + "codec"),
                selected = mpv.getPropertyBoolean(p + "selected") == true,
            )
        }
    }

    fun surfaceCreated(surface: Surface) {
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
    }

    fun surfaceChanged(width: Int, height: Int) =
        mpv.setPropertyString("android-surface-size", "${width}x$height")

    fun surfaceDestroyed() {
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    fun destroy() {
        destroyed = true
        if (debuggable) {
            stateHandler.removeCallbacks(stateTick)
            stateThread.quitSafely()
            stateThread.join(2000) // a tick in flight must not read a destroyed mpv
        }
        runCatching { if (fxOpened) fx?.release() }
        mpv.destroy()
    }

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> _paused.value = value
            "eof-reached" -> _eof.value = value
            "paused-for-cache" -> { cacheWait = value; _buffering.value = cacheWait || seeking }
            "seeking" -> { seeking = value; _buffering.value = cacheWait || seeking }
        }
    }
    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                _position.value = value
                val now = System.currentTimeMillis()
                if (!_paused.value && now - lastLogMs >= 1000) {
                    lastLogMs = now
                    Log.d("Anon", "time-pos=%.2f".format(value))
                }
            }
            "duration" -> _duration.value = value
            "speed" -> _speed.value = value
            "volume" -> _volume.value = value
        }
    }
    override fun event(eventId: Int) {
        if (eventId == MPV_EVENT_FILE_LOADED) _loads.value = _loads.value + 1
    }

    private companion object {
        const val MPV_EVENT_FILE_LOADED = 8
    }
}
