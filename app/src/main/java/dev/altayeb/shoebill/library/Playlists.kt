package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.altayeb.shoebill.ui.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Playlist(val id: String, val name: String, val items: List<String>)

class PlaylistStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<Playlist>> = _all

    private fun read(): List<Playlist> = runCatching {
        val arr = JSONArray(prefs.getString("all", "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val items = o.getJSONArray("items")
            Playlist(o.getString("id"), o.getString("name"), List(items.length()) { items.getString(it) })
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("items", JSONArray(p.items)))
        }
        prefs.edit().putString("all", arr.toString()).apply()
        _all.value = list
    }

    private fun edit(id: String, f: (Playlist) -> Playlist) = save(_all.value.map { if (it.id == id) f(it) else it })

    fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        save(_all.value + Playlist(id, name, emptyList()))
        return id
    }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name) }

    fun delete(id: String) = save(_all.value.filterNot { it.id == id })

    fun add(id: String, uri: String) = edit(id) { if (uri in it.items) it else it.copy(items = it.items + uri) }

    fun remove(id: String, uri: String) = edit(id) { it.copy(items = it.items - uri) }

    fun move(id: String, from: Int, to: Int) = edit(id) { p ->
        if (from !in p.items.indices || to !in p.items.indices) p
        else p.items.toMutableList().let { l -> l.add(to, l.removeAt(from)); p.copy(items = l) }
    }

    companion object {
        @Volatile private var instance: PlaylistStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: PlaylistStore(context.applicationContext).also { instance = it }
        }
    }
}

@Composable
fun PlaylistsTab(onPlayQueue: (List<Pair<Uri, String>>, Int) -> Unit, modifier: Modifier = Modifier) {
    val store = PlaylistStore.get(LocalContext.current)
    val lists by store.all.collectAsState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val current = lists.firstOrNull { it.id == openId }
    BackHandler(enabled = current != null) { openId = null }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }

    if (current != null) {
        PlaylistDetail(current, { openId = null }, onPlayQueue, modifier)
    } else {
        Box(modifier.fillMaxSize()) {
            if (lists.isEmpty()) {
                EmptyState(dev.altayeb.shoebill.R.drawable.art_empty,
                    stringResource(R.string.lib_no_playlists),
                    body = stringResource(R.string.lib_no_playlists_body),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
                    items(lists, key = { it.id }) { p ->
                        PlaylistRow(p, onOpen = { openId = p.id }, onRename = { renaming = p }, onDelete = { deleting = p })
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.lib_new_playlist)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (creating) NameDialog(stringResource(R.string.lib_new_playlist), "", stringResource(R.string.lib_create), { store.create(it); creating = false }, { creating = false })
    renaming?.let { p ->
        NameDialog(stringResource(R.string.rename), p.name, stringResource(R.string.change), { store.rename(p.id, it); renaming = null }, { renaming = null })
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.lib_delete_playlist)) },
            text = { Text(stringResource(R.string.lib_delete_playlist_q, p.name)) },
            confirmButton = {
                TextButton(onClick = { store.delete(p.id); deleting = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(p: Playlist, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember(p.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = cs.primary)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.lib_video_count, p.items.size, p.items.size), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.lib_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = cs.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(p: Playlist, onBack: () -> Unit, onPlayQueue: (List<Pair<Uri, String>>, Int) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val store = PlaylistStore.get(ctx)
    val cs = MaterialTheme.colorScheme
    // null = file gone; absent key = still loading
    val names by produceState<Map<String, String?>>(emptyMap(), p.items) {
        value = withContext(Dispatchers.IO) { p.items.associateWith { displayName(ctx, Uri.parse(it)) } }
    }
    val queue = p.items.mapNotNull { u -> names[u]?.let { Uri.parse(u) to it } }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            Text(p.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        FilledTonalButton(onClick = { onPlayQueue(queue, 0) }, enabled = queue.isNotEmpty(), modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lib_play_all))
        }
        if (p.items.isEmpty()) {
            EmptyState(dev.altayeb.shoebill.R.drawable.art_empty, stringResource(R.string.lib_playlist_empty), Modifier.weight(1f), body = stringResource(R.string.lib_playlist_empty_body))
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(p.items, key = { _, u -> u }) { i, uri ->
                    val loaded = names.containsKey(uri)
                    val name = names[uri]
                    val qi = queue.indexOfFirst { it.first.toString() == uri }
                    Row(Modifier.fillMaxWidth().animateItem(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f)
                                .clickable(enabled = qi >= 0) { onPlayQueue(queue, qi) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            when {
                                !loaded -> Text("…", color = cs.onSurfaceVariant)
                                name == null -> Text(stringResource(R.string.lib_file_missing), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                                else -> LtrText(AnnotatedString(name), MaterialTheme.typography.bodyLarge, maxLines = 2)
                            }
                        }
                        IconButton(onClick = { store.move(p.id, i, i - 1) }, enabled = i > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.lib_move_up))
                        }
                        IconButton(onClick = { store.move(p.id, i, i + 1) }, enabled = i < p.items.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.lib_move_down))
                        }
                        IconButton(onClick = { store.remove(p.id, uri) }) { Icon(Icons.Rounded.Close, stringResource(R.string.remove)) }
                    }
                }
            }
        }
    }
}

@Composable
fun AddToPlaylistDialog(uri: String, onDismiss: () -> Unit) {
    val store = PlaylistStore.get(LocalContext.current)
    val lists by store.all.collectAsState()
    var newMode by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_add_to_playlist)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (newMode) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
                } else {
                    lists.forEach { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = { Text(pluralStringResource(R.plurals.lib_video_count, p.items.size, p.items.size)) },
                            modifier = Modifier.clickable { store.add(p.id, uri); onDismiss() },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.lib_new_playlist_more)) },
                        leadingContent = { Icon(Icons.Rounded.Add, null) },
                        modifier = Modifier.clickable { newMode = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        },
        confirmButton = {
            if (newMode) {
                TextButton(onClick = { store.add(store.create(trimmed), uri); onDismiss() }, enabled = trimmed.isNotEmpty()) {
                    Text(stringResource(R.string.lib_create))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Display name of a MediaStore item; null when the file is gone or unreadable. */
private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()
