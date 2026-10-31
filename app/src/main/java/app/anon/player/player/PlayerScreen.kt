package app.anon.player.player

import app.anon.player.R
import app.anon.player.ui.SecureWindow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.anon.player.ui.sharedVideo
import app.anon.player.ui.BrandLoader
import kotlin.math.roundToInt
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.anon.player.data.FileState
import app.anon.player.data.PlaybackStore
import app.anon.player.library.formatTime
import app.anon.player.settings.AppSettings
import app.anon.player.settings.Decoder
import app.anon.player.subs.SubKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ln

private val ASPECTS = listOf("-1", "16:9", "4:3", "2.35:1")

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
    var overlayVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var locked by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf<Double?>(null) }
    var hud by remember { mutableStateOf<Hud?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var videoCodec by remember { mutableStateOf<String?>(null) }
    var subLabel by remember { mutableStateOf(context.getString(R.string.off)) }
    var decoderLabel by remember { mutableStateOf("HW+") }
    var abA by remember { mutableStateOf<Double?>(null) }
    var abB by remember { mutableStateOf<Double?>(null) }
    var sleepUntil by remember { mutableStateOf<Long?>(null) }
    var prevFile by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var nextFile by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var endDismissed by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(5) }
    var trackVersion by remember { mutableIntStateOf(0) }
    // online search and sync tool (ش-21, ش-22); per-file state resets with the file
    var searchOpen by remember { mutableStateOf(false) }
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
            val name = cr.query(picked, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
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
        player.setString("start", if (resume) "%.3f".format(java.util.Locale.ROOT, fs!!.positionMs / 1000.0) else "none")
        player.shareableUri = if (private) null else u
        player.onOpenFailed = { notice = Notice(context.getString(R.string.player_open_failed)) }
        player.shareableTitle = currentTitle
        if (!player.load(u)) notice = Notice(context.getString(R.string.player_open_failed))
    }

    fun openFile(u: Uri, t: String) {
        saveState()
        notice = null; endDismissed = false; abA = null; abB = null; videoCodec = null
        player.setString("ab-loop-a", "no")
        player.setString("ab-loop-b", "no")
        current = u
        currentTitle = t
        loadAt(u)
    }

    fun setBrightness(v: Float) {
        window.attributes = window.attributes.also { it.screenBrightness = v }
    }

    DisposableEffect(player) {
        onDispose { saveState(); player.destroy() }
    }
    LaunchedEffect(player) {
        loadAt(uri)
        while (true) { delay(5000); saveState() }
    }

    DisposableEffect(Unit) {
        val oldOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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

    // a new file: restore its per-file state (ق-12), resume if unfinished (ش-07), fall back to SW if HW fails (ق-08)
    // Off the UI thread: every mpv read waits on mpv's core lock, and a stuck hardware decoder holds it (seen as an ANR).
    LaunchedEffect(loads) { withContext(Dispatchers.IO) {
        if (loads == 0) return@withContext
        val fs = store.get(current.toString())
        player.setString("hwdec", fs?.hwdec ?: settingsState.value.decoder.hwdec)
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
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            File(context.filesDir, "qa_keep_ui").exists()
    }
    // overlay auto-hide: 3 s without touch, never while a panel is open, the finger is on the seek bar, or at the end
    LaunchedEffect(overlayVisible, interaction, panel, searchOpen, seekPreview, eof) {
        if (!qaKeepUi && overlayVisible && panel == null && !searchOpen && seekPreview == null && !eof) {
            delay(3000)
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
        player.setString("user-data/anon/ui", "controls=${if (overlayVisible) 1 else 0},panel=${panel ?: "none"},locked=${if (locked) 1 else 0}")
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
            .pointerInput(Unit) {
                var lastTapAt = 0L
                var lastTapPos = Offset.Zero
                var tapToken = 0
                var hudToken = 0
                var seekAcc = 0
                var seekSign = 0
                var seekAt = 0L
                val slop = 12.dp.toPx()

                fun clearHudLater() {
                    val token = ++hudToken
                    scope.launch { delay(700); if (hudToken == token) hud = null }
                }

                fun showHud(h: Hud) {
                    hud = h
                    clearHudLater()
                }

                fun tapSeek(sign: Int) {
                    val now = System.currentTimeMillis()
                    seekAcc = if (now - seekAt < 600 && sign == seekSign) seekAcc + 10 else 10
                    seekSign = sign
                    seekAt = now
                    player.seekBy(sign * 10.0)
                    showHud(Hud(HudKind.Tap, label = (if (sign > 0) "+" else "−") + context.getString(R.string.player_seconds, seekAcc.toString())))
                }

                awaitEachGesture {
                    val w = this@pointerInput.size.width.toFloat()
                    val h = this@pointerInput.size.height.toFloat()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val y0 = down.position.y
                    val onChrome = overlayVisible && (y0 < 96.dp.toPx() || y0 > h - 170.dp.toPx())
                    if (down.isConsumed || onChrome || panel != null || searchOpen) return@awaitEachGesture

                    val gesturesOn = settingsState.value.gestures && !locked
                    // ponytail: subtitle band estimated from sub-pos as a share of the view height; exact only when the
                    // video fills the height (landscape). Use mpv's sub bounding box if portrait drags feel off.
                    val subPos0 = player.getString("sub-pos")?.toDoubleOrNull() ?: 100.0
                    val subBottom = h * (subPos0 / 100.0).toFloat()
                    val onSub = gesturesOn && !player.getString("sub-text").isNullOrBlank() &&
                        down.position.x in w * 0.15f..w * 0.85f && y0 in subBottom - h * 0.22f..subBottom + h * 0.05f
                    var axis = Axis.None
                    var multi = false
                    var pinch0 = 0f
                    var zoom0 = 0.0
                    var startSec = 0.0
                    var startBright = 0f
                    var startVol = 0.0
                    var lastVol = 0.0
                    var totalX = 0f
                    var totalY = 0f
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            multi = true
                            if (gesturesOn) {
                                val dist = (pressed[0].position - pressed[1].position).getDistance()
                                if (pinch0 == 0f) {
                                    pinch0 = dist
                                    zoom0 = player.getDouble(if (onSub) "sub-scale" else "video-zoom") ?: if (onSub) 1.0 else 0.0
                                } else if (dist > 0f && onSub) {
                                    // pinch that starts on the subtitle line resizes the subtitle, not the video
                                    val sc = (zoom0 * dist / pinch0).coerceIn(0.6, 2.0)
                                    player.setDouble("sub-scale", sc)
                                    hud = Hud(HudKind.Tap, label = context.getString(R.string.sub_size_hud, (sc * 100).roundToInt().toString()))
                                } else if (dist > 0f) {
                                    player.setDouble("video-zoom", zoom0 + ln(dist / pinch0) / ln(2.0))
                                }
                                pressed.forEach { it.consume() }
                            }
                        } else if (pressed.size == 1 && !multi) {
                            val c = pressed[0]
                            totalX = c.position.x - down.position.x
                            totalY = c.position.y - down.position.y
                            if (axis == Axis.None && (abs(totalX) > slop || abs(totalY) > slop)) {
                                axis = when {
                                    !gesturesOn -> Axis.Blocked
                                    onSub && abs(totalY) > abs(totalX) -> Axis.Subtitle
                                    abs(totalY) > abs(totalX) -> Axis.Vertical
                                    else -> Axis.Horizontal
                                }
                                if (axis == Axis.Vertical) {
                                    if (down.position.x < w / 2) {
                                        startBright = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                                    } else {
                                        startVol = player.volume.value
                                        lastVol = startVol
                                    }
                                } else if (axis == Axis.Horizontal) {
                                    startSec = player.position.value
                                }
                            }
                            when (axis) {
                                Axis.Subtitle -> {
                                    val pos = (subPos0 + totalY / h * 100.0).roundToInt().coerceIn(0, 100)
                                    player.setString("sub-pos", pos.toString())
                                    hud = Hud(HudKind.Tap, label = context.getString(R.string.sub_position_hud, pos.toString()))
                                    c.consume()
                                }
                                Axis.Vertical -> {
                                    val frac = -totalY / h
                                    if (down.position.x < w / 2) {
                                        val b = (startBright + frac).coerceIn(0.01f, 1f)
                                        setBrightness(b)
                                        hud = Hud(HudKind.Brightness, b, "\u2066${(b * 100).toInt()}%\u2069")
                                    } else {
                                        val v = (startVol + frac * 200.0).coerceIn(0.0, 200.0)
                                        if ((lastVol < 100.0) != (v < 100.0)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        lastVol = v
                                        player.setVolume(v)
                                        hud = Hud(HudKind.Volume, (v / 200.0).toFloat(), "\u2066${v.toInt()}%\u2069")
                                    }
                                    c.consume()
                                }
                                Axis.Horizontal -> {
                                    val dur = player.duration.value
                                    val target = (startSec + totalX / w * 180.0).coerceIn(0.0, dur.coerceAtLeast(0.0))
                                    seekPreview = target
                                    val d = target - startSec
                                    hud = Hud(
                                        HudKind.Seek,
                                        label = (if (d >= 0) "+" else "−") + formatTime((abs(d) * 1000).toLong()) +
                                            " / " + formatTime((target * 1000).toLong()),
                                    )
                                    c.consume()
                                }
                                else -> {}
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (axis == Axis.Horizontal) {
                        seekPreview?.let { player.seekTo(it) }
                        seekPreview = null
                    }
                    if (multi && onSub && pinch0 > 0f) {
                        val sc = player.getDouble("sub-scale")
                        if (sc != null) AppSettings.get(context).update { it.copy(subStyle = it.subStyle.copy(scale = sc.toFloat())) }
                        clearHudLater()
                    } else if (axis == Axis.Subtitle) {
                        val pos = player.getString("sub-pos")?.toIntOrNull()
                        if (pos != null) AppSettings.get(context).update { it.copy(subStyle = it.subStyle.copy(pos = pos)) }
                        clearHudLater()
                    } else if (axis == Axis.Vertical || axis == Axis.Horizontal) {
                        clearHudLater()
                    } else if (axis == Axis.None && !multi) {
                        val now = System.currentTimeMillis()
                        val isDouble = now - lastTapAt < 300 && (down.position - lastTapPos).getDistance() < 80.dp.toPx()
                        if (isDouble) {
                            lastTapAt = 0L
                            tapToken++
                            if (gesturesOn) {
                                val f = down.position.x / w
                                when {
                                    f < 1f / 3f -> tapSeek(-1)
                                    f > 2f / 3f -> tapSeek(1)
                                    else -> if (player.eof.value) { player.seekTo(0.0); player.setPaused(false) } else player.togglePause()
                                }
                            }
                        } else {
                            lastTapAt = now
                            lastTapPos = down.position
                            val token = ++tapToken
                            scope.launch {
                                delay(300)
                                if (tapToken == token && panel == null) overlayVisible = !overlayVisible
                            }
                        }
                    }
                }
            },
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
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
            if (locked) {
                LockOverlay(onUnlock = { locked = false; overlayVisible = true })
            } else {
                Controls(
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
                        val cur = player.getString("video-aspect-override") ?: "-1"
                        val idx = ASPECTS.indexOf(cur)
                        val next = ASPECTS[(idx + 1) % ASPECTS.size]
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
            onSleep = { min -> sleepUntil = min?.let { System.currentTimeMillis() + it * 60_000L } },
            onShot = {
                val file = java.io.File(context.cacheDir, "shot-${System.currentTimeMillis()}.png")
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

private enum class Axis { None, Vertical, Horizontal, Blocked, Subtitle }
