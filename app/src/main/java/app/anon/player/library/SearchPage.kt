package app.anon.player.library

import app.anon.player.R
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.anon.player.data.PlaybackStore
import app.anon.player.ui.EmptyState
import app.anon.player.ui.SearchPill
import kotlinx.coroutines.launch

/** ش-04: full-screen search over every folder. Recent searches live in SharedPreferences "search". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBody(onBack: () -> Unit, onPlay: (Uri, String) -> Unit) = VideoGate { all ->
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by PlaybackStore.get(ctx).all.collectAsStateWithLifecycle()
    val actions = rememberVideoActions { scope.launch { VideoIndex.refresh(ctx) } }

    var query by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf(Recents.list(ctx)) }
    var target by remember { mutableStateOf<Video?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val folders = remember(all) { all.distinctBy { it.bucketId }.map { it.bucketId to it.folder } }
    val results = remember(all, query, folder) {
        if (query.isBlank()) emptyList() else all.filter {
            (folder == null || it.bucketId == folder) && it.name.contains(query, ignoreCase = true)
        }
    }

    fun commit() {
        Recents.add(ctx, query.trim())
        recents = Recents.list(ctx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    SearchPill(query, { query = it }, stringResource(R.string.lib_search_video), Modifier.fillMaxWidth().padding(end = 12.dp),
                        onSearch = { commit() }, focusRequester = focus)
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = folder == null, onClick = { folder = null }, label = { Text(stringResource(R.string.lib_all_folders)) })
                folders.forEach { (id, name) ->
                    FilterChip(
                        selected = folder == id,
                        onClick = { folder = if (folder == id) null else id },
                        label = { Text(name) },
                    )
                }
            }
            AnimatedContent(
                targetState = when {
                    query.isBlank() -> 0
                    results.isEmpty() -> 1
                    else -> 2
                },
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                label = "search",
            ) { mode ->
                when (mode) {
                    0 -> if (recents.isNotEmpty()) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(stringResource(R.string.lib_recent_searches), style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = { Recents.clear(ctx); recents = emptyList() }) { Text(stringResource(R.string.clear)) }
                            }
                            LazyColumn {
                                items(recents) { r ->
                                    ListItem(
                                        headlineContent = { Text(r) },
                                        leadingContent = { Icon(Icons.Rounded.History, null) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        modifier = Modifier.clickable { query = r },
                                    )
                                }
                            }
                        }
                    } else {
                        EmptyState(Icons.AutoMirrored.Rounded.ManageSearch, stringResource(R.string.lib_search_empty), body = stringResource(R.string.lib_search_empty_body))
                    }
                    1 -> EmptyState(
                        Icons.Rounded.SearchOff,
                        stringResource(R.string.lib_no_results_for, query.trim()),
                        body = stringResource(R.string.lib_no_results_body),
                        action = {
                            if (folder != null) TextButton(onClick = { folder = null }) { Text(stringResource(R.string.lib_search_all_folders)) }
                        },
                    )
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        itemsIndexed(results, key = { _, v -> v.key }) { i, v ->
                            Box(Modifier.padding(vertical = 1.dp).animateItem()) {
                                VideoRow(
                                    v, states[v.key], query, groupedShape(i, results.size), false,
                                    onClick = { commit(); onPlay(v.uri, v.name) },
                                    onLongClick = { target = v },
                                    onMore = { target = v },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    target?.let { v -> OptionsSheet(v, states[v.key], actions, onPlay = onPlay, onDismiss = { target = null }) }
}

/** Last 8 searches, newest first. */
private object Recents {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("search", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<String> =
        prefs(ctx).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun add(ctx: Context, q: String) {
        if (q.isBlank()) return
        val next = (listOf(q) + list(ctx).filter { it != q }).take(8)
        prefs(ctx).edit().putString("recent", next.joinToString("\n")).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("recent").apply()
    }
}
