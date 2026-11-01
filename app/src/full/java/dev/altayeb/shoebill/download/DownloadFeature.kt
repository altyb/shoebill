@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.altayeb.shoebill.download

import dev.altayeb.shoebill.settings.ChoiceRow
import dev.altayeb.shoebill.settings.GroupTitle
import dev.altayeb.shoebill.settings.SettingRow
import dev.altayeb.shoebill.settings.SwitchRow
import dev.altayeb.shoebill.settings.groupShapes
import androidx.compose.foundation.clickable
import dev.altayeb.shoebill.download.logic.Fmt
import dev.altayeb.shoebill.download.logic.PlEntry
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.download.logic.DownloadLogic
import dev.altayeb.shoebill.download.logic.DownloadOptions
import dev.altayeb.shoebill.download.logic.Quality
import dev.altayeb.shoebill.library.VideoIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object SharedLink { val value = MutableStateFlow<String?>(null) }

enum class DlState { Waiting, Running, Finishing, Done, Failed, Cancelled }
data class DlJob(
    val id: String, val url: String, val options: DownloadOptions, val state: DlState = DlState.Waiting, val percent: Int = 0,
    val title: String = "", val error: String = "", val uploader: String = "", val thumb: String? = null,
    val speed: String? = null, val etaSec: Long = -1, val audio: Boolean = false, val fileUri: String? = null,
)
data class Preview(val title: String, val uploader: String, val thumb: String?, val durationSec: Long, val formats: List<Fmt> = emptyList(), val entries: List<PlEntry> = emptyList())

/** Download choices live in Settings; the configure sheet starts from them. Not in the settings backup: the proxy can hold a password. */
class DlPrefs(c: Context) {
    private val p = c.applicationContext.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    var quality: Quality
        get() = runCatching { Quality.valueOf(p.getString("quality", null).orEmpty()) }.getOrDefault(Quality.BEST)
        set(v) = p.edit().putString("quality", v.name).apply()
    var playlist: Boolean get() = p.getBoolean("playlist", false); set(v) = p.edit().putBoolean("playlist", v).apply()
    var subtitles: Boolean get() = p.getBoolean("subtitles", false); set(v) = p.edit().putBoolean("subtitles", v).apply()
    var limit: String? get() = p.getString("limit", null); set(v) = p.edit().putString("limit", v).apply()
    var proxy: String get() = p.getString("proxy", "").orEmpty(); set(v) = p.edit().putString("proxy", v).apply()
    var wifiOnly: Boolean get() = p.getBoolean("wifiOnly", false); set(v) = p.edit().putBoolean("wifiOnly", v).apply()
    var skipSponsors: Boolean get() = p.getBoolean("skipSponsors", false); set(v) = p.edit().putBoolean("skipSponsors", v).apply()
}

/** Fetches what a link is, with the same proxy the download will use, so a blocked network fails here and not later. */
object Probe {
    fun info(c: Context, url: String, proxy: String?): Preview {
        DownloadQueue.ensureReady(c)
        DownloadQueue.refreshSoon(c)
        val req = YoutubeDLRequest(url).addOption("--no-playlist")
        proxy?.let { req.addOption("--proxy", it) }
        val i = YoutubeDL.getInstance().getInfo(req)
        val formats = i.formats.orEmpty().mapNotNull { f ->
            val id = f.formatId ?: return@mapNotNull null
            val v = f.vcodec.orEmpty().let { it.isNotEmpty() && it != "none" }
            val a = f.acodec.orEmpty().let { it.isNotEmpty() && it != "none" }
            Fmt(id, f.ext.orEmpty(), f.height, v, a, f.tbr, f.fileSize.takeIf { it > 0 } ?: f.fileSizeApproximate)
        }
        return Preview(i.title ?: url, i.uploader.orEmpty(), i.thumbnail, (i.duration).toLong(), DownloadLogic.formats(formats))
    }

