package dev.altayeb.shoebill.library

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import dev.altayeb.shoebill.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.toShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember

import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import dev.altayeb.shoebill.data.FileState
import dev.altayeb.shoebill.data.PlaybackStore
import dev.altayeb.shoebill.ui.theme.BrandAmber

// Index order is what HomeScreen switches on. Downloads exists only in the full build, so it stays last.
private val homeTabs = listOf(
    R.string.lib_tab_library to Icons.Rounded.VideoLibrary,
    R.string.lib_tab_playlists to Icons.Rounded.PlaylistPlay,
    R.string.lib_tab_network to Icons.Rounded.Lan,
    R.string.lib_tab_music to Icons.Rounded.MusicNote,
) + if (dev.altayeb.shoebill.download.DownloadFeature.ENABLED) listOf(R.string.lib_tab_downloads to Icons.Rounded.Download) else emptyList()

/** ش-02: the home screen. Tabs 2 and 3 are placeholders for now. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    onPlay: (Uri, String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onVault: () -> Unit,
    onPlayQueue: (List<Pair<Uri, String>>, Int) -> Unit,
) = VideoGate { videos ->
    val ctx = LocalContext.current
    val states by PlaybackStore.get(ctx).all.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // A link shared to the app opens on Downloads.
    val shared by dev.altayeb.shoebill.download.SharedLink.value.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(shared) { if (shared != null && dev.altayeb.shoebill.download.DownloadFeature.ENABLED) tab = 4 }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val existing = remember(videos) { videos.mapTo(HashSet()) { it.key } }
    // Continue-watching skips files that were trashed or removed since they were played.
    val cont = remember(states, existing) {
        PlaybackStore.get(ctx).continueWatching(50).filter { it.key in existing }
    }
    val folders = remember(videos, states) { folderSummaries(videos, states) }
    // One movie per folder (the common case): show the newest movies as cards so there are pictures up top.
    val singles = folders.isNotEmpty() && folders.all { it.count == 1 }
    val latest = remember(videos) { videos.sortedByDescending { it.dateAdded }.take(6) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (tab == 0) {
                val headerBg = MaterialTheme.colorScheme.surfaceContainer
                val patternColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                LargeTopAppBar(
                    title = {
                        Text("Shoebill", fontWeight = FontWeight.Bold)
                    },
                    navigationIcon = { Logo(onLongClick = onVault) },
                    actions = { IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title)) } },
                    scrollBehavior = scroll,
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                        .drawBehind { drawRect(headerBg); playPattern(patternColor) },
                )
            }
        },
        bottomBar = {
            HomeNav(tab) { tab = it }
        },
    ) { pad ->
        when (tab) {
            1 -> { PlaylistsTab(onPlayQueue, Modifier.padding(pad)); return@Scaffold }
            2 -> { NetworkTab(onPlay, Modifier.padding(pad)); return@Scaffold }
            3 -> { dev.altayeb.shoebill.music.MusicTab(Modifier.padding(pad)); return@Scaffold }
            4 -> { dev.altayeb.shoebill.download.DownloadFeature.Tab(Modifier.padding(pad)); return@Scaffold }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Surface(
                    onClick = onSearch,
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.lib_search_all_videos), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (cont.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.lib_continue_watching), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                }
                item { ContinueCarousel(cont, onPlay) }
            }
            if (singles) {
                item {
                    Text(stringResource(R.string.lib_latest_movies), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                }
                items(latest.chunked(2), key = { row -> row.first().key }) { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).animateItem(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { v ->
                            VideoCard(v, states[v.key], false,
                                onClick = { onPlay(v.uri, v.name) }, onLongClick = {},
                                modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            item {
                Text(stringResource(R.string.lib_folders), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            }
            itemsIndexed(folders, key = { _, f -> f.id }) { i, f ->
                FolderRow(f, groupedShape(i, folders.size),
                    Modifier.padding(vertical = 1.dp).animateItem()) { onOpenFolder(f.id) }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** Long-press the cookie logo → hidden vault (no visible entry). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Logo(onLongClick: () -> Unit) {
    // bare mark, no tile: the monochrome icon tinted with the text colour (108-unit canvas, 18 units margin -> 1.5x)
    Box(
        Modifier.padding(start = 8.dp).size(44.dp).clip(CircleShape)
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_monochrome), "Shoebill", Modifier.requiredSize(54.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        )
    }
}

/** Faint grid of small play triangles behind the header; one path, drawn on the header only. */
private fun DrawScope.playPattern(color: Color) {
    val step = 30.dp.toPx()
    val t = 7.dp.toPx()
    val tri = Path().apply { moveTo(0f, 0f); lineTo(t, t * 0.58f); lineTo(0f, t * 1.16f); close() }
    var row = 0
    var y = -t
    while (y < size.height + t) {
        var x = if (row % 2 == 0) 0f else step / 2
        while (x < size.width + t) {
            translate(x, y) { drawPath(tri, color) }
            x += step
        }
        y += step * 0.8f
        row++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContinueCarousel(items: List<FileState>, onPlay: (Uri, String) -> Unit) {
    val state = rememberCarouselState { items.size }
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = 240.dp,
        itemSpacing = 8.dp,
        modifier = Modifier.fillMaxWidth().height(200.dp),
    ) { i ->
        val f = items[i]
        val uri = Uri.parse(f.key)
        val frac = if (f.durationMs > 0) (f.positionMs.toFloat() / f.durationMs).coerceIn(0f, 1f) else 0f
        val src = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).pressScale(src)
                .clickable(interactionSource = src, indication = LocalIndication.current) { onPlay(uri, f.title) }
        ) {
            Thumb(uri, Modifier.fillMaxSize(), durationMs = f.durationMs, savedPosMs = f.positionMs, shared = true)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))
                )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(f.title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.lib_time_left, formatTime(f.durationMs - f.positionMs)), color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge)
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(BrandAmber))
            }
        }
    }
}

@Composable
private fun FolderRow(f: FolderInfo, shape: Shape, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = src,
        modifier = modifier.fillMaxWidth().pressScale(src),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(f.cover.uri, Modifier.size(width = 128.dp, height = 72.dp), RoundedCornerShape(16.dp),
                durationMs = f.cover.durationMs)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pluralStringResource(R.plurals.lib_video_count, f.count, f.count)} · ${formatSize(f.bytes)}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (f.fresh > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.lib_new), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Compact bottom bar: rounded top, the selected tab grows into an icon + label pill. */
@Composable
private fun HomeNav(tab: Int, onTab: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = cs.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            homeTabs.forEachIndexed { i, (labelRes, icon) ->
                val label = stringResource(labelRes)
                val selected = tab == i
                val bg by animateColorAsState(if (selected) cs.secondaryContainer else Color.Transparent, label = "nav")
                Row(
                    Modifier.clip(CircleShape).background(bg)
                        .selectable(selected, role = Role.Tab) { onTab(i) }
                        .padding(horizontal = 10.dp, vertical = 8.dp).animateContentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, label, tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
                    if (selected) Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer)
                }
            }
        }
    }
}
