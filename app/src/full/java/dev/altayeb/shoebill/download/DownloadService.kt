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

/** Keeps the process alive during a download. The notification shows the video itself, speed, time left, playlist progress, pause and cancel. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onBind(intent: Intent?): IBinder? = null
    private val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openTab(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(Intent.ACTION_VIEW, Uri.parse("shoebill://download"), this, dev.altayeb.shoebill.MainActivity::class.java), flags,
    )
    private fun action(name: String, code: Int, id: String? = null): PendingIntent =
        PendingIntent.getForegroundService(this, code, Intent(this, DownloadService::class.java).setAction(name).apply { id?.let { putExtra("id", it) } }, flags)

    /** "Playlist 4/12 · 3 more waiting": where this job sits in what was started with it. */
    private fun progressLine(j: DlJob, all: List<DlJob>): String? {
        val batch = j.batch?.let { b -> all.filter { it.batch == b } }?.takeIf { it.size > 1 }
        val place = j.item ?: batch?.let { "${it.count { x -> x.state == DlState.Done } + 1}/${it.size}" }
        val waiting = all.count { it.state == DlState.Waiting }
        return listOfNotNull(place?.let { getString(R.string.dl_playlist_place, it) }, waiting.takeIf { it > 0 }?.let { getString(R.string.dl_more_waiting, it) })
            .joinToString(" · ").ifEmpty { null }
    }

    private fun note(j: DlJob?, all: List<DlJob> = emptyList()): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(j?.title?.ifEmpty { null } ?: getString(R.string.dl_notify)).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setContentIntent(openTab())
            .setProgress(100, j?.percent ?: 0, j == null || j.percent == 0)
        if (j != null) {
            b.setContentText(listOfNotNull("${j.percent}%", j.speed, j.etaSec.takeIf { it > 0 }?.let { getString(R.string.dl_left, DownloadLogic.time(it)) }).joinToString(" · "))
            b.addAction(0, getString(R.string.dl_pause), action(ACTION_PAUSE, 2))
            b.addAction(0, getString(R.string.dl_cancel), action(ACTION_CANCEL, 1))
            j.thumb?.let { u -> thumbs[u]?.let { b.setLargeIcon(it) } }
            progressLine(j, all)?.let { b.setSubText(it) }
        }
        return b.build()
    }
    private val thumbs = HashMap<String, Bitmap?>()

    private fun finished(j: DlJob) {
        if (!DownloadQueue.notified.add(j.id)) return
        val failed = j.state == DlState.Failed
        val file = j.fileUri?.takeIf { !failed }?.let(Uri::parse)
        val mime = if (j.audio) "audio/*" else "video/*"
        val b = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(if (failed) R.string.dl_notify_failed else R.string.dl_notify_done))
            .setContentText(j.title.ifEmpty { j.url }).setAutoCancel(true).setGroup(GROUP)
            .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
        if (failed && j.error.isNotEmpty()) b.setStyle(NotificationCompat.BigTextStyle().bigText("${j.title.ifEmpty { j.url }}\n${j.error}"))
        if (file != null) {
            // tapping plays the file in Shoebill (music for audio, the video player for video); Share hands it to another app
            b.setContentIntent(PendingIntent.getActivity(this, j.id.hashCode(), Intent(this, dev.altayeb.shoebill.MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(file, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), flags))
            b.addAction(0, getString(R.string.play), b.build().contentIntent)
            b.addAction(0, getString(R.string.dl_share), PendingIntent.getActivity(this, j.id.hashCode() + 1,
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null), flags))
        } else {
            b.setContentIntent(openTab())
            if (failed) b.addAction(0, getString(R.string.dl_retry), action(ACTION_RETRY, j.id.hashCode(), j.id))
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(j.id.hashCode(), b.build())
        nm.notify(SUMMARY, NotificationCompat.Builder(this, CHANNEL_DONE).setSmallIcon(android.R.drawable.stat_sys_download_done).setGroup(GROUP).setGroupSummary(true).setAutoCancel(true).setContentIntent(openTab()).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, getString(R.string.dl_notify_done), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, note(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, note(null))
        if (intent?.action == null) nm.cancel(PAUSED) // a new download: the old "paused" note is stale
        DownloadQueue.loadHistory(this) // a notification button can be the first thing to run after the process was killed
        when (intent?.action) {
            ACTION_CANCEL -> DownloadQueue.jobs.value.firstOrNull { it.state == DlState.Running }?.let { DownloadQueue.cancel(it) }
            ACTION_PAUSE -> DownloadQueue.pauseAll()
            ACTION_RESUME -> { nm.cancel(PAUSED); DownloadQueue.resumeAll(this) }
            ACTION_RETRY -> DownloadQueue.jobs.value.firstOrNull { it.id == intent.getStringExtra("id") }?.let { nm.cancel(it.id.hashCode()); DownloadQueue.retry(this, it) }
        }
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
                nm.notify(ID, note(j, l))
            }
        }
        // Started by a button with nothing to do (the job was gone): do not sit in the shade.
        if (DownloadQueue.jobs.value.none { it.state == DlState.Waiting || it.state == DlState.Running }) idle(this)
        return START_NOT_STICKY
    }
    private var collecting = false

    override fun onDestroy() {
        // The queue stops this service right after the last job ends, often before the collector above sees it.
        DownloadQueue.jobs.value.filter { it.state == DlState.Done || it.state == DlState.Failed }.forEach { finished(it) }
        scope.coroutineContext[Job]?.cancel(); super.onDestroy()
    }
    companion object {
        /** Nothing left to run: say what is paused, with one button to carry on, then let the process go. Never starts the service. */
        fun idle(c: Context) {
            val l = DownloadQueue.jobs.value
            if (l.any { it.state == DlState.Waiting || it.state == DlState.Running || it.state == DlState.Finishing }) return
            val paused = l.count { it.state == DlState.Paused }
            if (paused > 0) c.getSystemService(NotificationManager::class.java).notify(PAUSED, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle(c.getString(R.string.dl_paused_count, paused)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 0, Intent(Intent.ACTION_VIEW, Uri.parse("shoebill://download"), c, dev.altayeb.shoebill.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(0, c.getString(R.string.dl_resume_all), PendingIntent.getForegroundService(c, 3, Intent(c, DownloadService::class.java).setAction(ACTION_RESUME), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            c.stopService(Intent(c, DownloadService::class.java))
        }
        private const val CHANNEL = "downloads"; private const val ID = 4101; private const val ACTION_CANCEL = "cancel"; private const val ACTION_RETRY = "retry"
        private const val ACTION_PAUSE = "pause"; private const val ACTION_RESUME = "resume"; private const val PAUSED = 4103
        private const val CHANNEL_DONE = "downloads_done"; private const val GROUP = "dl_done"; private const val SUMMARY = 4102
    }
}