    /** The videos behind a playlist link, without reading each one. */
    fun playlist(c: Context, url: String, proxy: String?): Preview? {
        DownloadQueue.ensureReady(c)
        DownloadQueue.refreshSoon(c)
        val req = YoutubeDLRequest(url).addOption("--flat-playlist").addOption("--yes-playlist").addOption("-J").addOption("--no-warnings")
        proxy?.let { req.addOption("--proxy", it) }
        val root = org.json.JSONObject(YoutubeDL.getInstance().execute(req).out)
        val list = root.optJSONArray("entries") ?: return null
        val entries = (0 until list.length()).mapNotNull { n ->
            val e = list.optJSONObject(n) ?: return@mapNotNull null
            val u = e.optString("url").takeIf { it.startsWith("http") } ?: e.optString("webpage_url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val t = e.optJSONArray("thumbnails")?.let { a -> a.optJSONObject(a.length() - 1)?.optString("url") }
            PlEntry(u, e.optString("title").ifEmpty { u }, e.optDouble("duration", 0.0).toLong(), t)
        }
        if (entries.size < 2) return null
        return Preview(root.optString("title").ifEmpty { url }, root.optString("uploader"), entries.first().thumb, 0, entries = entries)
    }

    private val cache = android.util.LruCache<String, android.graphics.Bitmap>(24)

    // ponytail: a proxy with a password is skipped, the card just shows no picture; add an Authenticator if people ask.
    fun thumb(url: String, proxy: String?): android.graphics.Bitmap? {
        cache.get(url)?.let { return it }
        if (!url.startsWith("https://")) return null
        return runCatching {
            val p = proxy?.takeIf { it.isNotEmpty() }?.let { java.net.URI(it) }
            if (p?.userInfo != null) return null
            val conn = (if (p == null) java.net.URL(url).openConnection() else java.net.URL(url).openConnection(
                java.net.Proxy(if (p.scheme.startsWith("socks")) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(p.host, p.port))
            )).apply { connectTimeout = 10_000; readTimeout = 15_000 }
            val bytes = conn.getInputStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var n = 0
                while (out.size() < 8 * 1024 * 1024 && input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                out.toByteArray()
            }
            val raw = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val small = if (raw.width > 720) android.graphics.Bitmap.createScaledBitmap(raw, 720, (720f * raw.height / raw.width).toInt().coerceAtLeast(1), true) else raw
            cache.put(url, small); small
        }.onFailure { android.util.Log.w("Download", "thumbnail: ${it.javaClass.simpleName}") }.getOrNull()
    }
}

/** One download at a time, in order. State lives here so the screen can come and go. */
object DownloadQueue {
    val jobs = MutableStateFlow<List<DlJob>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ready = false
    @Volatile private var active: String? = null

    private var historyFile: File? = null

    /** Finished downloads outlive the app. No proxy in here: it can hold a password, and retry reads the current setting. */
    @Synchronized fun loadHistory(c: Context) {
        if (historyFile != null) return
        val f = File(c.filesDir, "download-history.json"); historyFile = f
        val prefs = DlPrefs(c)
        val a = runCatching { org.json.JSONArray(f.readText()) }.getOrNull() ?: return
        val old = (0 until a.length()).mapNotNull { n ->
            val o = a.optJSONObject(n) ?: return@mapNotNull null
            DlJob(o.getString("id"), o.getString("url"), DownloadOptions(prefs.quality, false, false, prefs.limit, prefs.proxy.ifBlank { null }),
                runCatching { DlState.valueOf(o.getString("state")) }.getOrDefault(DlState.Done), 100,
                o.optString("title"), o.optString("error"), o.optString("uploader"), o.optString("thumb").ifEmpty { null },
                audio = o.optBoolean("audio"), fileUri = o.optString("uri").ifEmpty { null })
        }
        jobs.update { old + it }
    }

