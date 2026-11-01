package dev.altayeb.shoebill.player

import dev.altayeb.shoebill.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.altayeb.shoebill.ui.sharedVideo
import dev.altayeb.shoebill.ui.BrandLoader
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.altayeb.shoebill.data.FileState
import dev.altayeb.shoebill.data.PlaybackStore
import dev.altayeb.shoebill.library.formatTime
import dev.altayeb.shoebill.settings.AppSettings
import dev.altayeb.shoebill.settings.Decoder
import dev.altayeb.shoebill.subs.SubKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.content.pm.ApplicationInfo
import android.provider.OpenableColumns
import androidx.compose.material3.MaterialTheme
import java.util.Locale
import dev.altayeb.shoebill.player.logic.nextAspect

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
/** [private] = vault playback: no history, no resume, no thumbnails anywhere (ق-10), screenshots blocked (ق-14). */
fun PlayerScreen(uri: Uri, title: String, onBack: () -> Unit, private: Boolean = false, queue: List<Pair<Uri, String>>? = null) {
    val context = LocalContext.current
    val activity = LocalActivity.current as Activity
    val window = activity.window
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val store = remember { PlaybackStore.get(context) }
    val settingsState = AppSettings.get(context).settings.collectAsStateWithLifecycle()
    val player = remember(uri) { MpvPlayer(context) }
    val position by player.position.collectAsStateWithLifecycle()
    val duration by player.duration.collectAsStateWithLifecycle()
    val paused by player.paused.collectAsStateWithLifecycle()
    val inPip = rememberPip(activity, player, paused, enabled = !private)
    // leaving the app (or closing the floating window) pauses, unless background audio is on
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && !AppSettings.get(context).settings.value.backgroundAudio) player.setPaused(true)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val eof by player.eof.collectAsStateWithLifecycle()
    val loads by player.loads.collectAsStateWithLifecycle()
    val speed by player.speed.collectAsStateWithLifecycle()
    val volume by player.volume.collectAsStateWithLifecycle()

    var current by remember(uri) { mutableStateOf(uri) }
    var currentTitle by remember(uri) { mutableStateOf(title) }
    // these six are shared with the touch handler (PlayerGestures.kt), so the state objects stay reachable
    val overlayVisibleState = remember { mutableStateOf(true) }
    var overlayVisible by overlayVisibleState
    var interaction by remember { mutableIntStateOf(0) }
    val panelState = remember { mutableStateOf<Panel?>(null) }
    var panel by panelState
    val lockedState = remember { mutableStateOf(false) }
    var locked by lockedState
    val seekPreviewState = remember { mutableStateOf<Double?>(null) }
    var seekPreview by seekPreviewState
    val hudState = remember { mutableStateOf<Hud?>(null) }
    var hud by hudState
    var notice by remember { mutableStateOf<Notice?>(null) }
    var videoCodec by remember { mutableStateOf<String?>(null) }
    var subLabel by remember { mutableStateOf(context.getString(R.string.off)) }
    var decoderLabel by remember { mutableStateOf("HW+") }
    var abA by remember { mutableStateOf<Double?>(null) }
    var abB by remember { mutableStateOf<Double?>(null) }
    // read once per file on FILE_LOADED (mpv reads block), never per frame
    var chapters by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    val chapterSecs = remember(chapters) { chapters.map { it.second } }
    var sleepUntil by remember { mutableStateOf<Long?>(null) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var prevFile by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var nextFile by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var endDismissed by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(5) }
    var trackVersion by remember { mutableIntStateOf(0) }
    // online search and sync tool (ش-21, ش-22); per-file state resets with the file
    val searchOpenState = remember { mutableStateOf(false) }
    var searchOpen by searchOpenState
    var extSub by remember(current) { mutableStateOf<File?>(null) }
    var extSubTrack by remember(current) { mutableStateOf<String?>(null) }
    var vHash by remember(current) { mutableStateOf<VideoHash?>(null) }
    var hiddenProviders by remember { mutableStateOf(setOf<String>()) }
    val keys = remember { SubKeys(prefsKeyStore(context)) }
    fun lastSubId(): String? = player.tracks().lastOrNull { it.type == "sub" }?.id
    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        // same path as sibling subtitles: UTF-8 copy (old windows-1256 Arabic files) and the real name as the track title
        if (picked != null) runCatching {
            val cr = context.contentResolver
            val bytes = cr.openInputStream(picked)!!.use { it.readBytes() }
            val name = cr.query(picked, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "sub.srt"
            val file = cacheSubtitle(context, "pick-$name", bytes, name.substringAfterLast('.').lowercase())
            player.command("sub-add", file.absolutePath, "select", name.substringBeforeLast('.'))
            trackVersion++
        }
    }

    /** Remember the current file (ق-03, ق-12). Skipped until mpv knows its duration. */
    fun saveState() {
        val dur = player.duration.value
        if (private || dur <= 0.0) return
        store.save(
            FileState(
                key = current.toString(),
                title = currentTitle,
                positionMs = (player.position.value * 1000).toLong(),
                durationMs = (dur * 1000).toLong(),
                subtitle = extSub?.takeIf { player.getString("sid") == extSubTrack }?.absolutePath ?: player.getString("sid"),
                subDelayMs = ((player.getDouble("sub-delay") ?: 0.0) * 1000).toLong(),
                audio = player.getString("aid"),
                hwdec = player.getString("hwdec"),
                aspect = player.getString("video-aspect-override"),
                lastPlayed = System.currentTimeMillis(),
            ),
        )
    }

    /** Load [u], starting where the user left it (ش-07, ق-03). mpv applies `start` before the first frame, so no seek race. */
    fun loadAt(u: Uri) {
        val fs = if (private) null else store.get(u.toString())
        val resume = settingsState.value.autoResume && fs != null && fs.unfinished
        player.setString("start", if (resume) "%.3f".format(Locale.ROOT, fs!!.positionMs / 1000.0) else "none")
        player.shareableUri = if (private) null else u
        player.onOpenFailed = { notice = Notice(context.getString(R.string.player_open_failed)) }
        player.shareableTitle = currentTitle
        val prefs = settingsState.value
        player.setString("alang", prefs.audioLang)
        player.setString("slang", prefs.subLang)
        player.setString("loop-file", if (prefs.loopOne) "inf" else "no")
        if (!player.load(u)) notice = Notice(context.getString(R.string.player_open_failed))
    }

    fun openFile(u: Uri, t: String) {
        saveState()
        notice = null; endDismissed = false; abA = null; abB = null; chapters = emptyList(); videoCodec = null
        player.setString("ab-loop-a", "no")
        player.setString("ab-loop-b", "no")
        current = u
        currentTitle = t
        loadAt(u)
    }

    fun setBrightness(v: Float) {
        window.attributes = window.attributes.also { it.screenBrightness = v }
        if (settingsState.value.rememberBrightness) AppSettings.get(context).update { it.copy(brightness = v) }
    }

    DisposableEffect(player) {
        onDispose { saveState(); player.destroy() }
    }
    // "Follow the video": turn once the file's shape is known, for each new file.
    LaunchedEffect(current) {
        if (settingsState.value.orientation != 2) return@LaunchedEffect
        var w = 0.0; var h = 0.0
        repeat(40) {
            if (w <= 0.0 || h <= 0.0) { delay(250); w = player.getDouble("dwidth") ?: 0.0; h = player.getDouble("dheight") ?: 0.0 }
        }
        if (w > 0.0 && h > 0.0) activity.requestedOrientation = if (h > w) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    LaunchedEffect(player) {
        val start = settingsState.value
        // Once per session: changing speed mid-way must carry over to the next file.
        if (start.defaultSpeed != 1f) player.setSpeed(start.defaultSpeed.toDouble())
        if (start.rememberBrightness && start.brightness >= 0f) window.attributes = window.attributes.also { it.screenBrightness = start.brightness }
        loadAt(uri)
        while (true) { delay(5000); saveState() }
    }

    PlayerWindowEffects(activity, window, private, settingsState.value.orientation)

    // a new file: restore its per-file state (ق-12), resume if unfinished (ش-07), fall back to SW if HW fails (ق-08)
    // Off the UI thread: every mpv read waits on mpv's core lock, and a stuck hardware decoder holds it (seen as an ANR).
    LaunchedEffect(loads) { withContext(Dispatchers.IO) {
        if (loads == 0) return@withContext
        val fs = store.get(current.toString())
        player.setString("hwdec", fs?.hwdec ?: settingsState.value.decoder.hwdec)
        player.setString("deinterlace", if (settingsState.value.deinterlace) "auto" else "no")
        attachSiblingSubs(context, player, current)
        fs?.subtitle?.let { s ->
            if (s.startsWith("/")) {
                if (File(s).exists()) {
                    player.command("sub-add", s, "select")
                    extSub = File(s)
                    extSubTrack = lastSubId()
                }
            } else {
                player.setString("sid", s)
            }
        }
        fs?.audio?.let { player.setString("aid", it) }
        fs?.aspect?.let { player.setString("video-aspect-override", it) }
        fs?.let { player.setDouble("sub-delay", it.subDelayMs / 1000.0) }
        if (settingsState.value.autoResume && fs != null && fs.unfinished) {
            notice = Notice(context.getString(R.string.player_resume_from, formatTime(fs.positionMs)), context.getString(R.string.player_from_start)) { player.seekTo(0.0) }
        }
        delay(1500)
        val wanted = player.getString("hwdec")
        val hasVideo = !player.getString("video-format").isNullOrEmpty()
        if (hasVideo && wanted != "no" && player.getString("hwdec-current") == "no") {
            player.setString("hwdec", "no")
            notice = Notice(context.getString(R.string.player_switched_sw))
        }
        videoCodec = player.getString("video-codec")?.substringBefore(" / ") // "H.264 / AVC / MPEG-4 AVC / …" -> "H.264"
        decoderLabel = Decoder.values().firstOrNull { it.hwdec == player.getString("hwdec") }?.let { decoderName(it) } ?: "HW+"
    } }

    LaunchedEffect(loads) { if (loads > 0) chapters = withContext(Dispatchers.IO) { player.chapters() } }

    LaunchedEffect(loads, trackVersion, panel) {
        subLabel = player.tracks().firstOrNull { it.type == "sub" && it.selected }?.let { it.lang ?: it.title ?: context.getString(R.string.player_subtitle_fallback) } ?: context.getString(R.string.off)
    }

    LaunchedEffect(current) {
        vHash = withContext(Dispatchers.IO) { videoHash(context, current) }
    }

    LaunchedEffect(current) {
        prevFile = null
        nextFile = null
        // a playlist decides prev/next; otherwise the neighbours in the same folder
        val i = queue?.indexOfFirst { it.first == current } ?: -1
        prevFile = if (queue != null) queue.getOrNull(i - 1) else siblingInFolder(context, current, -1)
        nextFile = if (queue != null) queue.getOrNull(i + 1) else siblingInFolder(context, current, 1)
    }

    LaunchedEffect(notice) {
        if (notice != null) { delay(4000); notice = null }
    }

    LaunchedEffect(settingsState.value.subStyle) { applySubStyle(player, settingsState.value.subStyle) }

    // qa/e2e.py turns auto-hide off on debug builds (a uiautomator dump outlasts the 3 s)
    val qaKeepUi = remember {
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            File(context.filesDir, "qa_keep_ui").exists()
    }
    // overlay auto-hide: 3 s without touch, never while paused, a panel is open, the finger is on the seek bar, or at the end
    LaunchedEffect(overlayVisible, interaction, panel, searchOpen, seekPreview, eof, paused) {
        if (!qaKeepUi && overlayVisible && !paused && panel == null && !searchOpen && seekPreview == null && !eof) {
            delay(settingsState.value.controlsHideSec * 1000L)
            overlayVisible = false
        }
    }

    // end of file: next card with a 5 s countdown (ش-14)
    val showEnd = eof && settingsState.value.playNext && nextFile != null && !endDismissed
    LaunchedEffect(showEnd, current) {
        countdown = 5
        if (showEnd) {
            repeat(5) { delay(1000); countdown-- }
            nextFile?.let { openFile(it.first, it.second) }
        }
    }

    LaunchedEffect(sleepUntil) {
        val until = sleepUntil ?: return@LaunchedEffect
        delay((until - System.currentTimeMillis()).coerceAtLeast(0L))
        player.setPaused(true)
        sleepUntil = null
        notice = Notice(context.getString(R.string.player_sleep_done))
    }

    // UI state for qa/e2e.py (shows up in the debug BrandState line through mpv user-data)
    LaunchedEffect(overlayVisible, panel, locked) {
        player.setString("user-data/shoebill/ui", "controls=${if (overlayVisible) 1 else 0},panel=${panel ?: "none"},locked=${if (locked) 1 else 0}")
    }
    BackHandler(onBack = onBack)
    BackHandler(enabled = panel != null) { panel = null }
    BackHandler(enabled = searchOpen) { searchOpen = false }

    Box(
        Modifier.fillMaxSize().sharedVideo(uri).background(Color.Black)
            .pointerInput(Unit) {
                // any press counts as interaction (keeps the overlay alive), without consuming it
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press) interaction++
                    }
                }
            }
            .playerGestures(
                GestureHost(
                    player, scope, context, window, haptics, settingsState,
                    overlayVisibleState, panelState, searchOpenState, lockedState, hudState, seekPreviewState,
                    ::setBrightness,
                ),
            ),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = player.surfaceCreated(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) =
                            player.surfaceChanged(w, hh)
                        override fun surfaceDestroyed(h: SurfaceHolder) = player.surfaceDestroyed()
                    })
                }
            },
        )

        LaunchedEffect(inPip) { if (inPip) { panel = null; searchOpen = false } }
        if (!inPip) GestureHud(hud)

        val buffering by player.buffering.collectAsStateWithLifecycle()
        var showBuffering by remember { mutableStateOf(false) }
        LaunchedEffect(buffering) { if (buffering) { delay(400); showBuffering = true } else showBuffering = false }
        AnimatedVisibility(showBuffering, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) { BrandLoader() }

        AnimatedVisibility(
            visible = overlayVisible && panel == null && !searchOpen && !inPip,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            if (locked) {
                LockOverlay(onUnlock = { locked = false; overlayVisible = true })
            } else {
                Controls(
                    scope = this,
                    player = player,
                    title = currentTitle,
                    codec = videoCodec,
                    subLabel = subLabel,
                    decoderLabel = decoderLabel,
                    speedLabel = speedText(speed),
                    paused = paused,
                    eof = eof,
                    positionSec = position,
                    durationSec = duration,
                    shownSec = seekPreview ?: position,
                    abA = abA,
                    abB = abB,
                    chapterSecs = chapterSecs,
                    hasPrev = prevFile != null,
                    hasNext = nextFile != null,
                    onBack = onBack,
                    onPanel = { panel = it },
                    onLock = { locked = true; overlayVisible = true },
                    onRotate = {
                        activity.requestedOrientation =
                            if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR
                            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    },
                    onAspect = {
                        val next = nextAspect(player.getString("video-aspect-override"))
                        player.setString("video-aspect-override", next)
                        notice = Notice(if (next == "-1") context.getString(R.string.player_aspect_auto) else next)
                    },
                    onPrev = { prevFile?.let { openFile(it.first, it.second) } },
                    onNext = { nextFile?.let { openFile(it.first, it.second) } },
                    onScrub = { seekPreview = it },
                    onScrubEnd = { sec ->
                        if (sec != null) player.seekTo(sec)
                        seekPreview = null
                    },
                )
            }
        }

        SidePanel(
            panel = panel,
            player = player,
            trackVersion = trackVersion,
            speed = speed,
            volume = volume,
            abA = abA,
            abB = abB,
            chapters = chapters,
            onTracksChanged = { trackVersion++ },
            onAddSub = { subPicker.launch(arrayOf("*/*")) },
            onSearch = { panel = null; searchOpen = true },
            subStyle = settingsState.value.subStyle,
            onSubStyle = { st -> AppSettings.get(context).update { it.copy(subStyle = st) } },
            subFile = extSub,
            subsStartTab = 0,
            onAbA = { abA = player.getDouble("time-pos"); player.setDouble("ab-loop-a", abA ?: 0.0) },
            onAbB = { abB = player.getDouble("time-pos"); player.setDouble("ab-loop-b", abB ?: 0.0) },
            onAbClear = { abA = null; abB = null; player.setString("ab-loop-a", "no"); player.setString("ab-loop-b", "no") },
            onSleep = { min -> sleepMinutes = min; sleepUntil = min?.let { System.currentTimeMillis() + it * 60_000L } },
            sleepMinutes = sleepMinutes.takeIf { sleepUntil != null },
            onShot = {
                val file = File(context.cacheDir, "shot-${System.currentTimeMillis()}.png")
                player.command("screenshot-to-file", file.absolutePath, "video")
                notice = Notice(context.getString(R.string.player_shot_saved))
            },
            onDecoder = { decoderLabel = decoderName(it) },
            onClose = { panel = null },
        )

        SubSearchSheet(
            visible = searchOpen,
            player = player,
            videoName = currentTitle,
            hash = vHash,
            keys = keys,
            hidden = hiddenProviders,
            onHide = { hiddenProviders = hiddenProviders + it },
            onApplied = { file, _ ->
                extSub = file
                extSubTrack = lastSubId()
                trackVersion++
                notice = Notice(context.getString(R.string.sub_added))
            },
            onClose = { searchOpen = false },
        )

        notice?.let { n -> NoticePill(n) { notice = null } }

        if (showEnd) {
            nextFile?.let { (_, name) ->
                EndCard(
                    name = name,
                    countdown = countdown,
                    onPlayNow = { nextFile?.let { openFile(it.first, it.second) } },
                    onCancel = { endDismissed = true },
                )
            }
        }
    }
}
