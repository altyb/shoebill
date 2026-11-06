@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.altayeb.shoebill.music

import androidx.compose.material.icons.rounded.RemoveCircleOutline
import dev.altayeb.shoebill.library.logic.PlayPeriod
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.library.logic.PlayStat
import dev.altayeb.shoebill.library.logic.SmartLists
import dev.altayeb.shoebill.settings.AppSettings

/** The ready-made lists, in the order they are shown. */
enum class SmartKind(val label: Int, val empty: Int, val icon: ImageVector) {
    Favorites(R.string.music_favorites, R.string.music_favorites_empty, Icons.Rounded.Favorite),
    RecentlyAdded(R.string.music_recent_added, R.string.music_list_empty, Icons.Rounded.LibraryAdd),
    RecentlyPlayed(R.string.music_recent_played, R.string.music_played_empty, Icons.Rounded.History),
    MostPlayed(R.string.music_most_played, R.string.music_played_empty, Icons.Rounded.LocalFireDepartment),
    NotPlayed(R.string.music_not_played, R.string.music_list_empty, Icons.Rounded.Block);

    /** [period] is for Most played; [days] for Recently added (0 = all time). [keys] = MusicStats.periodKeys(). */
    fun songs(all: List<Song>, plays: Map<Long, PlayStat>, favs: List<Long>, period: PlayPeriod = PlayPeriod.All, days: Int = 0, keys: Pair<Long, Long> = 0L to 0L): List<Song> = when (this) {
        Favorites -> SmartLists.favorites(all, Song::id, favs)
        RecentlyAdded -> SmartLists.recentlyAdded(all, Song::dateAdded, if (days > 0) System.currentTimeMillis() / 1000 - days * 86_400L else 0) // date added is in seconds
        RecentlyPlayed -> SmartLists.recentlyPlayed(all, Song::id, plays)
        MostPlayed -> SmartLists.mostPlayed(all, Song::id, plays, period, keys.first, keys.second)
        NotPlayed -> SmartLists.notPlayed(all, Song::id, plays)
    }
}

@Composable
fun FavoriteButton(id: Long) {
    val stats = MusicStats.get(LocalContext.current)
    val favs by stats.favorites.collectAsStateWithLifecycle()
    val on = id in favs
    IconButton(onClick = { stats.toggleFavorite(id) }) {
        Icon(if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (on) R.string.music_unfavorite else R.string.music_favorite),
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The long-press menu of a song. Each entry can be hidden in Settings → Music → Song menu. */
enum class SongAction(val key: String, val label: Int) {
    Favorite("favorite", R.string.music_favorite), PlayNext("play_next", R.string.music_play_next), Queue("queue", R.string.music_add_queue),
    AddToList("list", R.string.music_add_to_list), Ringtone("ringtone", R.string.ring_title), Share("share", R.string.dl_share),
}

@Composable
fun SongMenu(s: Song, c: MediaController?, onDismiss: () -> Unit, onAddToList: () -> Unit, onRingtone: () -> Unit, onRemove: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val stats = remember { MusicStats.get(ctx) }
    val favs by stats.favorites.collectAsStateWithLifecycle()
    val hidden = AppSettings.get(ctx).settings.collectAsStateWithLifecycle().value.hiddenSongActions
    // Opens fully: half-open hid the last actions behind the system bar.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            ListItem(headlineContent = { Text(s.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(listOf(s.artist, s.album).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
            // Only inside one of your playlists; always shown there, it is how a list is edited.
            if (onRemove != null) ListItem(headlineContent = { Text(stringResource(R.string.music_remove_from_list)) }, leadingContent = { Icon(Icons.Rounded.RemoveCircleOutline, null) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.clickable { onRemove(); onDismiss() })
            SongAction.entries.filter { it.key !in hidden }.forEach { a ->
                val fav = s.id in favs
                val (icon, label) = when (a) {
                    SongAction.Favorite -> (if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) to (if (fav) R.string.music_unfavorite else R.string.music_favorite)
                    SongAction.PlayNext -> Icons.Rounded.PlaylistPlay to a.label
                    SongAction.Queue -> Icons.AutoMirrored.Rounded.QueueMusic to a.label
                    SongAction.AddToList -> Icons.AutoMirrored.Rounded.PlaylistAdd to a.label
                    SongAction.Ringtone -> Icons.Rounded.ContentCut to a.label
                    SongAction.Share -> Icons.Rounded.Share to a.label
                }
                ListItem(headlineContent = { Text(stringResource(label)) }, leadingContent = { Icon(icon, null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable {
                        when (a) {
                            SongAction.Favorite -> stats.toggleFavorite(s.id)
                            // Nothing queued yet: these just play the song.
                            SongAction.PlayNext -> c?.let { if (it.mediaItemCount == 0) { it.setMediaItem(s.item()); it.prepare(); it.play() } else it.addMediaItem(it.currentMediaItemIndex + 1, s.item()) }
                            SongAction.Queue -> c?.let { if (it.mediaItemCount == 0) { it.setMediaItem(s.item()); it.prepare(); it.play() } else it.addMediaItem(s.item()) }
                            SongAction.AddToList -> onAddToList()
                            SongAction.Ringtone -> onRingtone()
                            SongAction.Share -> runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, s.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)) }
                        }
                        onDismiss()
                    })
            }
        }
    }
}
