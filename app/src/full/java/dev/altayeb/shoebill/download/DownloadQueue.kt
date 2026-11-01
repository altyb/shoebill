@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

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
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.ContextCompat
import dev.altayeb.shoebill.ui.EmptyState
import dev.altayeb.shoebill.ui.theme.Radius
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api

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
    var sponsorCategories: String get() = p.getString("sponsorCats", "default") ?: "default"; set(v) = p.edit().putString("sponsorCats", v).apply()
    var container: String get() = p.getString("container", "mkv") ?: "mkv"; set(v) = p.edit().putString("container", v).apply()
    var codec: String? get() = p.getString("codec", null); set(v) = p.edit().putString("codec", v).apply()
    var filename: Int get() = p.getInt("filename", 0); set(v) = p.edit().putInt("filename", v).apply()
    var restrictNames: Boolean get() = p.getBoolean("restrictNames", false); set(v) = p.edit().putBoolean("restrictNames", v).apply()
    var embedThumbnail: Boolean get() = p.getBoolean("embedThumb", true); set(v) = p.edit().putBoolean("embedThumb", v).apply()
    var embedMetadata: Boolean get() = p.getBoolean("embedMeta", true); set(v) = p.edit().putBoolean("embedMeta", v).apply()
    var subLangs: String get() = p.getString("subLangs", "ar.*,en.*") ?: "ar.*,en.*"; set(v) = p.edit().putString("subLangs", v).apply()
    var autoSubs: Boolean get() = p.getBoolean("autoSubs", true); set(v) = p.edit().putBoolean("autoSubs", v).apply()
    var forceIpv4: Boolean get() = p.getBoolean("ipv4", false); set(v) = p.edit().putBoolean("ipv4", v).apply()
    var threads: Int get() = p.getInt("threads", 1); set(v) = p.edit().putInt("threads", v).apply()
    var noDuplicates: Boolean get() = p.getBoolean("noDup", false); set(v) = p.edit().putBoolean("noDup", v).apply()
    var incognito: Boolean get() = p.getBoolean("incognito", false); set(v) = p.edit().putBoolean("incognito", v).apply()
    var extra: String get() = p.getString("extra", "").orEmpty(); set(v) = p.edit().putString("extra", v).apply()
    var playlistFolder: Boolean get() = p.getBoolean("playlistFolder", false); set(v) = p.edit().putBoolean("playlistFolder", v).apply()
    var thumbFile: Boolean get() = p.getBoolean("thumbFile", false); set(v) = p.edit().putBoolean("thumbFile", v).apply()
    var subFiles: Boolean get() = p.getBoolean("subFiles", false); set(v) = p.edit().putBoolean("subFiles", v).apply()
    var autoUpdate: Boolean get() = p.getBoolean("autoUpdate", true); set(v) = p.edit().putBoolean("autoUpdate", v).apply()
    var nightly: Boolean get() = p.getBoolean("nightly", false); set(v) = p.edit().putBoolean("nightly", v).apply()
    private val dir = c.applicationContext.filesDir
    val cookiesFile get() = File(dir, "cookies.txt")
    val archiveFile get() = File(dir, "download-archive.txt")

    /** One download's options: this sheet's choices on top of the standing ones. */
    fun options(quality: Quality, playlist: Boolean, subs: Boolean, formatId: String? = null, formatAudioOnly: Boolean = false) = DownloadOptions(
        quality, playlist, subs, limit, proxy.takeIf { it.isNotBlank() }?.trim(), formatId, formatAudioOnly, skipSponsors, sponsorCategories, container, codec, filename,
        restrictNames, embedThumbnail, embedMetadata, subLangs, autoSubs, forceIpv4, threads, cookiesFile.takeIf { it.exists() }?.path,
        archiveFile.path.takeIf { noDuplicates }, DownloadLogic.parseExtra(extra).orEmpty(),
        playlistFolder = playlistFolder, thumbnailFile = thumbFile, subtitleFiles = subFiles,
    )
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

    private val cache = LruCache<String, Bitmap>(24)

    // ponytail: a proxy with a password is skipped, the card just shows no picture; add an Authenticator if people ask.
    fun thumb(url: String, proxy: String?): Bitmap? {
        cache.get(url)?.let { return it }
        if (!url.startsWith("https://")) return null
        return runCatching {
            val p = proxy?.takeIf { it.isNotEmpty() }?.let { URI(it) }
            if (p?.userInfo != null) return null
            val conn = (if (p == null) URL(url).openConnection() else URL(url).openConnection(
                Proxy(if (p.scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(p.host, p.port))
            )).apply { connectTimeout = 10_000; readTimeout = 15_000 }
            val bytes = conn.getInputStream().use { input ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var n = 0
                while (out.size() < 8 * 1024 * 1024 && input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                out.toByteArray()
            }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val small = if (raw.width > 720) Bitmap.createScaledBitmap(raw, 720, (720f * raw.height / raw.width).toInt().coerceAtLeast(1), true) else raw
            cache.put(url, small); small
        }.onFailure { Log.w("Download", "thumbnail: ${it.javaClass.simpleName}") }.getOrNull()
    }
}

/** One download at a time, in order. State lives here so the screen can come and go. */
object DownloadQueue {
    val jobs = MutableStateFlow<List<DlJob>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ready = false
    @Volatile private var active: String? = null

    private var historyFile: File? = null
    @Volatile private var incognito = false

    /** Finished downloads outlive the app. No proxy in here: it can hold a password, and retry reads the current setting. */
    @Synchronized fun loadHistory(c: Context) {
        if (historyFile != null) return
        val f = File(c.filesDir, "download-history.json"); historyFile = f
        val prefs = DlPrefs(c)
        incognito = prefs.incognito
        val a = runCatching { org.json.JSONArray(f.readText()) }.getOrNull() ?: return
        val old = (0 until a.length()).mapNotNull { n ->
            val o = a.optJSONObject(n) ?: return@mapNotNull null
            DlJob(o.getString("id"), o.getString("url"), prefs.options(prefs.quality, false, false),
                runCatching { DlState.valueOf(o.getString("state")) }.getOrDefault(DlState.Done), 100,
                o.optString("title"), o.optString("error"), o.optString("uploader"), o.optString("thumb").ifEmpty { null },
                audio = o.optBoolean("audio"), fileUri = o.optString("uri").ifEmpty { null })
        }
        jobs.update { old + it }
    }

    private fun saveHistory() {
        val f = historyFile ?: return
        if (incognito) return
        val a = org.json.JSONArray()
        jobs.value.filter { it.state == DlState.Done || it.state == DlState.Failed }.takeLast(200).forEach { j ->
            a.put(org.json.JSONObject().put("id", j.id).put("url", j.url).put("state", j.state.name).put("title", j.title).put("error", j.error)
                .put("uploader", j.uploader).put("thumb", j.thumb.orEmpty()).put("audio", j.audio).put("uri", j.fileUri.orEmpty()))
        }
        runCatching { f.writeText(a.toString()) }
    }

    fun add(c: Context, url: String, o: DownloadOptions, preview: Preview? = null) {
        incognito = DlPrefs(c).incognito
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
        ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java))
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
        if (!DlPrefs(c).autoUpdate) return
        val prefs = c.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("updated", 0L) < 7 * 24 * 3_600_000L) return
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(c, if (DlPrefs(c).nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE) }.onSuccess { prefs.edit().putLong("updated", now).apply() }
    }

    private suspend fun run(c: Context, j: DlJob) {
        val out = File(c.cacheDir, "downloads/${j.id}").apply { mkdirs() }
        try {
            if (DlPrefs(c).wifiOnly && c.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) error(c.getString(R.string.dl_wifi_blocked))
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
            val all = out.walkTopDown().filter { it.isFile }.toList()
            val media = all.filter { DownloadLogic.isMediaFile(it.name) }
            check(media.isNotEmpty()) { if (j.options.archiveFile != null) c.getString(R.string.dl_already) else "no file was produced" }
            val saved = media.map { publish(c, it, folderOf(out, it)) }
            // Sidecars come after the check and never fail the job: the video is already in the library by then.
            all.forEach { f ->
                val side = (j.options.thumbnailFile && DownloadLogic.isThumbFile(f.name)) || (j.options.subtitleFiles && DownloadLogic.isSubtitleFile(f.name))
                if (side) runCatching { publish(c, f, folderOf(out, f)) }.onFailure { Log.w("Download", "sidecar: ${it.javaClass.simpleName}") }
            }
            change(j.id) { it.copy(state = DlState.Done, title = it.title.ifEmpty { media.first().nameWithoutExtension }, audio = DownloadLogic.isAudioFile(media.first().name), fileUri = saved.first()) }
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

    /** The playlist folder a file sits in, as safe path parts under the private output folder. Empty for a flat download. */
    private fun folderOf(out: File, f: File): String =
        f.parentFile.relativeTo(out).invariantSeparatorsPath.split('/').mapNotNull { DownloadLogic.folderName(it) }.joinToString("/")

    /**
     * Pending until fully copied, so a kill mid-copy never leaves half a file in the library.
     * Pictures go to Pictures/Shoebill, subtitles to Movies/Shoebill (beside the video; MediaStore.Files takes any file type there).
     */
    private fun publish(c: Context, f: File, folder: String): String {
        val audio = DownloadLogic.isAudioFile(f.name)
        val thumb = DownloadLogic.isThumbFile(f.name)
        val root = when { thumb -> Environment.DIRECTORY_PICTURES; audio -> Environment.DIRECTORY_MUSIC; else -> Environment.DIRECTORY_MOVIES }
        val rel = listOf("Shoebill", folder).filter { it.isNotEmpty() }.joinToString("/")
        val r = c.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val base = when {
                thumb -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                DownloadLogic.isSubtitleFile(f.name) -> MediaStore.Files.getContentUri("external")
                audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, DownloadLogic.mime(f.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/$rel")
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
            val dir = File(Environment.getExternalStoragePublicDirectory(root), rel).apply { mkdirs() }
            val to = File(dir, f.name)
            f.copyTo(to, overwrite = true)
            MediaScannerConnection.scanFile(c, arrayOf(to.path), null, null)
            return Uri.fromFile(to).toString()
        }
    }
}

