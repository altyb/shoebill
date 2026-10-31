package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.R
import androidx.compose.ui.res.stringResource

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.altayeb.shoebill.ui.EmptyState
import dev.altayeb.shoebill.ui.SearchPill

private val SCHEMES = listOf("http://", "https://", "rtsp://", "rtmp://", "ftp://")

/** "الشبكة" tab: play a stream link, keep the last 12. */
@Composable
fun NetworkTab(onPlay: (Uri, String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }
    var recents by remember { mutableStateOf(Streams.list(ctx)) }
    val valid = SCHEMES.any { url.trim().startsWith(it, ignoreCase = true) }

    fun play(link: String) {
        val u = link.trim()
        Streams.add(ctx, u)
        recents = Streams.list(ctx)
        onPlay(Uri.parse(u), Uri.parse(u).lastPathSegment?.takeIf { it.isNotBlank() } ?: Uri.parse(u).host ?: u)
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchPill(url, { url = it }, stringResource(R.string.lib_stream_hint), Modifier.weight(1f), onSearch = { if (valid) play(url) })
            IconButton(onClick = {
                val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                clip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { url = it.trim() }
            }) { Icon(Icons.Rounded.ContentPaste, stringResource(R.string.lib_paste)) }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { play(url) }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.PlayArrow, null)
            Text(stringResource(R.string.play), Modifier.padding(start = 8.dp))
        }
        if (url.isNotBlank() && !valid) {
            Text(stringResource(R.string.lib_stream_invalid), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        SmbSection(onPlay)
        Spacer(Modifier.height(8.dp))
        if (recents.isEmpty()) {
            EmptyState(Icons.Rounded.Wifi, stringResource(R.string.lib_network_empty), body = stringResource(R.string.lib_network_empty_body))
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lib_recent_links), style = MaterialTheme.typography.titleSmall)
            }
            LazyColumn {
                items(recents, key = { it }) { r ->
                    ListItem(
                        headlineContent = { Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Rounded.Link, null) },
                        trailingContent = {
                            IconButton(onClick = { Streams.remove(ctx, r); recents = Streams.list(ctx) }) { Icon(Icons.Rounded.Close, stringResource(R.string.remove)) }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.animateItem().clickable { play(r) },
                    )
                }
            }
        }
    }
}

private object Streams {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("streams", Context.MODE_PRIVATE)
    fun list(ctx: Context): List<String> = prefs(ctx).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun add(ctx: Context, u: String) = save(ctx, (listOf(u) + list(ctx).filter { it != u }).take(12))
    fun remove(ctx: Context, u: String) = save(ctx, list(ctx).filter { it != u })
    private fun save(ctx: Context, l: List<String>) = prefs(ctx).edit().putString("recent", l.joinToString("\n")).apply()
}
