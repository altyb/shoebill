@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.altayeb.shoebill.music

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.library.formatTime
import dev.altayeb.shoebill.library.logic.Lrc
import dev.altayeb.shoebill.library.logic.NaturalOrder
import dev.altayeb.shoebill.player.AudioFx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun rememberController(): MediaController? {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(ctx, ComponentName(ctx, MusicService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({ c = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(future) }
    }
    return c
}

@Composable
fun MusicTab(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val granted = { ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED }
    var access by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access = it }
    LifecycleResumeEffect(Unit) { access = granted(); onPauseOrDispose {} }
    if (!access) {
        Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.MusicNote, null, Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.music_access), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(audioPermission()) }) { Text(stringResource(R.string.music_allow)) }
        }
        return
    }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    LifecycleResumeEffect(Unit) { onPauseOrDispose {} }
    LaunchedEffect(Unit) { songs = querySongs(ctx) }
    val controller = rememberController()
    var full by rememberSaveable_ { mutableStateOf(false) }
    val state = rememberPlayerState(controller)
    if (full && controller != null && state.current != null) {
        BackHandler { full = false }
        NowPlaying(controller, state, songs.orEmpty(), modifier) { full = false }
        return
    }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            val all = songs
            if (all == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (all.isEmpty()) Text(stringResource(R.string.music_empty), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center)
            else Library(all, controller)
        }
        if (controller != null && state.current != null) MiniPlayer(controller, state) { full = true }
    }
}

// keeps the screen choice through rotation
@Composable
private fun <T : Any> rememberSaveable_(init: () -> T): T = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.runtime.saveable.autoSaver(), init = init)

class PlayerState {
    var current by mutableStateOf<androidx.media3.common.MediaItem?>(null)
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var speed by mutableFloatStateOf(1f)
    var queueVersion by mutableIntStateOf(0)
}

@Composable
private fun rememberPlayerState(c: MediaController?): PlayerState {
    val s = remember { PlayerState() }
    DisposableEffect(c) {
        if (c == null) return@DisposableEffect onDispose {}
        fun sync() {
            s.current = c.currentMediaItem
            s.playing = c.isPlaying
            s.shuffle = c.shuffleModeEnabled
            s.repeat = c.repeatMode
            s.speed = c.playbackParameters.speed
            s.duration = c.duration.coerceAtLeast(0)
            s.position = c.currentPosition.coerceAtLeast(0)
            s.queueVersion++
        }
        val l = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) = sync() }
        c.addListener(l)
        sync()
        onDispose { c.removeListener(l) }
    }
    LaunchedEffect(c) { while (c != null) { s.position = c.currentPosition.coerceAtLeast(0); delay(250) } }
    return s
}

private fun play(c: MediaController?, list: List<Song>, index: Int) {
    c ?: return
    c.setMediaItems(list.map { it.item() }, index, 0)
    c.prepare()
    c.play()
}

private enum class LibTab(val label: Int) { Songs(R.string.music_songs), Albums(R.string.music_albums), Artists(R.string.music_artists), Folders(R.string.music_folders), Lists(R.string.music_lists) }

