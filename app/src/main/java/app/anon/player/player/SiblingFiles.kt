package app.anon.player.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Previous (step -1) or next (step 1) video in the same MediaStore folder, sorted by name (ش-14).
 * Null for non-MediaStore uris, or at the ends of the folder.
 */
suspend fun siblingInFolder(context: Context, uri: Uri, step: Int): Pair<Uri, String>? = withContext(Dispatchers.IO) {
    if (uri.authority != MediaStore.AUTHORITY) return@withContext null
    val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return@withContext null
    val cr = context.contentResolver
    val videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val bucket = cr.query(ContentUris.withAppendedId(videos, id), arrayOf("bucket_id"), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: return@withContext null
    val list: List<Pair<Long, String>> = cr.query(
        videos, arrayOf("_id", "_display_name"), "bucket_id = ?", arrayOf(bucket), "_display_name COLLATE NOCASE ASC",
    )?.use { c ->
        buildList<Pair<Long, String>> { while (c.moveToNext()) add(c.getLong(0) to (c.getString(1) ?: "")) }
    } ?: return@withContext null
    val index = list.indexOfFirst { it.first == id }
    if (index < 0) return@withContext null
    list.getOrNull(index + step)?.let { (cid, name) -> ContentUris.withAppendedId(videos, cid) to name }
}

/** Adds sibling subtitles of [video] as cached UTF-8 copies (old Arabic windows-1256 files decode correctly). */
suspend fun attachSiblingSubs(context: Context, player: MpvPlayer, video: Uri): Int = withContext(Dispatchers.IO) {
    var n = 0
    for (u in siblingSubtitleUris(context, video)) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@runCatching
            val name = context.contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "sub"
            val file = cacheSubtitle(context, "sib-" + name, bytes, name.substringAfterLast('.').lowercase())
            player.command("sub-add", file.absolutePath, "auto", name.substringBeforeLast('.'))
            n++
        }
    }
    n
}
