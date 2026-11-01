@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.altayeb.shoebill.library

import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import dev.altayeb.shoebill.data.FileState
import dev.altayeb.shoebill.data.PlaybackStore
import dev.altayeb.shoebill.ui.theme.BrandAmber
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.runtime.LaunchedEffect
import dev.altayeb.shoebill.download.DownloadFeature
import dev.altayeb.shoebill.download.SharedLink
import dev.altayeb.shoebill.music.MUSIC_TAB
import dev.altayeb.shoebill.music.MiniPlayer
import dev.altayeb.shoebill.music.MusicHost
import dev.altayeb.shoebill.music.MusicSheet
import dev.altayeb.shoebill.music.MusicTab
import dev.altayeb.shoebill.ui.theme.Radius

// Index order is what HomeScreen switches on. Downloads exists only in the full build, so it stays last.
private val homeTabs = listOf(
    R.string.lib_tab_library to Icons.Rounded.VideoLibrary,
    R.string.lib_tab_playlists to Icons.Rounded.PlaylistPlay,
    R.string.lib_tab_network to Icons.Rounded.Lan,
    R.string.lib_tab_music to Icons.Rounded.MusicNote,
) + if (DownloadFeature.ENABLED) listOf(R.string.lib_tab_downloads to Icons.Rounded.Download) else emptyList()

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
    val shared by SharedLink.value.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { if (shared != null && DownloadFeature.ENABLED) tab = 4 }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val existing = remember(videos) { videos.mapTo(HashSet()) { it.key } }
    // Continue-watching skips files that were trashed or removed since they were played.
    val cont = remember(states, existing) {
        PlaybackStore.get(ctx).continueWatching(50).filter { it.key in existing }
    }
    val folders = remember(videos, states) { folderSummaries(videos, states) }
    // One movie per folder (the common case): show the newest movies as cards so there are pictures up top.
    val singles = folders.isNotEmpty() && folders.all { it.count == 1 }
    val latest = remember(videos) { videos.sortedByDescending { it.dateAdded }.take(6) }

    val music = dev.altayeb.shoebill.music.rememberMusicHost(tab)
    var dockHeight by remember { mutableStateOf(0) }
    // Derived, so the sheet's animation does not recompose the whole home screen on every frame.
    val sheetFull by remember(music) { androidx.compose.runtime.derivedStateOf { music.sheet.value >= 1f } }
    val motion = MaterialTheme.motionScheme
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        // With the sheet fully open, the tabs under it are hidden from screen readers too.
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection).then(if (sheetFull) Modifier.clearAndSetSemantics {} else Modifier),
        topBar = {
            // One header on every tab: the mark in the same spot, only the words change.
            val name = if (tab == 0) "Shoebill" else stringResource(homeTabs[tab].first)
            TopAppBar(
                title = {
                    androidx.compose.animation.AnimatedContent(name, transitionSpec = {
                        androidx.compose.animation.fadeIn(motion.fastEffectsSpec()) togetherWith androidx.compose.animation.fadeOut(motion.fastEffectsSpec())
                    }, label = "headerTitle") { Text(it, fontWeight = FontWeight.Bold, maxLines = 1) }
                },
                navigationIcon = { Logo(onLongClick = onVault) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title)) } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = {
            HomeDock(music, tab, Modifier.onSizeChanged { dockHeight = it.height }) { tab = it }
        },
    ) { pad ->
        // Plain fade between tabs: scaling a whole list of thumbnails costs frames for little gain.
        androidx.compose.animation.AnimatedContent(tab, transitionSpec = {
            androidx.compose.animation.fadeIn(motion.defaultEffectsSpec()) togetherWith androidx.compose.animation.fadeOut(motion.fastEffectsSpec())
        }, label = "tabs") { tab ->
        when (tab) {
            1 -> { PlaylistsTab(onPlayQueue, Modifier.padding(pad)); return@AnimatedContent }
            2 -> { NetworkTab(onPlay, Modifier.padding(pad)); return@AnimatedContent }
            MUSIC_TAB -> { MusicTab(music, Modifier.padding(pad)); return@AnimatedContent }
            4 -> { DownloadFeature.Tab(Modifier.padding(pad)); return@AnimatedContent }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Surface(
                    onClick = onSearch,
                    shape = Radius.XL,
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    MusicSheet(music, dockHeight)
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
            Modifier.fillMaxSize().clip(Radius.XL).pressScale(src)
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
            Thumb(f.cover.uri, Modifier.fillMaxWidth(0.36f).widthIn(max = 128.dp).aspectRatio(16f / 9f), Radius.M,
                durationMs = f.cover.durationMs)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pluralStringResource(R.plurals.lib_video_count, f.count, f.count)} · ${formatSize(f.bytes)}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
}

/** One surface from the mini player down to the tabs. The tabs are Material 3 Expressive's own bar, with its own motion. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeDock(music: MusicHost, tab: Int, modifier: Modifier, onTab: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = cs.surfaceContainer) {
        Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
            if (music.hasTrack) MiniPlayer(music)
            // Narrow phones get one smaller size for all five labels, so they stay equal and do not collide.
            val navLabel = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360) MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp) else MaterialTheme.typography.labelMedium
            ShortNavigationBar(containerColor = Color.Transparent) {
                homeTabs.forEachIndexed { i, (labelRes, icon) ->
                    ShortNavigationBarItem(
                        selected = tab == i,
                        onClick = { onTab(i) },
                        icon = { Icon(icon, null) },
                        label = { Text(stringResource(labelRes), maxLines = 1, softWrap = false, style = navLabel, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = navLabel.fontSize)) },
                    )
                }
            }
        }
    }
}
