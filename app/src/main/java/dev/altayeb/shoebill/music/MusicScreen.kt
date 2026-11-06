@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.altayeb.shoebill.music

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.rounded.Timer
import dev.altayeb.shoebill.library.logic.SmartLists
import dev.altayeb.shoebill.library.logic.PlayPeriod
import androidx.compose.material.icons.rounded.Close
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.library.formatTime
import dev.altayeb.shoebill.library.logic.Lrc
import dev.altayeb.shoebill.library.logic.NaturalOrder
import dev.altayeb.shoebill.library.logic.artBucket
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import dev.altayeb.shoebill.ui.EmptyState
import dev.altayeb.shoebill.ui.theme.Radius
import kotlinx.coroutines.CoroutineScope

private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun rememberController(connect: Boolean): MediaController? {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf<MediaController?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    DisposableEffect(connect, generation) {
        if (!connect) return@DisposableEffect onDispose {}
        val token = SessionToken(ctx, ComponentName(ctx, MusicService::class.java))
        var live = true
        val future = MediaController.Builder(ctx, token).setListener(object : MediaController.Listener {
            // The service died under us (killed for memory): drop the dead controller and connect again, which restarts it and restores the queue.
            override fun onDisconnected(controller: MediaController) { if (live) { c = null; generation++ } }
        }).buildAsync()
        future.addListener({ c = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(ctx))
        onDispose { live = false; c = null; MediaController.releaseFuture(future) }
    }
    return c
}

/** What the Music tab, the dock and the sheet share. Lives above the tabs so playback stays visible on all of them. */
class MusicHost(val state: PlayerState, val sheet: Animatable<Float, AnimationVector1D>, val density: Float = 1f) {
    var pull = 0f
    var controller by mutableStateOf<MediaController?>(null)
    var songs by mutableStateOf<List<Song>?>(null)
    private val byId by derivedStateOf { songs.orEmpty().associateBy { it.id } }
    /** The song playing now, from the library list; null before the list is loaded. */
    val song: Song? get() = state.current?.mediaId?.toLongOrNull()?.let(byId::get)
    val hasTrack get() = controller != null && state.current != null
}

/** No connection until music is wanted: people who only watch video never start the service or its player. */
@Composable
fun rememberMusicHost(tab: Int): MusicHost {
    var connect by remember { mutableStateOf(MusicService.running) }
    LaunchedEffect(tab) { if (tab == MUSIC_TAB) connect = true }
    val inbox by MusicInbox.uri.collectAsStateWithLifecycle()
    LaunchedEffect(inbox) { if (inbox != null) connect = true }
    val controller = rememberController(connect)
    val state = rememberPlayerState(controller)
    val sheet = remember { Animatable(0f) }
    val host = LocalDensity.current.density.let { dp -> remember { MusicHost(state, sheet, dp) } }.also { it.controller = controller }
    // Open or closed survives a theme change, which recreates the activity: the sheet reopens once the controller is back.
    var open by rememberSaveable { mutableStateOf(false) }
    var reopen by remember { mutableStateOf(open) }
    LaunchedEffect(host.hasTrack) { if (reopen && host.hasTrack) { reopen = false; sheet.snapTo(1f) } }
    LaunchedEffect(Unit) { snapshotFlow { sheet.targetValue > 0.5f }.collect { if (!reopen) open = it } }
    // An audio file opened from anywhere (a finished download, its notification, a file manager) plays at once with the
    // full player over the current tab, in the library queue when it is in the library.
    val ctx = LocalContext.current
    LaunchedEffect(inbox, controller) {
        val u = inbox ?: return@LaunchedEffect
        val c = controller ?: return@LaunchedEffect
        MusicInbox.uri.value = null
        val all = host.songs ?: runCatching { querySongs(ctx) }.getOrDefault(emptyList()).also { host.songs = it }
        val id = if (u.authority == android.provider.MediaStore.AUTHORITY) runCatching { android.content.ContentUris.parseId(u) }.getOrNull() else null
        val i = all.indexOfFirst { it.id == id }
        if (i >= 0) play(c, all, i) else { c.setMediaItem(MediaItem.fromUri(u)); c.prepare(); c.play() }
        snapshotFlow { host.hasTrack }.first { it } // the sheet closes itself while there is no track yet
        sheet.animateTo(1f)
    }
    return host
}

const val MUSIC_TAB = 3

object MusicInbox { val uri = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null) }

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
    // Songs added or removed while the tab is open (a download, a delete) show up without leaving the tab.
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { refresh++ } }
        ctx.contentResolver.registerContentObserver(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs)
        onDispose { ctx.contentResolver.unregisterContentObserver(obs) }
    }
    // The old list stays on screen while the new one loads, so a refresh never flashes the spinner; rows are keyed by id, so scroll holds.
    LaunchedEffect(refresh) { if (refresh > 0) delay(500); host.songs = querySongs(ctx) }
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
            s.duration = c.duration.coerceAtLeast(0) // TIME_UNSET is negative
            s.position = c.currentPosition.coerceAtLeast(0)
        }
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                sync()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) s.queueVersion++
            }
        }
        c.addListener(l)
        sync() // a reconnect resyncs everything from the new controller
        s.queueVersion++
        onDispose { c.removeListener(l) }
    }
    // The 4 Hz position poll only while the screen is started; in the background the service plays on and nothing reads it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(c) {
        if (c == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { if (c.isPlaying) s.position = c.currentPosition.coerceAtLeast(0); delay(250) } }
    }
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
    val ui = remember { ctx.getSharedPreferences("music_ui", android.content.Context.MODE_PRIVATE) }
    var az by remember { mutableStateOf(ui.getBoolean("az", false)) }
    LaunchedEffect(az) { ui.edit().putBoolean("az", az).apply() }
    var open by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    // One of your own playlists is open by name, so a removed song or a rename shows at once.
    var openList by remember { mutableStateOf<String?>(null) }
    var renameList by remember { mutableStateOf<String?>(null) }
    var deleteList by remember { mutableStateOf<String?>(null) }
    var addTo by remember { mutableStateOf<Song?>(null) }
    var menuFor by remember { mutableStateOf<Song?>(null) }
    var newList by remember { mutableStateOf(false) }
    var ringtone by remember { mutableStateOf<Song?>(null) }
    val stats = remember { MusicStats.get(ctx) }
    val plays by stats.plays.collectAsStateWithLifecycle()
    val favs by stats.favorites.collectAsStateWithLifecycle()
    // A ready-made list is opened by kind, not as a copy: it follows plays and hearts while it is open.
    var smart by rememberSaveable { mutableStateOf<SmartKind?>(null) }
    val smartSongs = remember(all, plays, favs) { SmartKind.entries.associateWith { it.songs(all, plays, favs) } }
    // The span each list shows, kept while it is open and back again (Most played: by plays; Recently added: by days).
    var period by rememberSaveable { mutableStateOf(PlayPeriod.All) }
    var days by rememberSaveable { mutableStateOf(0) }
    smart?.let { k ->
        BackHandler { smart = null }
        val keys = remember(plays) { MusicStats.periodKeys() }
        val songs = remember(k, all, plays, favs, period, days) { k.songs(all, plays, favs, period, days, keys) }
        val most = k == SmartKind.MostPlayed
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { smart = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(k.label), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { play(c, songs, 0) }, enabled = songs.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play)) }
            }
            if (most) {
                val month = remember { java.time.LocalDate.now().month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale.getDefault()) }
                SpanChips(listOf(PlayPeriod.Week to stringResource(R.string.music_this_week), PlayPeriod.Month to month, PlayPeriod.All to stringResource(R.string.music_all_time)), period) { period = it }
            } else if (k == SmartKind.RecentlyAdded) {
                SpanChips(listOf(15, 30, 60, 365).map { it to stringResource(R.string.music_past_days, it) } + (0 to stringResource(R.string.music_all_time)), days) { days = it }
            }
            // Most played: time spent listening (plays × length); the other lists: how long they take to play through.
            ListSummary(songs.size, if (most) SmartLists.listened(songs, Song::id, Song::durationMs, plays, period, keys.first, keys.second) else songs.sumOf { it.durationMs })
            if (songs.isEmpty()) Text(stringResource(k.empty), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else SongList(songs, c, favs, if (most) { s -> SmartLists.plays(plays[s.id], period, keys.first, keys.second) } else null) { menuFor = it }
        }
    } ?: openList?.let { name ->
        BackHandler { openList = null }
        val songs = remember(name, all, listsVersion) { lists.songs(name, all) }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openList = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { play(c, songs, 0) }, enabled = songs.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play)) }
                ListMenu({ renameList = name }, { deleteList = name })
            }
            ListSummary(songs.size, songs.sumOf { it.durationMs })
            if (songs.isEmpty()) Text(stringResource(R.string.music_list_empty), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else SongList(songs, c, favs) { menuFor = it }
        }
    } ?: open?.let { (name, songs) ->
        BackHandler { open = null }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { play(c, songs, 0) }) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play)) }
            }
            ListSummary(songs.size, songs.sumOf { it.durationMs })
            SongList(songs, c, favs) { menuFor = it }
        }
    } ?: Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true,
            label = { Text(stringResource(R.string.search)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibTab.entries.forEach { t -> FilterChip(tab == t, { tab = t }, { Text(stringResource(t.label)) }) }
            VerticalDivider(Modifier.height(32.dp).align(Alignment.CenterVertically))
            FilterChip(az, { az = !az }, { Text(stringResource(if (az) R.string.music_sort_az else R.string.music_sort_new)) })
        }
        val q = query.trim()
        val hits = remember(all, q, az) {
            val found = if (q.isEmpty()) all else all.filter { listOf(it.title, it.artist, it.album).any { f -> f.contains(q, true) } }
            if (az) found.sortedWith(compareBy(NaturalOrder) { it.title }) else found // the library comes newest first
        }
        // Grouping and sorting once per (list, tab), not on every recomposition the 4 Hz position tick causes.
        val groups = remember(hits, tab, listsVersion, az) {
            fun by(songs: List<Song>, key: (Song) -> String) = songs.groupBy(key).toSortedMap(NaturalOrder).map { it.key to it.value }
            when (tab) {
                LibTab.Songs -> emptyList()
                LibTab.Albums -> by(hits) { it.album }
                LibTab.Genres -> by(hits.filter { it.genre.isNotBlank() }) { it.genre }
                LibTab.Artists -> by(hits) { it.artist.ifEmpty { "—" } }
                LibTab.Folders -> if (az) by(hits) { it.folder } else hits.groupBy { it.folder }.toList() // newest: the folder with the latest song leads
                LibTab.Lists -> lists.names().map { it to lists.songs(it, all) }
            }
        }
        val allLabel = stringResource(R.string.music_all_songs)
        when (tab) {
            LibTab.Songs -> SongList(hits, c, favs) { menuFor = it }
            LibTab.Albums -> AlbumGrid(groups) { n, s -> open = n to s.sortedWith(compareBy<Song> { it.track }.thenBy(NaturalOrder) { it.title }) }
            LibTab.Lists -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "all") {
                    Row(Modifier.fillMaxWidth().clickable { open = allLabel to hits }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(52.dp), shape = Radius.S, color = MaterialTheme.colorScheme.secondaryContainer) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.LibraryMusic, null) } }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(allLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(pluralStringResource(R.plurals.music_count, hits.size, hits.size), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(SmartKind.entries, key = { "smart-" + it.name }) { k ->
                    Row(Modifier.fillMaxWidth().clickable { smart = k }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(52.dp), shape = Radius.S, color = MaterialTheme.colorScheme.secondaryContainer) { Box(contentAlignment = Alignment.Center) { Icon(k.icon, null) } }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(k.label), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(pluralStringResource(R.plurals.music_count, smartSongs.getValue(k).size, smartSongs.getValue(k).size), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item(key = "yours") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.music_your_lists), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { newList = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.music_new_list_button)) }
                    }
                }
                if (groups.isEmpty()) item(key = "none") { Text(stringResource(R.string.music_lists_empty), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center) }
                items(groups, key = { "list-" + it.first }) { (name, songs) -> GroupRow(name, songs, { ListMenu({ renameList = name }, { deleteList = name }) }) { openList = name } }
            }
            else -> GroupList(groups) { n, s -> open = n to s }
        }
    }
    if (newList) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newList = false }, title = { Text(stringResource(R.string.music_new_list)) },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text(stringResource(R.string.ring_name)) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { lists.create(name.trim()); listsVersion++; newList = false }) { Text(stringResource(R.string.music_create)) } },
            dismissButton = { TextButton(onClick = { newList = false }) { Text(stringResource(R.string.cancel)) } })
    }
    menuFor?.let { s ->
        val inList = openList
        SongMenu(s, c, onDismiss = { menuFor = null }, onAddToList = { addTo = s }, onRingtone = { ringtone = s },
            onRemove = inList?.let { n -> { lists.remove(n, s.id); listsVersion++ } })
    }
    renameList?.let { old ->
        var name by remember(old) { mutableStateOf(old) }
        val t = name.trim()
        val taken = t != old && lists.exists(t)
        AlertDialog(onDismissRequest = { renameList = null }, title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text(stringResource(R.string.ring_name)) },
                isError = taken, supportingText = if (taken) { { Text(stringResource(R.string.music_list_taken)) } } else null) },
            confirmButton = { TextButton(enabled = t.isNotEmpty() && !taken, onClick = {
                if (t == old || lists.rename(old, t)) { if (openList == old) openList = t; listsVersion++ }
                renameList = null
            }) { Text(stringResource(R.string.rename)) } },
            dismissButton = { TextButton(onClick = { renameList = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deleteList?.let { n ->
        AlertDialog(onDismissRequest = { deleteList = null }, title = { Text(stringResource(R.string.music_delete_list)) },
            text = { Text(stringResource(R.string.music_delete_list_q, n)) },
            confirmButton = { TextButton(onClick = { lists.delete(n); if (openList == n) openList = null; listsVersion++; deleteList = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteList = null }) { Text(stringResource(R.string.cancel)) } })
    }
    ringtone?.let { s -> RingtoneMaker(s) { ringtone = null } }
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
private fun SongList(songs: List<Song>, c: MediaController?, favs: List<Long>, badge: ((Song) -> Int)? = null, onLong: (Song) -> Unit) {
    val fav = remember(favs) { favs.toHashSet() }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, s.id in fav, badge?.invoke(s), { play(c, songs, i) }, { onLong(s) }) }
    }
}

