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

/** Keeps the process alive during a download. The notification shows the video itself, speed, time left and a cancel button. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onBind(intent: Intent?): IBinder? = null

    private fun openTab(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(Intent.ACTION_VIEW, Uri.parse("shoebill://download"), this, dev.altayeb.shoebill.MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun note(j: DlJob?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(j?.title?.ifEmpty { null } ?: getString(R.string.dl_notify)).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setContentIntent(openTab())
            .setProgress(100, j?.percent ?: 0, j == null || j.percent == 0)
        if (j != null) {
            b.setContentText(listOfNotNull("${j.percent}%", j.speed, j.etaSec.takeIf { it > 0 }?.let { getString(R.string.dl_left, DownloadLogic.time(it)) }).joinToString(" · "))
            b.addAction(0, getString(R.string.dl_cancel), PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            j.thumb?.let { u -> thumbs[u]?.let { b.setLargeIcon(it) } }
        }
        return b.build()
    }
    private val thumbs = HashMap<String, Bitmap?>()

    private fun finished(j: DlJob) {
        if (!notified.add(j.id)) return
        val failed = j.state == DlState.Failed
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val file = j.fileUri?.takeIf { !failed }?.let(Uri::parse)
        val mime = if (j.audio) "audio/*" else "video/*"
        val b = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(if (failed) R.string.dl_notify_failed else R.string.dl_notify_done))
            .setContentText(j.title.ifEmpty { j.url }).setAutoCancel(true).setGroup(GROUP)
            .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
        if (file != null) {
            // tapping plays the file in Shoebill; Share hands it to another app
            b.setContentIntent(PendingIntent.getActivity(this, j.id.hashCode(), Intent(this, dev.altayeb.shoebill.MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(file, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), flags))
            b.addAction(0, getString(R.string.play), b.build().contentIntent)
            b.addAction(0, getString(R.string.dl_share), PendingIntent.getActivity(this, j.id.hashCode() + 1,
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null), flags))
        } else {
            b.setContentIntent(openTab())
            if (failed) b.addAction(0, getString(R.string.dl_retry), PendingIntent.getService(this, j.id.hashCode(), Intent(this, DownloadService::class.java).setAction(ACTION_RETRY).putExtra("id", j.id), flags))
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(j.id.hashCode(), b.build())
        nm.notify(SUMMARY, NotificationCompat.Builder(this, CHANNEL_DONE).setSmallIcon(android.R.drawable.stat_sys_download_done).setGroup(GROUP).setGroupSummary(true).setAutoCancel(true).setContentIntent(openTab()).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_DONE, getString(R.string.dl_notify_done), NotificationManager.IMPORTANCE_DEFAULT))
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, note(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, note(null))
        if (intent?.action == ACTION_CANCEL) { DownloadQueue.jobs.value.firstOrNull { it.state == DlState.Running }?.let { DownloadQueue.cancel(it) }; return START_NOT_STICKY }
        if (intent?.action == ACTION_RETRY) { DownloadQueue.jobs.value.firstOrNull { it.id == intent.getStringExtra("id") }?.let { notified.remove(it.id); DownloadQueue.retry(this, it) } }
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
        scope.coroutineContext[Job]?.cancel(); super.onDestroy()
    }
    private companion object { const val CHANNEL = "downloads"; const val ID = 4101; const val ACTION_CANCEL = "cancel"; const val ACTION_RETRY = "retry"; const val CHANNEL_DONE = "downloads_done"; const val GROUP = "dl_done"; const val SUMMARY = 4102; val notified = Collections.synchronizedSet(HashSet<String>()) }
}

