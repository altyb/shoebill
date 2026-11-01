@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package dev.altayeb.shoebill.music

import androidx.compose.runtime.collectAsState
import dev.altayeb.shoebill.settings.AppSettings
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.toPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.launch
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
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.graphics.shapes.Morph
import androidx.media3.common.MediaItem
import dev.altayeb.shoebill.ui.EmptyState
import dev.altayeb.shoebill.ui.theme.Radius
import kotlinx.coroutines.CoroutineScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import dev.altayeb.shoebill.ui.ConnectedChoice
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.derivedStateOf

private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun rememberController(connect: Boolean): MediaController? {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(connect) {
        if (!connect) return@DisposableEffect onDispose {}
        val token = SessionToken(ctx, ComponentName(ctx, MusicService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({ c = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(ctx))
        onDispose { c = null; MediaController.releaseFuture(future) }
    }
    return c
}

/** What the Music tab, the dock and the sheet share. Lives above the tabs so playback stays visible on all of them. */
class MusicHost(val state: PlayerState, val sheet: Animatable<Float, AnimationVector1D>) {
    var controller by mutableStateOf<MediaController?>(null)
    var songs by mutableStateOf<List<Song>?>(null)
    val hasTrack get() = controller != null && state.current != null
}

/** No connection until music is wanted: people who only watch video never start the service or its player. */
@Composable
fun rememberMusicHost(tab: Int): MusicHost {
    var connect by remember { mutableStateOf(MusicService.running) }
    LaunchedEffect(tab) { if (tab == MUSIC_TAB) connect = true }
    val controller = rememberController(connect)
    val state = rememberPlayerState(controller)
    val sheet = remember { Animatable(0f) }
    return remember { MusicHost(state, sheet) }.also { it.controller = controller }
}

const val MUSIC_TAB = 3

@Composable
fun MusicTab(host: MusicHost, modifier: Modifier = Modifier) {
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
    LaunchedEffect(Unit) { host.songs = querySongs(ctx) }
    Box(modifier.fillMaxSize()) {
        val all = host.songs
        if (all == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        else if (all.isEmpty()) EmptyState(R.drawable.art_music, stringResource(R.string.music_empty))
        else Library(all, host.controller)
    }
}

class PlayerState {
    var current by mutableStateOf<MediaItem?>(null)
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
    LaunchedEffect(c) { while (c != null) { if (c.isPlaying) s.position = c.currentPosition.coerceAtLeast(0); delay(250) } }
    return s
}

private fun play(c: MediaController?, list: List<Song>, index: Int) {
    c ?: return
    c.setMediaItems(list.map { it.item() }, index, 0)
    c.prepare()
    c.play()
}

private enum class LibTab(val label: Int) { Songs(R.string.music_songs), Albums(R.string.music_albums), Artists(R.string.music_artists), Genres(R.string.music_genres), Folders(R.string.music_folders), Lists(R.string.music_lists) }

@Composable
private fun Library(all: List<Song>, c: MediaController?) {
    val ctx = LocalContext.current
    val lists = remember { MusicLists(ctx) }
    var listsVersion by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(LibTab.entries.getOrElse(AppSettings.get(ctx).settings.value.musicStartTab) { LibTab.Songs }) }
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
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibTab.entries.forEach { t -> FilterChip(tab == t, { tab = t }, { Text(stringResource(t.label)) }) }
        }
        val q = query.trim()
        val hits = remember(all, q) { if (q.isEmpty()) all else all.filter { listOf(it.title, it.artist, it.album).any { f -> f.contains(q, true) } } }
        fun groups(key: (Song) -> String) = hits.groupBy(key).toSortedMap(NaturalOrder).map { it.key to it.value }
        when (tab) {
            LibTab.Songs -> SongList(hits, c) { addTo = it }
            LibTab.Albums -> AlbumGrid(groups { it.album }) { n, s -> open = n to s.sortedWith(compareBy<Song> { it.track }.thenBy(NaturalOrder) { it.title }) }
            LibTab.Genres -> GroupList(hits.filter { it.genre.isNotBlank() }.groupBy { it.genre }.toSortedMap(NaturalOrder).map { it.key to it.value }) { n, s -> open = n to s }
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

/** One decode per album, so scrolling back never decodes again or flashes the placeholder. */
private val artCache = LruCache<Long, Bitmap>(64)

/** One group of sound options on its own card. */
@Composable
private fun SoundCard(title: String?, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = Radius.L, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            content()
        }
    }
}

private class MorphShape(val morph: Morph, val progress: Float) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        morph.toPath(progress, path)
        val m = Matrix(); m.scale(size.width, size.height)
        path.transform(m)
        return Outline.Generic(path)
    }
}