@Composable
private fun Library(all: List<Song>, c: MediaController?) {
    val ctx = LocalContext.current
    val lists = remember { MusicLists(ctx) }
    var listsVersion by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(LibTab.Songs) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var addTo by remember { mutableStateOf<Song?>(null) }
    open?.let { (name, songs) ->
        BackHandler { open = null }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { play(c, songs, 0) }) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play)) }
            }
            SongList(songs, c) { addTo = it }
        }
    } ?: Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true,
            label = { Text(stringResource(R.string.search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LibTab.entries.forEach { t -> FilterChip(tab == t, { tab = t }, { Text(stringResource(t.label)) }) }
        }
        val q = query.trim()
        val hits = remember(all, q) { if (q.isEmpty()) all else all.filter { listOf(it.title, it.artist, it.album).any { f -> f.contains(q, true) } } }
        fun groups(key: (Song) -> String) = hits.groupBy(key).toSortedMap(NaturalOrder).map { it.key to it.value }
        when (tab) {
            LibTab.Songs -> SongList(hits, c) { addTo = it }
            LibTab.Albums -> GroupList(groups { it.album }) { n, s -> open = n to s.sortedWith(compareBy<Song> { it.track }.thenBy(NaturalOrder) { it.title }) }
            LibTab.Artists -> GroupList(groups { it.artist.ifEmpty { "—" } }) { n, s -> open = n to s }
            LibTab.Folders -> GroupList(groups { it.folder }) { n, s -> open = n to s }
            LibTab.Lists -> {
                listsVersion.hashCode()
                val names = lists.names()
                if (names.isEmpty()) Text(stringResource(R.string.music_lists_empty), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center)
                GroupList(names.map { it to lists.songs(it, all) }) { n, s -> open = n to s }
            }
        }
    }
    addTo?.let { s ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addTo = null },
            title = { Text(stringResource(R.string.music_add_to_list)) },
            text = { Column {
                lists.names().forEach { n -> TextButton(onClick = { lists.add(n, s.id); listsVersion++; addTo = null }) { Text(n) } }
                OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text(stringResource(R.string.music_new_list)) })
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { lists.add(name.trim(), s.id); listsVersion++; addTo = null }) { Text(stringResource(R.string.music_create)) } },
            dismissButton = { TextButton(onClick = { addTo = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SongList(songs: List<Song>, c: MediaController?, onLong: (Song) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, { play(c, songs, i) }, { onLong(s) }) }
    }
}

@Composable
private fun GroupList(groups: List<Pair<String, List<Song>>>, open: (String, List<Song>) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(groups, key = { it.first }) { (name, songs) ->
            Row(Modifier.fillMaxWidth().clickable { open(name, songs) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                songs.firstOrNull()?.let { Art(it, 52) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.music_count, songs.size), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SongRow(s: Song, onClick: () -> Unit, onLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(s, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(s.artist, s.album).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(s.durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Art(s: Song, sizeDp: Int) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, s.albumId) {
        value = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) runCatching { ctx.contentResolver.loadThumbnail(s.uri, Size(256, 256), null) }.getOrNull() else null
        }
    }
    val shape = RoundedCornerShape(10.dp)
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(sizeDp.dp).clip(shape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(sizeDp.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null) }
}

@Composable
private fun MiniPlayer(c: MediaController, s: PlayerState, open: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.current?.mediaMetadata?.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(s.current?.mediaMetadata?.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { if (s.playing) c.pause() else c.play() }) {
                Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (s.playing) R.string.pause else R.string.play))
            }
            IconButton(onClick = { c.seekToNext() }) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next)) }
        }
    }
}