    private fun saveHistory() {
        val f = historyFile ?: return
        val a = org.json.JSONArray()
        jobs.value.filter { it.state == DlState.Done || it.state == DlState.Failed }.takeLast(200).forEach { j ->
            a.put(org.json.JSONObject().put("id", j.id).put("url", j.url).put("state", j.state.name).put("title", j.title).put("error", j.error)
                .put("uploader", j.uploader).put("thumb", j.thumb.orEmpty()).put("audio", j.audio).put("uri", j.fileUri.orEmpty()))
        }
        runCatching { f.writeText(a.toString()) }
    }

    fun add(c: Context, url: String, o: DownloadOptions, preview: Preview? = null) {
        jobs.update { it + DlJob(UUID.randomUUID().toString(), url, o, title = preview?.title.orEmpty(), uploader = preview?.uploader.orEmpty(), thumb = preview?.thumb) }
        pump(c.applicationContext)
    }

    fun retry(c: Context, j: DlJob) { change(j.id) { it.copy(state = DlState.Waiting, percent = 0, error = "") }; pump(c.applicationContext) }
    fun clearFinished() { jobs.update { l -> l.filter { it.state in setOf(DlState.Waiting, DlState.Running, DlState.Finishing) } }; saveHistory() }

    fun cancel(j: DlJob) {
        if (j.state == DlState.Waiting) change(j.id) { it.copy(state = DlState.Cancelled) }
        else if (active == j.id) { runCatching { YoutubeDL.getInstance().destroyProcessById(j.id) }; change(j.id) { it.copy(state = DlState.Cancelled) } }
    }

    private fun change(id: String, f: (DlJob) -> DlJob) = jobs.update { l -> l.map { if (it.id == id) f(it) else it } }

    @Synchronized private fun pump(c: Context) {
        if (active != null) return
        val next = jobs.value.firstOrNull { it.state == DlState.Waiting } ?: return
        active = next.id
        androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java))
        scope.launch {
            run(c, next)
            synchronized(this@DownloadQueue) { active = null }
            if (jobs.value.none { it.state == DlState.Waiting }) c.stopService(Intent(c, DownloadService::class.java))
            pump(c)
        }
    }

    fun ensureReady(c: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(c)
        FFmpeg.getInstance().init(c)
        ready = true
    }

    /** Sites change often and an old yt-dlp fails with errors like HTTP 403, so it renews itself weekly. No network or no release: carry on with what we have. */
    /** The bundled yt-dlp ages fast and old ones read sites badly, so reading a link also renews it, without making that read wait. */
    fun refreshSoon(c: Context) { scope.launch { runCatching { updateIfStale(c.applicationContext) } } }

    private fun updateIfStale(c: Context) {
        val prefs = c.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("updated", 0L) < 7 * 24 * 3_600_000L) return
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(c, YoutubeDL.UpdateChannel.STABLE) }.onSuccess { prefs.edit().putLong("updated", now).apply() }
    }

    private suspend fun run(c: Context, j: DlJob) {
        val out = File(c.cacheDir, "downloads/${j.id}").apply { mkdirs() }
        try {
            if (DlPrefs(c).wifiOnly && c.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered) error(c.getString(R.string.dl_wifi_blocked))
            ensureReady(c)
            change(j.id) { it.copy(state = DlState.Running) }
            updateIfStale(c)
            val req = YoutubeDLRequest(j.url)
            val args = DownloadLogic.args(j.options, out.absolutePath)
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (i + 1 < args.size && !args[i + 1].startsWith("--") && !(args[i + 1] == "-x" || args[i + 1] == "-o" || args[i + 1] == "-f")) { req.addOption(a, args[i + 1]); i += 2 }
                else { req.addOption(a); i++ }
            }
            YoutubeDL.getInstance().execute(req, j.id) { progress, eta, line ->
                change(j.id) { it.copy(percent = progress.toInt().coerceIn(0, 100), etaSec = eta, speed = DownloadLogic.speed(line) ?: it.speed) }
            }
            if (jobs.value.firstOrNull { it.id == j.id }?.state == DlState.Cancelled) return
            change(j.id) { it.copy(state = DlState.Finishing, percent = 100) }
            val files = out.listFiles().orEmpty().filter { DownloadLogic.isMediaFile(it.name) }
            check(files.isNotEmpty()) { "no file was produced" }
            val saved = files.map { publish(c, it) }
            change(j.id) { it.copy(state = DlState.Done, title = it.title.ifEmpty { files.first().nameWithoutExtension }, audio = DownloadLogic.isAudioFile(files.first().name), fileUri = saved.first()) }
            saveHistory()
            VideoIndex.refresh(c)
        } catch (e: Throwable) { // a broken runtime throws LinkageError; that must fail the job, not kill music and video too
            if (jobs.value.firstOrNull { it.id == j.id }?.state != DlState.Cancelled) {
                val line = e.message.orEmpty().lineSequence().lastOrNull { it.startsWith("ERROR") } ?: e.message.orEmpty().lineSequence().lastOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
                change(j.id) { it.copy(state = DlState.Failed, error = DownloadLogic.scrub(line, j.options.proxy).take(200)) }
                saveHistory()
            }
        } finally {
            out.deleteRecursively()
        }
    }

    /** Pending until fully copied, so a kill mid-copy never leaves half a file in the library. */
    private fun publish(c: Context, f: File): String {
        val audio = DownloadLogic.isAudioFile(f.name)
        val r = c.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val base = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, DownloadLogic.mime(f.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/Shoebill")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = r.insert(base, v) ?: error("could not save the file")
            try {
                r.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri.toString()
            } catch (e: Exception) {
                r.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES), "Shoebill").apply { mkdirs() }
            val to = File(dir, f.name)
            f.copyTo(to, overwrite = true)
            android.media.MediaScannerConnection.scanFile(c, arrayOf(to.path), null, null)
            return android.net.Uri.fromFile(to).toString()
        }
    }
}

