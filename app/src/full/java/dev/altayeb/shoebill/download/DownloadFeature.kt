@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.altayeb.shoebill.download

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Icon
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
        val notify = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
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
            if (jobs.isEmpty()) item(key = "none") { EmptyState(R.drawable.art_download, stringResource(R.string.dl_none), Modifier.height(320.dp)) }
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
                            Thumb(i.thumb, proxy, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Radius.M))
                            if (i.durationSec > 0) Text(DownloadLogic.time(i.durationSec), Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = .7f), Radius.XS).padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(i.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (i.uploader.isNotEmpty()) Text(i.uploader, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(32.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.dl_reading))
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
                            Thumb(e.thumb, proxy, Modifier.width(80.dp).aspectRatio(16f / 9f).clip(Radius.XS))
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
                    if (showFormats) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        val pic by androidx.compose.runtime.produceState<Bitmap?>(null, url) {
            value = url?.let { withContext(Dispatchers.IO) { Probe.thumb(it, proxy) } }
        }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            pic?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }

    /** The download in progress, large, with the same picture as the sheet before it. */
    @Composable
    private fun HeroCard(j: DlJob) {
        val cs = MaterialTheme.colorScheme
        Surface(shape = Radius.L, color = cs.surfaceContainerHigh) {
            Column {
                if (j.thumb != null) Thumb(j.thumb, j.options.proxy, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (j.thumb == null) { // a plain file has no picture: a tile keeps the card from looking empty
                            Surface(Modifier.size(48.dp), shape = Radius.M, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Download, null) }
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(j.title.ifEmpty { j.url }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(heroLine(j), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton(onClick = { DownloadQueue.cancel(j) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, stringResource(R.string.dl_cancel)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Inside the padding, so the wave has room and is never cut by the card edge.
                        LinearWavyProgressIndicator(progress = { j.percent / 100f }, Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text("${j.percent}%", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    }
                }
            }
        }
    }

    /** The percent has its own place beside the bar, so this line carries only speed and time left. */
    @Composable
    private fun heroLine(j: DlJob): String =
        if (j.state != DlState.Running) status(j)
        else listOfNotNull(j.speed, j.etaSec.takeIf { it > 0 }?.let { stringResource(R.string.dl_left, DownloadLogic.time(it)) }).joinToString(" · ").ifEmpty { stringResource(R.string.dl_starting) }

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
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(j.fileUri), if (j.audio) "audio/*" else "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            }
        }
        Surface(modifier.fillMaxWidth().then(if (j.state == DlState.Done && j.fileUri != null) Modifier.clickable(onClick = open) else Modifier), shape = Radius.M, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(j.thumb, j.options.proxy, Modifier.width(112.dp).aspectRatio(16f / 9f).clip(Radius.S))
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
                ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${ctx.packageName}")))
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
