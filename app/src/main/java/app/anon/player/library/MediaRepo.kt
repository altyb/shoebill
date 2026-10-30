package app.anon.player.library

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.anon.player.data.FileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

data class Video(
    val uri: Uri,
    val name: String,
    val folder: String,
    val bucketId: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAdded: Long, // seconds, as MediaStore stores it
) {
    val key: String get() = uri.toString()
    val quality: String get() = qualityTag(width, height)
}

data class FolderInfo(val id: String, val name: String, val count: Int, val bytes: Long, val fresh: Int, val cover: Video)

/** The one shared video list. Screens read it; anything that changes MediaStore calls refresh(). */
object VideoIndex {
    private val _videos = MutableStateFlow<List<Video>?>(null)
    val videos: StateFlow<List<Video>?> = _videos

    suspend fun refresh(context: Context) {
        _videos.value = queryVideos(context.applicationContext)
    }
}

suspend fun queryVideos(context: Context): List<Video> = withContext(Dispatchers.IO) {
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.BUCKET_ID,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.DATE_ADDED,
    )
    val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
    val out = mutableListOf<Video>()
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, sort
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
        val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
        val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
        val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        while (c.moveToNext()) {
            out += Video(
                uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol).toString()),
                name = c.getString(nameCol) ?: "",
                folder = c.getString(bucketCol) ?: "",
                bucketId = c.getString(bucketIdCol) ?: "",
                durationMs = c.getLong(durCol),
                width = c.getInt(wCol),
                height = c.getInt(hCol),
                sizeBytes = c.getLong(sizeCol),
                dateAdded = c.getLong(addedCol),
            )
        }
    }
    out
}

/** One row per folder; `fresh` = videos added in the last 7 days that were never played; `cover` = newest video. */
fun folderSummaries(videos: List<Video>, states: Map<String, FileState>): List<FolderInfo> {
    val weekAgo = System.currentTimeMillis() / 1000 - 7 * 86_400
    return videos.groupBy { it.bucketId }.map { (id, vs) ->
        FolderInfo(
            id = id,
            name = vs[0].folder,
            count = vs.size,
            bytes = vs.sumOf { it.sizeBytes },
            fresh = vs.count { it.dateAdded >= weekAgo && it.key !in states },
            cover = vs.maxBy { it.dateAdded },
        )
    }.sortedBy { it.name.lowercase() }
}

/** Short edge decides the tag, so portrait phone clips read the same as landscape. */
fun qualityTag(w: Int, h: Int): String {
    val short = minOf(w, h)
    return when {
        short >= 2160 -> "4K"
        short >= 1080 -> "1080p"
        short >= 720 -> "720p"
        short > 0 -> "${short}p"
        else -> ""
    }
}

/** mm:ss, or h:mm:ss from one hour up. */
fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%02d:%02d".format(Locale.ROOT, m, s)
}

/** Decimal units, the way the Android storage screen shows them. LTR-isolated so RTL text keeps "4 MB", not "MB 4". */
fun formatSize(b: Long): String = "\u2066" + when {
    b >= 1_000_000_000L -> "%.1f GB".format(Locale.ROOT, b / 1e9)
    b >= 1_000_000L -> "%.0f MB".format(Locale.ROOT, b / 1e6)
    else -> "%.0f KB".format(Locale.ROOT, b / 1e3)
} + "\u2069"
