package app.anon.player.library

import app.anon.player.R
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember

import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.anon.player.data.FileState
import app.anon.player.data.PlaybackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** ش-05: options for one video. Rename/trash/share actions come from the parent screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(
    video: Video,
    state: FileState?,
    actions: VideoActions,
    onPlay: (Uri, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val canWrite = Build.VERSION.SDK_INT >= 30

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(video.uri, Modifier.size(width = 96.dp, height = 54.dp), RoundedCornerShape(10.dp),
                    durationMs = video.durationMs, savedPosMs = state?.positionMs ?: -1)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    LtrText(AnnotatedString(video.name), MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(
                        listOfNotNull(
                            formatTime(video.durationMs),
                            video.quality.ifEmpty { null },
                            formatSize(video.sizeBytes),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            val resume = stringResource(R.string.lib_resume)
            val fromStart = stringResource(R.string.lib_from_start)
            ButtonGroup(overflowIndicator = {}, modifier = Modifier.fillMaxWidth()) {
                clickableItem(
                    onClick = { onPlay(video.uri, video.name); onDismiss() },
                    label = resume,
                    weight = 1f,
                )
                clickableItem(
                    onClick = {
                        // «من البداية»: clear the saved position only; the rest of the per-file state stays.
                        state?.let { PlaybackStore.get(ctx).save(it.copy(positionMs = 0)) }
                        onPlay(video.uri, video.name); onDismiss()
                    },
                    label = fromStart,
                    weight = 1f,
                )
            }
            Spacer(Modifier.height(8.dp))
            SheetRow(stringResource(R.string.lib_file_info), Icons.Rounded.Info) { dialog = "info" }
            SheetRow(stringResource(R.string.lib_add_to_playlist), Icons.AutoMirrored.Rounded.PlaylistAdd) { dialog = "playlist" }
            if (canWrite) SheetRow(stringResource(R.string.rename), Icons.Rounded.Edit) { dialog = "rename" }
            SheetRow(stringResource(R.string.lib_share), Icons.Rounded.Share) { actions.share(listOf(video)); onDismiss() }
            if (canWrite) {
                SheetRow(stringResource(R.string.lib_trash), Icons.Rounded.Delete, error = true) {
                    actions.trash(listOf(video))
                    onDismiss()
                }
            }
        }
    }

    when (dialog) {
        "info" -> InfoDialog(video) { dialog = null }
        "playlist" -> AddToPlaylistDialog(video.uri.toString()) { dialog = null; onDismiss() }
        "rename" -> RenameDialog(video, onConfirm = { name ->
            dialog = null
            actions.rename(video, name)
            onDismiss()
        }, onDismiss = { dialog = null })
    }
}

@Composable
private fun SheetRow(label: String, icon: ImageVector, error: Boolean = false, onClick: () -> Unit) {
    val c = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = c) },
        leadingContent = { Icon(icon, null, tint = c) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun InfoDialog(video: Video, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val path by produceState<String?>(null, video.uri) {
        value = withContext(Dispatchers.IO) { mediaPath(ctx, video.uri) }
    }
    val date = remember(video) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(video.dateAdded * 1000)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_file_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoLine(stringResource(R.string.lib_name), video.name)
                InfoLine(stringResource(R.string.lib_path), path ?: "…")
                InfoLine(stringResource(R.string.lib_size), formatSize(video.sizeBytes))
                InfoLine(stringResource(R.string.lib_resolution), if (video.width > 0) "${video.width}×${video.height}" else stringResource(R.string.lib_unknown))
                InfoLine(stringResource(R.string.lib_duration), formatTime(video.durationMs))
                InfoLine(stringResource(R.string.lib_date), date)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_done)) } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LtrText(AnnotatedString(value), MaterialTheme.typography.bodyMedium, maxLines = 3)
    }
}

@Composable
private fun RenameDialog(video: Video, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(video.name) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty() && trimmed != video.name) {
                Text(stringResource(R.string.change))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Folder path on disk (API 29+); null below that. */
private fun mediaPath(ctx: Context, uri: Uri): String? {
    if (Build.VERSION.SDK_INT < 29) return null
    return ctx.contentResolver.query(
        uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) + c.getString(1) else null }
}