@Composable
private fun NowPlaying(c: MediaController, s: PlayerState, all: List<Song>, modifier: Modifier, close: () -> Unit) {
    val ctx = LocalContext.current
    val song = remember(s.current, all) { s.current?.mediaId?.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id } } }
    var page by remember { mutableIntStateOf(0) } // 0 art, 1 lyrics, 2 queue, 3 sound
    var lyrics by remember(song) { mutableStateOf<String?>(null) }
    LaunchedEffect(song) { lyrics = song?.let { Lyrics.load(ctx, it) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && song != null) lyrics = kotlinx.coroutines.runBlocking { Lyrics.save(ctx, song, uri) }
    }
    var sleepAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0L) { delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0)); c.pause(); sleepAt = 0L }
    }
    Column(modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            listOf(R.string.music_page_art to Icons.Rounded.Album, R.string.music_page_lyrics to Icons.Rounded.Lyrics,
                R.string.music_page_queue to Icons.AutoMirrored.Rounded.QueueMusic, R.string.music_page_sound to Icons.Rounded.GraphicEq).forEachIndexed { i, (l, ic) ->
                IconToggleButton(page == i, { page = i }) { Icon(ic, stringResource(l)) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (page) {
                0 -> song?.let { Art(it, 280) }
                1 -> LyricsPage(lyrics, s.position, { c.seekTo(it) }) { pick.launch(arrayOf("*/*")) }
                2 -> QueuePage(c, s)
                else -> SoundPage(c, s, sleepAt) { sleepAt = it }
            }
        }
        Text(s.current?.mediaMetadata?.title?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(s.current?.mediaMetadata?.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column {
                var drag by remember { mutableStateOf<Float?>(null) }
                val dur = s.duration.coerceAtLeast(1)
                Slider(drag ?: (s.position.toFloat() / dur).coerceIn(0f, 1f), { drag = it }, onValueChangeFinished = { drag?.let { c.seekTo((it * dur).toLong()) }; drag = null })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(s.position), style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(s.duration), style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconToggleButton(s.shuffle, { c.shuffleModeEnabled = it }) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.music_shuffle)) }
                    IconButton(onClick = { c.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(36.dp)) }
                    FilledIconButton(onClick = { if (s.playing) c.pause() else c.play() }, Modifier.size(72.dp)) {
                        Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (s.playing) R.string.pause else R.string.play), Modifier.size(40.dp))
                    }
                    IconButton(onClick = { c.seekToNext() }) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next), Modifier.size(36.dp)) }
                    IconButton(onClick = { c.repeatMode = (s.repeat + 1) % 3 }) {
                        Icon(if (s.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, stringResource(R.string.music_repeat),
                            tint = if (s.repeat == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsPage(text: String?, pos: Long, seek: (Long) -> Unit, pick: () -> Unit) {
    val lines = remember(text) { text?.let(Lrc::parse).orEmpty() }
    if (lines.isEmpty()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text?.takeIf { it.isNotBlank() } ?: stringResource(R.string.music_no_lyrics), textAlign = TextAlign.Center)
            TextButton(onClick = pick) { Text(stringResource(R.string.music_pick_lyrics)) }
        }
        return
    }
    val now = Lrc.indexAt(lines, pos)
    val list = rememberLazyListState()
    LaunchedEffect(now) { if (now > 1) list.animateScrollToItem(now - 1) }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        itemsIndexed(lines) { i, l ->
            Text(l.text, Modifier.fillMaxWidth().clickable { seek(l.timeMs) }.padding(8.dp), textAlign = TextAlign.Center,
                style = if (i == now) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                color = if (i == now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QueuePage(c: MediaController, s: PlayerState) {
    val items = remember(s.queueVersion) { (0 until c.mediaItemCount).map { c.getMediaItemAt(it) } }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(items) { i, m ->
            Row(Modifier.fillMaxWidth().clickable { c.seekTo(i, 0) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.mediaMetadata.title?.toString().orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (i == c.currentMediaItemIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                IconButton(onClick = { c.removeMediaItem(i) }) { Icon(Icons.Rounded.Close, stringResource(R.string.music_remove)) }
            }
        }
    }
}

@Composable
private fun SoundPage(c: MediaController, s: PlayerState, sleepAt: Long, setSleep: (Long) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(8.dp).then(Modifier), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.player_speed), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v -> FilterChip(kotlin.math.abs(s.speed - v) < 0.01f, { c.setPlaybackSpeed(v) }, { Text("$v×") }) }
        }
        Text(stringResource(R.string.player_sleep_timer), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 30, 60).forEach { m -> OutlinedButton(onClick = { setSleep(System.currentTimeMillis() + m * 60_000L) }) { Text(stringResource(R.string.player_minutes_short, m.toString())) } }
            OutlinedButton(onClick = { setSleep(0L) }) { Text(stringResource(R.string.off)) }
        }
        if (sleepAt > 0L) Text(stringResource(R.string.music_sleep_on))
        val session = MusicService.audioSession
        val fx = remember(session) { if (session != 0) AudioFx.open(ctx, session) else null }
        DisposableEffect(fx) { onDispose { runCatching { fx?.release() } } }
        if (fx != null) {
            var on by remember { mutableStateOf(fx.enabled) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_equalizer), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(on, { on = it; fx.setEnabled(it) })
            }
            if (on) for (b in 0 until fx.bands) key(b) {
                var level by remember { mutableStateOf(fx.level(b).toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${fx.hz(b)}", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                    Slider(level, { level = it; fx.setLevel(b, it.toInt()) }, valueRange = fx.minLevel.toFloat()..fx.maxLevel.toFloat())
                }
            }
        }
    }
}
