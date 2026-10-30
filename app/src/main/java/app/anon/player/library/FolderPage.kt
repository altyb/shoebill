package app.anon.player.library

import app.anon.player.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.annotation.StringRes

import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.anon.player.data.PlaybackStore
import app.anon.player.ui.EmptyState
import app.anon.player.ui.SearchPill
import kotlinx.coroutines.launch

private enum class SortBy(@StringRes val label: Int) { Name(R.string.lib_name), Date(R.string.lib_date), Size(R.string.lib_size), Duration(R.string.lib_duration) }
private enum class QuickFilter(@StringRes val label: Int) { All(R.string.lib_filter_all), Unplayed(R.string.lib_filter_unplayed), FourK(R.string.lib_filter_4k) }

/** ش-03: one folder. `folder` is the MediaStore BUCKET_ID. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderBody(folder: String, onBack: () -> Unit, onPlay: (Uri, String) -> Unit) = VideoGate { all ->
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by PlaybackStore.get(ctx).all.collectAsStateWithLifecycle()
    val videos = remember(all, folder) { all.filter { it.bucketId == folder } }
    val actions = rememberVideoActions { scope.launch { VideoIndex.refresh(ctx) } }

    var grid by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SortBy.Name) }
    var quick by rememberSaveable { mutableStateOf(QuickFilter.All) }
    var sortMenu by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var target by remember { mutableStateOf<Video?>(null) }
    val toggle: (Video) -> Unit = { v -> selected = if (v.key in selected) selected - v.key else selected + v.key }

    BackHandler(selected.isNotEmpty()) { selected = emptySet() }

    val shown = remember(videos, states, sort, quick, query) {
        videos
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .filter {
                when (quick) {
                    QuickFilter.All -> true
                    QuickFilter.Unplayed -> it.key !in states
                    QuickFilter.FourK -> it.quality == "4K"
                }
            }
            .let {
                when (sort) {
                    SortBy.Name -> it.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { v -> v.name })
                    SortBy.Date -> it.sortedByDescending { v -> v.dateAdded }
                    SortBy.Size -> it.sortedByDescending { v -> v.sizeBytes }
                    SortBy.Duration -> it.sortedByDescending { v -> v.durationMs }
                }
            }
    }
    val picked = remember(videos, selected) { videos.filter { it.key in selected } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(videos.firstOrNull()?.folder ?: "", style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pluralStringResource(R.plurals.lib_video_count, videos.size, videos.size)} · ${formatSize(videos.sumOf { it.sizeBytes })}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(Icons.Rounded.Search, stringResource(R.string.search))
                    }
                    IconButton(onClick = { grid = !grid }) {
                        Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, stringResource(if (grid) R.string.lib_view_list else R.string.lib_view_grid))
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, stringResource(R.string.lib_sort)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortBy.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(s.label)) },
                                    onClick = { sort = s; sortMenu = false },
                                    trailingIcon = { if (s == sort) Icon(Icons.Rounded.Check, null) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    searching,
                    enter = fadeIn(tween(200)) + expandVertically(),
                    exit = fadeOut(tween(150)) + shrinkVertically(),
                ) {
                    SearchPill(query, { query = it }, stringResource(R.string.lib_search_folder),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickFilter.entries.forEach { q ->
                        FilterChip(selected = quick == q, onClick = { quick = q }, label = { Text(stringResource(q.label)) })
                    }
                }
                if (shown.isEmpty()) {
                    EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.lib_no_videos_here), Modifier.weight(1f), body = stringResource(R.string.lib_no_videos_here_body))
                } else if (grid) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(shown, key = { it.key }) { v ->
                            VideoCard(
                                v, states[v.key], v.key in selected,
                                onClick = { if (selected.isNotEmpty()) toggle(v) else onPlay(v.uri, v.name) },
                                onLongClick = { toggle(v) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        itemsIndexed(shown, key = { _, v -> v.key }) { i, v ->
                            VideoRow(
                                v, states[v.key], query, groupedShape(i, shown.size), v.key in selected,
                                onClick = { if (selected.isNotEmpty()) toggle(v) else onPlay(v.uri, v.name) },
                                onLongClick = { toggle(v) },
                                onMore = { target = v },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
            if (selected.isNotEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 6.dp,
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${picked.size}", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 12.dp))
                        IconButton(onClick = { actions.share(picked) }) { Icon(Icons.Rounded.Share, stringResource(R.string.lib_share)) }
                        if (Build.VERSION.SDK_INT >= 30) {
                            IconButton(onClick = { actions.trash(picked) { selected = emptySet() } }) {
                                Icon(Icons.Rounded.Delete, stringResource(R.string.lib_trash), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.lib_clear_selection)) }
                    }
                }
            }
        }
    }

    target?.let { v ->
        OptionsSheet(v, states[v.key], actions, onPlay = onPlay, onDismiss = { target = null })
    }
}