/** The line on top of an open list: how many songs and how long (as Musicolet shows it). */
@Composable
private fun ListSummary(count: Int, totalMs: Long) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(R.plurals.music_count, count, count), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Icon(Icons.Rounded.Timer, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(formatTime(totalMs), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

/** The span chips of a ready-made list (this week / this month / all time, or past N days). */
@Composable
private fun <T> SpanChips(options: List<Pair<T, String>>, selected: T, pick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> FilterChip(v == selected, { pick(v) }, { Text(label) }) }
    }
}

/** Decoded covers by song and request size (not album: downloads with no album tag all share their folder's album id), bounded by bytes, so scrolling back never decodes again or flashes the placeholder. */
private val artCache = object : LruCache<Pair<Long, Int>, Bitmap>(32 shl 20) { override fun sizeOf(key: Pair<Long, Int>, value: Bitmap) = value.byteCount }
/** Songs with no cover, by song and size: asked once per run. Per song, not per album, so one bare track never hides the art its album mates carry. */
private val artMissing: MutableSet<Pair<Long, Int>> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
/** Three decodes at a time: a fast fling must not flood the IO pool and starve the audio and list threads. */
private val artIo = Dispatchers.IO.limitedParallelism(3)

/** One group of sound options on its own card. */
@Composable
private fun SoundCard(title: String?, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = dev.altayeb.shoebill.ui.theme.Radius.L, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
                    Text(songs.first().artist.ifEmpty { pluralStringResource(R.plurals.music_count, songs.size, songs.size) }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun GroupList(groups: List<Pair<String, List<Song>>>, open: (String, List<Song>) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(groups, key = { it.first }) { (name, songs) -> GroupRow(name, songs) { open(name, songs) } }
    }
}

@Composable
private fun GroupRow(name: String, songs: List<Song>, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        songs.firstOrNull()?.let { Art(it, 52) } ?: Surface(Modifier.size(52.dp), shape = Radius.S, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.music_count, songs.size, songs.size), style = MaterialTheme.typography.bodySmall)
        }
        trailing?.invoke()
    }
}