@Composable
private fun AlbumGrid(groups: List<Pair<String, List<Song>>>, open: (String, List<Song>) -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(groups.size, key = { groups[it].first }) { i ->
            val (name, songs) = groups[i]
            BoxWithConstraints(Modifier.clip(Radius.S).clickable { open(name, songs) }) {
                val side = maxWidth.value.toInt()
                Column {
                    songs.firstOrNull()?.let { Art(it, side) }
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(songs.first().artist.ifEmpty { stringResource(R.string.music_count, songs.size) }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
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
    val bmp by produceState<Bitmap?>(artCache.get(s.albumId), s.albumId) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) runCatching { ctx.contentResolver.loadThumbnail(s.uri, Size(256, 256), null) }.getOrNull() else null
        }
        value?.let { artCache.put(s.albumId, it) }
    }
    val shape = Radius.S
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(sizeDp.dp).clip(shape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(sizeDp.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null) }
}

private fun settle(host: MusicHost, scope: CoroutineScope, springOpen: FiniteAnimationSpec<Float>, springClose: FiniteAnimationSpec<Float>, velocity: Float = 0f) {
    val open = if (kotlin.math.abs(velocity) > 300f) velocity < 0 else host.sheet.value > 0.3f
    scope.launch { host.sheet.animateTo(if (open) 1f else 0f, if (open) springOpen else springClose) }
}

@Composable
private fun rememberSheetDrag(host: MusicHost, scope: CoroutineScope): DraggableState {
    val range = LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
    return rememberDraggableState { d ->
        scope.launch { host.sheet.snapTo((host.sheet.value - d / range).coerceIn(0f, 1f)) }
    }
}

/** The top of the bottom dock: same surface as the tabs, no card of its own. Tap or drag up to open. */
@Composable
fun MiniPlayer(host: MusicHost) {
    val c = host.controller ?: return
    val s = host.state
    val scope = rememberCoroutineScope()
    val drag = rememberSheetDrag(host, scope)
    val sheetOpen = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val sheetClose = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    Row(
        Modifier.fillMaxWidth()
            .draggable(drag, Orientation.Vertical, onDragStopped = { settle(host, scope, sheetOpen, sheetClose, it) })
            .clickable { scope.launch { host.sheet.animateTo(1f, sheetOpen) } }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        host.songs?.firstOrNull { it.id == s.current?.mediaId?.toLongOrNull() }?.let { Art(it, 44); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(s.current?.mediaMetadata?.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(s.current?.mediaMetadata?.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { if (s.playing) c.pause() else c.play() }, shapes = IconButtonDefaults.shapes()) {
            Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (s.playing) R.string.pause else R.string.play))
        }
        IconButton(onClick = { c.seekToNext() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next)) }
    }
    // Moves while playing and lies flat when paused, which is also the only way to tell the two apart at a glance.
    LinearWavyProgressIndicator(
        progress = { (s.position.toFloat() / s.duration.coerceAtLeast(1)).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        amplitude = { if (s.playing) 1f else 0f },
    )
}

/**
 * The dock grows into the now-playing page. It starts as the dock's own rectangle, so nothing appears from nowhere,
 * then the page's parts follow in order (header, art, controls). The art takes the spring's overshoot.
 */
@Composable
fun MusicSheet(host: MusicHost, dockHeight: Int) {
    val c = host.controller
    val scope = rememberCoroutineScope()
    val sheetClose = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val sheetOpen = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    if (c == null || !host.hasTrack) {
        LaunchedEffect(Unit) { host.sheet.snapTo(0f) }
        return
    }
    // The value is only read in layout and draw below, so a moving sheet relays out one surface and recomposes nothing.
    val shown by remember(host) { derivedStateOf { host.sheet.value > 0f || host.sheet.isRunning } }
    if (!shown) return
    BackHandler(host.sheet.targetValue > 0f) { scope.launch { host.sheet.animateTo(0f, sheetClose) } }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (host.songs == null && ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED) host.songs = querySongs(ctx)
    }
    val drag = rememberSheetDrag(host, scope)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val density = LocalDensity.current
        val full = constraints.maxHeight.toFloat()
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
                .layout { m, c ->
                    val h = (dockHeight + (full - dockHeight) * host.sheet.value.coerceIn(0f, 1f)).toInt().coerceAtLeast(0)
                    val pl = m.measure(c.copy(minHeight = h, maxHeight = h))
                    layout(pl.width, h) { pl.place(0, 0) }
                }
                .graphicsLayer { shape = RoundedCornerShape(24.dp * (1f - host.sheet.value.coerceIn(0f, 1f))); clip = true }
                .draggable(drag, Orientation.Vertical, onDragStopped = { settle(host, scope, sheetOpen, sheetClose, it) }),
        ) {
            // Laid out at full size from the first frame and only clipped, so the page never reflows while it grows.
            Box(Modifier.fillMaxSize().wrapContentHeight(Alignment.Top, unbounded = true)) {
                NowPlaying(c, host.state, host.songs.orEmpty(), Modifier.height(with(density) { full.toDp() }).statusBarsPadding().navigationBarsPadding(), { host.sheet.value }) {
                    scope.launch { host.sheet.animateTo(0f, sheetClose) }
                }
            }
        }
    }
}