/** Keeps the process alive during a download. The notification shows the video itself, speed, time left and a cancel button. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onBind(intent: Intent?): IBinder? = null

    private fun openTab(): android.app.PendingIntent = android.app.PendingIntent.getActivity(
        this, 0, Intent(Intent.ACTION_VIEW, android.net.Uri.parse("shoebill://download"), this, dev.altayeb.shoebill.MainActivity::class.java),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun note(j: DlJob?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(j?.title?.ifEmpty { null } ?: getString(R.string.dl_notify)).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setContentIntent(openTab())
            .setProgress(100, j?.percent ?: 0, j == null || j.percent == 0)
        if (j != null) {
            b.setContentText(listOfNotNull("${j.percent}%", j.speed, j.etaSec.takeIf { it > 0 }?.let { getString(R.string.dl_left, DownloadLogic.time(it)) }).joinToString(" · "))
            b.addAction(0, getString(R.string.dl_cancel), android.app.PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            j.thumb?.let { u -> thumbs[u]?.let { b.setLargeIcon(it) } }
        }
        return b.build()
    }
    private val thumbs = HashMap<String, android.graphics.Bitmap?>()

    private fun finished(j: DlJob) {
        if (!notified.add(j.id)) return
        val failed = j.state == DlState.Failed
        getSystemService(NotificationManager::class.java).notify(j.id.hashCode(), NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(if (failed) R.string.dl_notify_failed else R.string.dl_notify_done))
            .setContentText(j.title.ifEmpty { j.url }).setAutoCancel(true).setContentIntent(openTab()).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, note(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, note(null))
        if (intent?.action == ACTION_CANCEL) { DownloadQueue.jobs.value.firstOrNull { it.state == DlState.Running }?.let { DownloadQueue.cancel(it) }; return START_NOT_STICKY }
        if (collecting) return START_NOT_STICKY
        collecting = true
        scope.launch {
            DownloadQueue.jobs.collect { l ->
                l.filter { it.state == DlState.Done || it.state == DlState.Failed }.forEach { finished(it) }
                val j = l.firstOrNull { it.state == DlState.Running } ?: return@collect
                val u = j.thumb
                if (u != null && u !in thumbs) {
                    thumbs[u] = null
                    launch(Dispatchers.IO) { val b = Probe.thumb(u, j.options.proxy); withContext(Dispatchers.Main) { thumbs[u] = b } }
                }
                getSystemService(NotificationManager::class.java).notify(ID, note(j))
            }
        }
        return START_NOT_STICKY
    }
    private var collecting = false

    override fun onDestroy() {
        // The queue stops this service right after the last job ends, often before the collector above sees it.
        DownloadQueue.jobs.value.filter { it.state == DlState.Done || it.state == DlState.Failed }.forEach { finished(it) }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy()
    }
    private companion object { const val CHANNEL = "downloads"; const val ID = 4101; const val ACTION_CANCEL = "cancel"; val notified = java.util.Collections.synchronizedSet(HashSet<String>()) }
}

object DownloadFeature {
    const val ENABLED = true

    /** A link from outside: "Share to", an opened video-site address, or shoebill://download?url=. It only ever opens the configure sheet; nothing starts without a tap. */
    fun linkFrom(i: Intent?): String? {
        if (i?.action == Intent.ACTION_SEND) return i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (i?.action != Intent.ACTION_VIEW) return null
        val u = i.data ?: return null
        return when {
            u.scheme == "shoebill" && u.host == "download" -> u.getQueryParameter("url").orEmpty()
            (u.scheme == "https" || u.scheme == "http") && i.type == null -> u.toString()
            else -> null
        }
    }

    @Composable
    fun Tab(modifier: Modifier) {
        val ctx = LocalContext.current
        val shared by SharedLink.value.collectAsStateWithLifecycle()
        var url by remember { mutableStateOf("") }
        var bad by remember { mutableStateOf(false) }
        var sheetUrl by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(shared) {
            val s = shared ?: return@LaunchedEffect
            DownloadLogic.firstUrl(s)?.let { url = it; bad = false; sheetUrl = it }
            SharedLink.value.value = null
        }
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { DownloadQueue.loadHistory(ctx) } }
        val jobs by DownloadQueue.jobs.collectAsStateWithLifecycle()
        var kind by remember { mutableStateOf(0) } // 0 all, 1 video, 2 audio
        val notify = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
        val cs = MaterialTheme.colorScheme
        val hero = jobs.firstOrNull { it.state == DlState.Running || it.state == DlState.Finishing }
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "hero") {
                Column(Modifier.animateContentSize()) {
                    if (hero != null) { HeroCard(hero); Spacer(Modifier.height(12.dp)) }
                    OutlinedTextField(url, { url = it; bad = false }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.dl_url)) },
                        isError = bad, supportingText = if (bad) { { Text(stringResource(R.string.dl_bad_url)) } } else null,
                        trailingIcon = { TextButton(onClick = {
                            ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.let { url = DownloadLogic.firstUrl(it) ?: it; bad = false }
                        }) { Text(stringResource(R.string.dl_paste)) } })
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val u = DownloadLogic.firstUrl(url)
                        if (u == null) bad = true else {
                            if (Build.VERSION.SDK_INT >= 33) notify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            sheetUrl = u
                        }
                    }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.dl_start)) }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.dl_rights), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    val others = jobs.filter { it !== hero }
                    if (others.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.dl_queue), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { DownloadQueue.clearFinished() }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text(stringResource(R.string.dl_clear)) }
                    }
                    if (others.any { it.state == DlState.Done }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(R.string.dl_all, R.string.dl_video, R.string.dl_audio).forEachIndexed { i, l -> FilterChip(kind == i, { kind = i }, { Text(stringResource(l)) }) }
                    }
                }
            }
            items(jobs.filter { it !== hero && (kind == 0 || (it.state == DlState.Done && it.audio == (kind == 2))) }.reversed(), key = { it.id }) { j -> JobCard(j, Modifier.animateItem()) }
            if (jobs.isEmpty()) item(key = "none") { dev.altayeb.shoebill.ui.EmptyState(R.drawable.art_download, stringResource(R.string.dl_none), Modifier.height(320.dp)) }
        }
        sheetUrl?.let { u -> ConfigureSheet(u, { sheetUrl = null }) { url = "" } }
    }

    /** Before anything downloads: what this link is, and how to save it. Starts from the Settings defaults. */
    @Composable
    private fun ConfigureSheet(url: String, close: () -> Unit, started: () -> Unit) {
        val ctx = LocalContext.current
        val prefs = remember { DlPrefs(ctx) }
        val proxy = remember { prefs.proxy.takeIf { it.isNotBlank() }?.trim() }
        var quality by remember { mutableStateOf(prefs.quality) }
        var playlist by remember { mutableStateOf(prefs.playlist) }
        var subs by remember { mutableStateOf(prefs.subtitles) }
        var info by remember { mutableStateOf<Preview?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var format by remember { mutableStateOf<Fmt?>(null) }
        var showFormats by remember { mutableStateOf(false) }
        val unpicked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
        LaunchedEffect(url) {
            withContext(Dispatchers.IO) { runCatching {
                (if (DownloadLogic.looksLikePlaylist(url)) runCatching { Probe.playlist(ctx, url, proxy) }.getOrNull() else null) ?: Probe.info(ctx, url, proxy)
            } }
                .onSuccess { info = it }
                .onFailure { e -> error = DownloadLogic.scrub(e.message.orEmpty().lineSequence().lastOrNull { it.startsWith("ERROR") } ?: ctx.getString(R.string.dl_no_preview), proxy).take(200) }
        }
        ModalBottomSheet(onDismissRequest = close) {
            val i = info
            val entries = i?.entries.orEmpty()
            // The buttons stay in reach however long the format or playlist list gets.
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    i != null -> {
                        if (i.thumb != null) Box {
                            Thumb(i.thumb, proxy, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(dev.altayeb.shoebill.ui.theme.Radius.M))
                            if (i.durationSec > 0) Text(DownloadLogic.time(i.durationSec), Modifier.align(Alignment.BottomEnd).padding(8.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .7f), dev.altayeb.shoebill.ui.theme.Radius.XS).padding(horizontal = 8.dp, vertical = 2.dp), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(i.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (i.uploader.isNotEmpty()) Text(i.uploader, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.LoadingIndicator(Modifier.size(32.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.dl_reading))
                    }
                }
                if (entries.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.dl_pick_count, entries.size - unpicked.size, entries.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { if (unpicked.isEmpty()) unpicked.addAll(entries.map { it.url }) else unpicked.clear() }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                            Text(stringResource(if (unpicked.isEmpty()) R.string.dl_select_none else R.string.dl_select_all))
                        }
                    }
                    // Lazy, so a 250-video playlist fetches only the thumbnails on screen.
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) { items(entries, key = { it.url }) { e ->
                        Row(Modifier.fillMaxWidth().clickable { if (!unpicked.remove(e.url)) unpicked.add(e.url) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(e.url !in unpicked, null, Modifier.padding(end = 12.dp).padding(vertical = 12.dp))
                            Thumb(e.thumb, proxy, Modifier.width(80.dp).aspectRatio(16f / 9f).clip(dev.altayeb.shoebill.ui.theme.Radius.XS))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                if (e.durationSec > 0) Text(DownloadLogic.time(e.durationSec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } }
                }
                Text(stringResource(R.string.dl_quality), style = MaterialTheme.typography.titleSmall)
                QualityChips(quality) { quality = it; format = null }
                val fmts = i?.formats.orEmpty()
                if (fmts.isNotEmpty()) {
                    TextButton(onClick = { showFormats = !showFormats }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text(stringResource(R.string.dl_all_formats, fmts.size)) }
                    if (showFormats) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fmts.forEach { f -> FilterChip(format == f, { format = f.takeIf { format != it } }, { Text(DownloadLogic.formatLabel(f)) }) }
                    }
                }
                if (entries.isEmpty()) Row(Modifier.offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(playlist, { playlist = it }); Text(stringResource(R.string.dl_playlist)) }
                if (quality.audio == null) Row(Modifier.offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(subs, { subs = it }); Text(stringResource(R.string.dl_subs)) }
            }
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = close, Modifier.weight(1f)) { Text(stringResource(R.string.dl_cancel)) }
                    Button(onClick = {
                        val base = DownloadOptions(quality, playlist, subs, prefs.limit, proxy, format?.id, format?.audioOnly == true, prefs.skipSponsors)
                        if (entries.isEmpty()) DownloadQueue.add(ctx, url, base, info)
                        else entries.filter { it.url !in unpicked }.forEach { e ->
                            DownloadQueue.add(ctx, e.url, base.copy(playlist = false), Preview(e.title, i?.uploader.orEmpty(), e.thumb, e.durationSec))
                        }
                        started(); close()
                    }, Modifier.weight(1f), enabled = entries.isEmpty() || unpicked.size < entries.size) { Text(stringResource(R.string.dl_start)) }
                }
        }
    }

    @Composable
    private fun QualityChips(selected: Quality, pick: (Quality) -> Unit) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q ->
                FilterChip(selected == q, { pick(q) }, { Text(when (q) {
                    Quality.BEST -> stringResource(R.string.dl_best)
                    Quality.M4A -> stringResource(R.string.dl_m4a)
                    Quality.MP3 -> stringResource(R.string.dl_mp3)
                    else -> "${q.height}p"
                }) })
            }
        }
    }

    @Composable
    private fun Thumb(url: String?, proxy: String?, modifier: Modifier) {
        val pic by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, url) {
            value = url?.let { withContext(Dispatchers.IO) { Probe.thumb(it, proxy) } }
        }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            pic?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
    }

    /** The download in progress, large, with the same picture as the sheet before it. */
    @Composable
    private fun HeroCard(j: DlJob) {
        Surface(shape = dev.altayeb.shoebill.ui.theme.Radius.L, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                if (j.thumb != null) Thumb(j.thumb, j.options.proxy, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                androidx.compose.material3.LinearWavyProgressIndicator(progress = { j.percent / 100f }, Modifier.fillMaxWidth())
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(j.title.ifEmpty { j.url }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(status(j), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { DownloadQueue.cancel(j) }) { Text(stringResource(R.string.dl_cancel)) }
                }
            }
        }
    }

    @Composable
    private fun status(j: DlJob): String = when (j.state) {
        DlState.Waiting -> stringResource(R.string.dl_waiting)
        DlState.Running -> listOfNotNull(stringResource(R.string.dl_running, j.percent), j.speed, j.etaSec.takeIf { it > 0 }?.let { stringResource(R.string.dl_left, DownloadLogic.time(it)) }).joinToString(" · ")
        DlState.Finishing -> stringResource(R.string.dl_finishing)
        DlState.Done -> stringResource(R.string.dl_done)
        DlState.Failed -> stringResource(R.string.dl_failed, j.error)
        DlState.Cancelled -> stringResource(R.string.dl_cancelled)
    }

    @Composable
    private fun JobCard(j: DlJob, modifier: Modifier) {
        val ctx = LocalContext.current
        val open: () -> Unit = {
            runCatching {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(j.fileUri), if (j.audio) "audio/*" else "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            }
        }
        Surface(modifier.fillMaxWidth().then(if (j.state == DlState.Done && j.fileUri != null) Modifier.clickable(onClick = open) else Modifier), shape = dev.altayeb.shoebill.ui.theme.Radius.M, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(j.thumb, j.options.proxy, Modifier.width(112.dp).aspectRatio(16f / 9f).clip(dev.altayeb.shoebill.ui.theme.Radius.S))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(j.title.ifEmpty { j.url }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(status(j), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = if (j.state == DlState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (j.state) {
                    DlState.Waiting -> TextButton(onClick = { DownloadQueue.cancel(j) }) { Text(stringResource(R.string.dl_cancel)) }
                    DlState.Failed, DlState.Cancelled -> TextButton(onClick = { DownloadQueue.retry(ctx, j) }) { Text(stringResource(R.string.dl_retry)) }
                    else -> Unit
                }
            }
        }
    }

    /** Everything that is a standing choice, not part of one download. */
    @Composable
    fun SettingsSection() {
        val ctx = LocalContext.current
        val prefs = remember { DlPrefs(ctx) }
        val scope = rememberCoroutineScope()
        var quality by remember { mutableStateOf(prefs.quality) }
        var playlist by remember { mutableStateOf(prefs.playlist) }
        var subs by remember { mutableStateOf(prefs.subtitles) }
        var limit by remember { mutableStateOf(prefs.limit) }
        var proxy by remember { mutableStateOf(prefs.proxy) }
        var wifi by remember { mutableStateOf(prefs.wifiOnly) }
        var sponsors by remember { mutableStateOf(prefs.skipSponsors) }
        var message by remember { mutableStateOf<String?>(null) }
        var updating by remember { mutableStateOf(false) }
        val col = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)

        GroupTitle(stringResource(R.string.dl_group_defaults))
        val d = groupShapes(4)
        Column(verticalArrangement = col) {
            ChoiceRow(stringResource(R.string.dl_quality), d[0], Quality.entries.map { q -> q to when (q) {
                Quality.BEST -> stringResource(R.string.dl_best)
                Quality.M4A -> stringResource(R.string.dl_m4a)
                Quality.MP3 -> stringResource(R.string.dl_mp3)
                else -> "${q.height}p"
            } }, quality) { quality = it; prefs.quality = it }
            SwitchRow(stringResource(R.string.dl_playlist), d[1], playlist) { playlist = it; prefs.playlist = it }
            SwitchRow(stringResource(R.string.dl_subs), d[2], subs) { subs = it; prefs.subtitles = it }
            SwitchRow(stringResource(R.string.dl_sponsors), d[3], sponsors, stringResource(R.string.dl_sponsors_sub)) { sponsors = it; prefs.skipSponsors = it }
        }

        GroupTitle(stringResource(R.string.dl_group_network))
        val n = groupShapes(3)
        Column(verticalArrangement = col) {
            SwitchRow(stringResource(R.string.dl_wifi), n[0], wifi, stringResource(R.string.dl_wifi_sub)) { wifi = it; prefs.wifiOnly = it }
            ChoiceRow(stringResource(R.string.dl_limit), n[1], listOf<Pair<String?, String>>(null to stringResource(R.string.dl_no_limit)) + DownloadLogic.LIMITS.map { it to it + "B/s" }, limit) { limit = it; prefs.limit = it }
            Surface(Modifier.fillMaxWidth(), shape = n[2], color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                val ok = DownloadLogic.validProxy(proxy)
                OutlinedTextField(proxy, { proxy = it.take(300); if (DownloadLogic.validProxy(it)) prefs.proxy = it.trim() }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true,
                    label = { Text(stringResource(R.string.dl_proxy)) }, isError = !ok,
                    supportingText = { Text(stringResource(if (ok) R.string.dl_proxy_hint else R.string.dl_bad_proxy)) })
            }
        }

        GroupTitle(stringResource(R.string.dl_group_tools))
        val t = groupShapes(if (Build.VERSION.SDK_INT >= 31) 2 else 1)
        Column(verticalArrangement = col) {
            if (Build.VERSION.SDK_INT >= 31) SettingRow(stringResource(R.string.dl_open_links), t[0], onClick = {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
            })
            SettingRow(stringResource(R.string.dl_update), t.last(), subtitle = message, onClick = {
                if (!updating) {
                    updating = true; message = null
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            runCatching {
                                DownloadQueue.ensureReady(ctx)
                                val st = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                                if (st == YoutubeDL.UpdateStatus.DONE) ctx.getString(R.string.dl_updated, YoutubeDL.getInstance().version(ctx).orEmpty())
                                else ctx.getString(R.string.dl_up_to_date)
                            }.getOrElse { ctx.getString(R.string.dl_update_failed) }
                        }
                        updating = false
                    }
                }
            })
        }
    }
}