/** ⋮ on one of your playlists: rename or delete it. */
@Composable
private fun ListMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { shown = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.music_list_options)) }
        DropdownMenu(shown, { shown = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.rename)) }, { shown = false; onRename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem({ Text(stringResource(R.string.delete)) }, { shown = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(s: Song, favorite: Boolean, plays: Int?, onClick: () -> Unit, onLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(s, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(s.artist, s.album).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (favorite) Icon(Icons.Rounded.Favorite, stringResource(R.string.music_favorite), Modifier.padding(end = 8.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
        // Most played: the play count of the chosen span, over the length.
        if (plays != null) Column(horizontalAlignment = Alignment.End) {
            Surface(shape = Radius.S, color = MaterialTheme.colorScheme.primaryContainer) {
                Text("$plays", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(2.dp))
            Text(formatTime(s.durationMs), style = MaterialTheme.typography.labelMedium)
        } else Text(formatTime(s.durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Art(s: Song, sizeDp: Int) {
    val ctx = LocalContext.current
    // Asked at the size drawn (rounded up to a bucket), so the 280 dp cover is not a 256 px thumbnail stretched.
    val px = artBucket(with(LocalDensity.current) { sizeDp.dp.roundToPx() })
    val key = s.id to px
    // State keyed on the song: a track change shows its own cover or the placeholder at once, never the previous cover.
    val bmp = remember(s.id, px) { mutableStateOf(artCache.get(key)) }
    LaunchedEffect(s.id, px) {
        if (bmp.value != null || (s.id to px) in artMissing) return@LaunchedEffect
        val b = withContext(artIo) {
            if (Build.VERSION.SDK_INT >= 29) runCatching { ctx.contentResolver.loadThumbnail(s.uri, Size(px, px), null) }.getOrNull() else null
        }
        if (b != null) artCache.put(key, b) else artMissing += s.id to px
        bmp.value = b
    }
    val shape = Radius.S
    val b = bmp.value
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(sizeDp.dp).clip(shape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(sizeDp.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null) }
}

private fun settle(host: MusicHost, scope: CoroutineScope, springOpen: FiniteAnimationSpec<Float>, springClose: FiniteAnimationSpec<Float>, velocity: Float = 0f, density: Float = host.density) {
    // A fling down on the closed bar turns the music off and the bar goes away.
    val pulled = host.pull; host.pull = 0f
    if ((velocity > 300f || pulled > 48f * density) && host.sheet.value < 0.05f) { host.controller?.let(::close); return }
    val open = if (kotlin.math.abs(velocity) > 300f) velocity < 0 else host.sheet.value > 0.3f
    scope.launch { host.sheet.animateTo(if (open) 1f else 0f, if (open) springOpen else springClose) }
}

@Composable
private fun rememberSheetDrag(host: MusicHost, scope: CoroutineScope): DraggableState {
    val range = LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
    return androidx.compose.foundation.gestures.rememberDraggableState { d ->
        // How far the closed bar is pulled down past its rest: enough of it turns the music off (see settle).
        host.pull = if (host.sheet.value <= 0f && d > 0f) host.pull + d else 0f
        scope.launch { host.sheet.snapTo((host.sheet.value - d / range).coerceIn(0f, 1f)) }
    }
}

/** Music off: stop and empty the queue, so the bar and the notification go away. */
private fun close(c: MediaController) { c.stop(); c.clearMediaItems() }

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
        host.song?.let { Art(it, 44); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(s.current?.mediaMetadata?.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(s.current?.mediaMetadata?.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { if (s.playing) c.pause() else c.play() }, shapes = IconButtonDefaults.shapes()) {
            Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (s.playing) R.string.pause else R.string.play))
        }
        // Paused: next is of little use, so the place turns into close.
        if (s.playing) IconButton(onClick = { c.seekToNext() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next)) }
        else IconButton(onClick = { close(c) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, stringResource(R.string.music_close)) }
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
    val shown by remember(host) { androidx.compose.runtime.derivedStateOf { host.sheet.value > 0f || host.sheet.isRunning } }
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
                NowPlaying(c, host, Modifier.height(with(density) { full.toDp() }).statusBarsPadding().navigationBarsPadding(), { host.sheet.value }) {
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
private fun NowPlaying(c: MediaController, host: MusicHost, modifier: Modifier, progress: () -> Float, close: () -> Unit) {
    val ctx = LocalContext.current
    val s = host.state
    val song = host.song
    var page by remember { mutableIntStateOf(0) } // 0 art, 1 lyrics, 2 queue, 3 sound
    val prefs by AppSettings.get(ctx).settings.collectAsState()
    val view = androidx.compose.ui.platform.LocalView.current
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
                (androidx.compose.animation.fadeIn(motion.defaultEffectsSpec()) + androidx.compose.animation.scaleIn(motion.defaultSpatialSpec(), initialScale = 0.94f)) togetherWith androidx.compose.animation.fadeOut(motion.fastEffectsSpec())
            }, contentAlignment = Alignment.Center, label = "npPage") { pageNow -> when (pageNow) {
                // Reads the raw value, so the art alone shows the spring's overshoot past 1.
                0 -> song?.let {
                    // Paused art relaxes into a cookie; playing art is a plain square. Material's own shape morph.
                    val morph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie12Sided) }
                    val cookie by androidx.compose.animation.core.animateFloatAsState(if (s.playing || !prefs.coverMorph) 0f else 1f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "artMorph")
                    // Remembered: a new Shape instance each recomposition would re-clip the cover even when the morph is still.
                    val shape = remember(cookie) { MorphShape(morph, cookie) }
                    Box(Modifier.graphicsLayer { val v = 0.82f + 0.18f * progress(); scaleX = v; scaleY = v }.clip(shape)) { Art(it, 280) }
                }
                1 -> LyricsPage(lyrics, { s.position }, { c.seekTo(it) }) { pick.launch(arrayOf("*/*")) }
                2 -> QueuePage(c, s)
                else -> SoundPage(c, s)
            } }
        }
        Column(Modifier.fillMaxWidth().arrive(progress, 0.35f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(48.dp)) // balances the heart, so the title stays centred
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.current?.mediaMetadata?.title?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(s.current?.mediaMetadata?.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val id = s.current?.mediaId?.toLongOrNull()
                if (id != null) FavoriteButton(id) else Spacer(Modifier.size(48.dp))
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.arrive(progress, 0.45f)) {
                QuickRow(c, s)
                SeekRow(c, s)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconToggleButton(s.shuffle, { c.shuffleModeEnabled = it }, shapes = IconButtonDefaults.toggleableShapes(), colors = onColors()) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.music_shuffle)) }
                    IconButton(onClick = { if (prefs.rewindBeforePrevious) c.seekToPrevious() else c.seekToPreviousMediaItem() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(36.dp)) }
                    FilledIconButton(onClick = { if (s.playing) c.pause() else c.play() }, modifier = Modifier.size(72.dp), shapes = IconButtonDefaults.shapes()) {
                        val m = MaterialTheme.motionScheme
        AnimatedContent(s.playing, transitionSpec = { (androidx.compose.animation.fadeIn(m.defaultEffectsSpec()) + androidx.compose.animation.scaleIn(m.defaultSpatialSpec(), 0.5f)) togetherWith androidx.compose.animation.fadeOut(m.defaultEffectsSpec()) }, label = "playpause") { playing ->
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (playing) R.string.pause else R.string.play), Modifier.size(40.dp))
        }
                    }
                    IconButton(onClick = { c.seekToNext() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next), Modifier.size(36.dp)) }
                    IconToggleButton(s.repeat != Player.REPEAT_MODE_OFF, { c.repeatMode = (s.repeat + 1) % 3 }, shapes = IconButtonDefaults.toggleableShapes(), colors = onColors()) {
                        Icon(if (s.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, stringResource(R.string.music_repeat))
                    }
                }
            }
        }
    }
}

