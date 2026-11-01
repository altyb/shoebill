@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.altayeb.shoebill.download

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
data class DlJob(val id: String, val url: String, val options: DownloadOptions, val state: DlState = DlState.Waiting, val percent: Int = 0, val title: String = "", val error: String = "")

/** One download at a time, in order. State lives here so the screen can come and go. */
object DownloadQueue {
    val jobs = MutableStateFlow<List<DlJob>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ready = false
    @Volatile private var active: String? = null

    fun add(c: Context, url: String, o: DownloadOptions) {
        jobs.update { it + DlJob(UUID.randomUUID().toString(), url, o) }
        pump(c.applicationContext)
    }

    fun retry(c: Context, j: DlJob) { change(j.id) { it.copy(state = DlState.Waiting, percent = 0, error = "") }; pump(c.applicationContext) }
    fun clearFinished() = jobs.update { l -> l.filter { it.state in setOf(DlState.Waiting, DlState.Running, DlState.Finishing) } }

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
    private fun updateIfStale(c: Context) {
        val prefs = c.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("updated", 0L) < 7 * 24 * 3_600_000L) return
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(c, YoutubeDL.UpdateChannel.STABLE) }.onSuccess { prefs.edit().putLong("updated", now).apply() }
    }

    private suspend fun run(c: Context, j: DlJob) {
        val out = File(c.cacheDir, "downloads/${j.id}").apply { mkdirs() }
        try {
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
            YoutubeDL.getInstance().execute(req, j.id) { progress, _, _ ->
                change(j.id) { it.copy(percent = progress.toInt().coerceIn(0, 100)) }
            }
            if (jobs.value.firstOrNull { it.id == j.id }?.state == DlState.Cancelled) return
            change(j.id) { it.copy(state = DlState.Finishing, percent = 100) }
            val files = out.listFiles().orEmpty().filter { DownloadLogic.isMediaFile(it.name) }
            check(files.isNotEmpty()) { "no file was produced" }
            files.forEach { publish(c, it) }
            change(j.id) { it.copy(state = DlState.Done, title = files.first().nameWithoutExtension) }
            VideoIndex.refresh(c)
        } catch (e: Throwable) { // a broken runtime throws LinkageError; that must fail the job, not kill music and video too
            if (jobs.value.firstOrNull { it.id == j.id }?.state != DlState.Cancelled) {
                val line = e.message.orEmpty().lineSequence().lastOrNull { it.startsWith("ERROR") } ?: e.message.orEmpty().lineSequence().lastOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
                change(j.id) { it.copy(state = DlState.Failed, error = line.take(200)) }
            }
        } finally {
            out.deleteRecursively()
        }
    }

    /** Pending until fully copied, so a kill mid-copy never leaves half a file in the library. */
    private fun publish(c: Context, f: File) {
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
            } catch (e: Exception) {
                r.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES), "Shoebill").apply { mkdirs() }
            val to = File(dir, f.name)
            f.copyTo(to, overwrite = true)
            android.media.MediaScannerConnection.scanFile(c, arrayOf(to.path), null, null)
        }
    }
}

/** Keeps the process alive during a download, with a progress notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onBind(intent: Intent?): IBinder? = null

    private fun note(percent: Int, title: String): Notification =
        NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.dl_notify)).setContentText(title).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, percent, percent == 0).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, note(0, ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, note(0, ""))
        scope.launch {
            DownloadQueue.jobs.collect { l ->
                val j = l.firstOrNull { it.state == DlState.Running } ?: return@collect
                getSystemService(NotificationManager::class.java).notify(ID, note(j.percent, j.url.take(60)))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }
    private companion object { const val CHANNEL = "downloads"; const val ID = 4101 }
}

object DownloadFeature {
    const val ENABLED = true

    @Composable
    fun Tab(modifier: Modifier) {
        val ctx = LocalContext.current
        val shared by SharedLink.value.collectAsStateWithLifecycle()
        var url by remember { mutableStateOf("") }
        LaunchedEffect(shared) { DownloadLogic.firstUrl(shared)?.let { url = it; SharedLink.value.value = null } }
        var quality by remember { mutableStateOf(Quality.BEST) }
        var playlist by remember { mutableStateOf(false) }
        var subs by remember { mutableStateOf(false) }
        var bad by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var updating by remember { mutableStateOf(false) }
        val jobs by DownloadQueue.jobs.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val notify = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it; bad = false }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.dl_url)) },
                isError = bad, supportingText = if (bad) { { Text(stringResource(R.string.dl_bad_url)) } } else null,
                trailingIcon = { TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.let { url = DownloadLogic.firstUrl(it) ?: it; bad = false }
                }) { Text(stringResource(R.string.dl_paste)) } })
            Text(stringResource(R.string.dl_quality), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Quality.entries.forEach { q ->
                    FilterChip(quality == q, { quality = q }, { Text(when (q) {
                        Quality.BEST -> stringResource(R.string.dl_best)
                        Quality.M4A -> stringResource(R.string.dl_m4a)
                        Quality.MP3 -> stringResource(R.string.dl_mp3)
                        else -> "${q.height}p"
                    }) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(playlist, { playlist = it }); Text(stringResource(R.string.dl_playlist))
                if (quality.audio == null) { Spacer(Modifier.width(12.dp)); Checkbox(subs, { subs = it }); Text(stringResource(R.string.dl_subs)) }
            }
            Button(onClick = {
                val u = DownloadLogic.firstUrl(url)
                if (u == null) bad = true else {
                    if (Build.VERSION.SDK_INT >= 33) notify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    DownloadQueue.add(ctx, u, DownloadOptions(quality, playlist, subs)); url = ""
                }
            }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.dl_start)) }
            Text(stringResource(R.string.dl_rights), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dl_queue), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { DownloadQueue.clearFinished() }) { Text(stringResource(R.string.dl_clear)) }
            }
            if (jobs.isEmpty()) Text(stringResource(R.string.dl_none))
            LazyColumn(Modifier.weight(1f)) { items(jobs, key = { it.id }) { j -> JobRow(j) } }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(enabled = !updating, onClick = {
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
            }) { Text(stringResource(R.string.dl_update)) }
        }
    }

    @Composable
    private fun JobRow(j: DlJob) {
        val ctx = LocalContext.current
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(j.title.ifEmpty { j.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (j.state) {
                    DlState.Waiting -> stringResource(R.string.dl_waiting)
                    DlState.Running -> stringResource(R.string.dl_running, j.percent)
                    DlState.Finishing -> stringResource(R.string.dl_finishing)
                    DlState.Done -> stringResource(R.string.dl_done)
                    DlState.Failed -> stringResource(R.string.dl_failed, j.error)
                    DlState.Cancelled -> stringResource(R.string.dl_cancelled)
                }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = if (j.state == DlState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                when (j.state) {
                    DlState.Waiting, DlState.Running -> TextButton(onClick = { DownloadQueue.cancel(j) }) { Text(stringResource(R.string.dl_cancel)) }
                    DlState.Failed, DlState.Cancelled -> TextButton(onClick = { DownloadQueue.retry(ctx, j) }) { Text(stringResource(R.string.dl_retry)) }
                    else -> Unit
                }
            }
            if (j.state == DlState.Running) LinearProgressIndicator(progress = { j.percent / 100f }, Modifier.fillMaxWidth())
        }
    }
}