/** One part of the page arriving: from `start` of the sheet's travel, over the next 40%. Fade and short rise, FilmBox's stagger. */
private fun Modifier.arrive(progress: () -> Float, start: Float) = graphicsLayer {
    val a = ((progress() - start) / 0.4f).coerceIn(0f, 1f)
    alpha = a
    translationY = (1f - a) * 24.dp.toPx()
}

@Composable
private fun NowPlaying(c: MediaController, s: PlayerState, all: List<Song>, modifier: Modifier, progress: () -> Float, close: () -> Unit) {
    val ctx = LocalContext.current
    val song = remember(s.current, all) { s.current?.mediaId?.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id } } }
    var page by remember { mutableIntStateOf(0) } // 0 art, 1 lyrics, 2 queue, 3 sound
    val prefs by AppSettings.get(ctx).settings.collectAsState()
    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOnMusic) {
        view.keepScreenOn = prefs.keepScreenOnMusic
        onDispose { view.keepScreenOn = false }
    }
    var lyrics by remember(song) { mutableStateOf<String?>(null) }
    LaunchedEffect(song) { lyrics = song?.let { Lyrics.load(ctx, it) } }
    val lyricScope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && song != null) lyricScope.launch { lyrics = Lyrics.save(ctx, song, uri) }
    }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0L) { delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0)); c.pause(); sleepAt = 0L }
    }
    Column(modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().arrive(progress, 0.15f), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            listOf(R.string.music_page_art to Icons.Rounded.Album, R.string.music_page_lyrics to Icons.Rounded.Lyrics,
                R.string.music_page_queue to Icons.AutoMirrored.Rounded.QueueMusic, R.string.music_page_sound to Icons.Rounded.GraphicEq).forEachIndexed { i, (l, ic) ->
                IconToggleButton(page == i, { page = i }) { Icon(ic, stringResource(l)) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().arrive(progress, 0.25f), contentAlignment = Alignment.Center) {
            val motion = MaterialTheme.motionScheme
            AnimatedContent(page, transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.94f)) togetherWith fadeOut(motion.fastEffectsSpec())
            }, contentAlignment = Alignment.Center, label = "npPage") { pageNow -> when (pageNow) {
                // Reads the raw value, so the art alone shows the spring's overshoot past 1.
                0 -> song?.let {
                    // Paused art relaxes into a cookie; playing art is a plain square. Material's own shape morph.
                    val morph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie12Sided) }
                    val cookie by animateFloatAsState(if (s.playing || !prefs.coverMorph) 0f else 1f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "artMorph")
                    Box(Modifier.graphicsLayer { val v = 0.82f + 0.18f * progress(); scaleX = v; scaleY = v }.clip(MorphShape(morph, cookie))) { Art(it, 280) }
                }
                1 -> LyricsPage(lyrics, s.position, { c.seekTo(it) }) { pick.launch(arrayOf("*/*")) }
                2 -> QueuePage(c, s)
                else -> SoundPage(c, s, sleepAt, sleepMinutes) { m -> sleepMinutes = m; sleepAt = if (m == 0) 0L else System.currentTimeMillis() + m * 60_000L }
            } }
        }
        Column(Modifier.fillMaxWidth().arrive(progress, 0.35f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.current?.mediaMetadata?.title?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(s.current?.mediaMetadata?.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.arrive(progress, 0.45f)) {
                var drag by remember { mutableStateOf<Float?>(null) }
                val dur = s.duration.coerceAtLeast(1)
                Slider(drag ?: (s.position.toFloat() / dur).coerceIn(0f, 1f), { drag = it }, onValueChangeFinished = { drag?.let { c.seekTo((it * dur).toLong()) }; drag = null })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(s.position), style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(s.duration), style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconToggleButton(s.shuffle, { c.shuffleModeEnabled = it }) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.music_shuffle)) }
                    IconButton(onClick = { if (prefs.rewindBeforePrevious) c.seekToPrevious() else c.seekToPreviousMediaItem() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(36.dp)) }
                    FilledIconButton(onClick = { if (s.playing) c.pause() else c.play() }, modifier = Modifier.size(72.dp), shapes = IconButtonDefaults.shapes()) {
                        Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (s.playing) R.string.pause else R.string.play), Modifier.size(40.dp))
                    }
                    IconButton(onClick = { c.seekToNext() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next), Modifier.size(36.dp)) }
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
    val large = AppSettings.get(LocalContext.current).settings.collectAsState().value.lyricsLarge
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
                style = when { large -> if (i == now) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge; i == now -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.bodyLarge },
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
private fun SoundPage(c: MediaController, s: PlayerState, sleepAt: Long, sleepMinutes: Int, setSleep: (Int) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SoundCard(stringResource(R.string.player_speed)) {
            ConnectedChoice(listOf(0.75f, 1f, 1.25f, 1.5f, 2f).map { it to "$it×" }, listOf(0.75f, 1f, 1.25f, 1.5f, 2f).firstOrNull { kotlin.math.abs(s.speed - it) < 0.01f }) { c.setPlaybackSpeed(it) }
        }
        SoundCard(stringResource(R.string.player_sleep_timer)) {
            val off = stringResource(R.string.off)
            ConnectedChoice(listOf(0 to off) + listOf(15, 30, 60).map { it to stringResource(R.string.player_minutes_short, it.toString()) }, if (sleepAt > 0L) sleepMinutes else 0, pick = setSleep)
            if (sleepAt > 0L) {
                val left by produceState(sleepAt - System.currentTimeMillis(), sleepAt) {
                    while (true) { value = sleepAt - System.currentTimeMillis(); delay(1000) }
                }
                Text(stringResource(R.string.music_sleep_left, formatTime(left.coerceAtLeast(0))), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        val session = MusicService.audioSession
        val fx = remember(session) { if (session != 0) AudioFx.open(ctx, session) else null }
        DisposableEffect(fx) { onDispose { runCatching { fx?.release() } } }
        if (fx != null) SoundCard(null) {
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