@Composable
private fun onColors() = IconButtonDefaults.iconToggleButtonColors(
    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer, checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer)

/** Speed and sleep timer one tap from the player: each tap steps to the next value, and a chip that is not at its default is tinted. */
@Composable
private fun QuickRow(c: MediaController, s: PlayerState) {
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    val naps = listOf(0, 15, 30, 60)
    val sleepAt = MusicService.sleepAt
    val nap = if (sleepAt > 0L) MusicService.sleepMinutes else 0
    val tint = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, labelColor = MaterialTheme.colorScheme.onSecondaryContainer, leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer)
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        AssistChip({ c.setPlaybackSpeed(speeds[(speeds.indexOfFirst { kotlin.math.abs(it - s.speed) < 0.01f } + 1) % speeds.size]) },
            { Text("${s.speed}\u00D7") }, leadingIcon = { Icon(Icons.Rounded.Speed, stringResource(R.string.player_speed), Modifier.size(18.dp)) },
            colors = if (kotlin.math.abs(s.speed - 1f) > 0.01f) tint else AssistChipDefaults.assistChipColors())
        AssistChip({ MusicService.setSleep(naps[(naps.indexOf(nap) + 1) % naps.size]) },
            { Text(if (nap == 0) stringResource(R.string.off) else stringResource(R.string.player_minutes_short, nap.toString())) },
            leadingIcon = { Icon(Icons.Rounded.Bedtime, stringResource(R.string.player_sleep_timer), Modifier.size(18.dp)) },
            colors = if (nap != 0) tint else AssistChipDefaults.assistChipColors())
    }
}

/** The only part of the page that reads the position in composition, so the 4 Hz tick recomposes this row and nothing else. */
@Composable
private fun SeekRow(c: MediaController, s: PlayerState) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = s.duration.coerceAtLeast(1)
    Slider(drag ?: (s.position.toFloat() / dur).coerceIn(0f, 1f), { drag = it }, onValueChangeFinished = { drag?.let { c.seekTo((it * dur).toLong()) }; drag = null })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(s.position), style = MaterialTheme.typography.labelMedium)
        Text(formatTime(s.duration), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LyricsPage(text: String?, pos: () -> Long, seek: (Long) -> Unit, pick: () -> Unit) {
    val large = AppSettings.get(LocalContext.current).settings.collectAsState().value.lyricsLarge
    val lines = remember(text) { text?.let(Lrc::parse).orEmpty() }
    if (lines.isEmpty()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text?.takeIf { it.isNotBlank() } ?: stringResource(R.string.music_no_lyrics), textAlign = TextAlign.Center)
            TextButton(onClick = pick) { Text(stringResource(R.string.music_pick_lyrics)) }
        }
        return
    }
    // Derived: the list recomposes when the sung line changes, not on every position tick.
    val now by remember(lines) { derivedStateOf { Lrc.indexAt(lines, pos()) } }
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
    val items = remember(c, s.queueVersion) { (0 until c.mediaItemCount).map { c.getMediaItemAt(it) } }
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
private fun SoundPage(c: MediaController, s: PlayerState) {
    val ctx = LocalContext.current
    val sleepAt = MusicService.sleepAt
    val sleepMinutes = MusicService.sleepMinutes
    val setSleep = { m: Int -> MusicService.setSleep(m) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SoundCard(stringResource(R.string.player_speed)) {
            dev.altayeb.shoebill.ui.ConnectedChoice(listOf(0.75f, 1f, 1.25f, 1.5f, 2f).map { it to "$it×" }, listOf(0.75f, 1f, 1.25f, 1.5f, 2f).firstOrNull { kotlin.math.abs(s.speed - it) < 0.01f }) { c.setPlaybackSpeed(it) }
        }
        SoundCard(stringResource(R.string.player_sleep_timer)) {
            val off = stringResource(R.string.off)
            dev.altayeb.shoebill.ui.ConnectedChoice(listOf(0 to off) + listOf(15, 30, 60).map { it to stringResource(R.string.player_minutes_short, it.toString()) }, if (sleepAt > 0L) sleepMinutes else 0, pick = setSleep)
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
