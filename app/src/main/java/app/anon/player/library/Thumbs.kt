package app.anon.player.library

import app.anon.player.ui.sharedVideo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.anon.player.library.logic.fallbackFrameMs
import app.anon.player.library.logic.isMostlyBlack
import app.anon.player.library.logic.isMostlyWhite
import app.anon.player.library.logic.pickFrameMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 320x180 thumbnails. Chain: MediaMetadataRetriever at the picked frame, retry at 25% if black,
 * then MediaStore loadThumbnail (API 29+). Null means the caller draws the placeholder.
 * Memory LruCache first, then cacheDir/thumbs/<sha1>.jpg, then extraction (max 2 at once).
 */
object Thumbs {
    private const val W = 320
    private const val H = 180

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val gate = Semaphore(2)

    fun peek(uri: Uri, frameMs: Long): Bitmap? = cache.get(key(uri, frameMs))

    suspend fun load(context: Context, uri: Uri, durationMs: Long, savedPosMs: Long): Bitmap? {
        val app = context.applicationContext
        val frame = pickFrameMs(durationMs, savedPosMs)
        val k = key(uri, frame)
        cache.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(app.cacheDir, "thumbs/${sha1(k)}.jpg")
            decodeDisk(file)?.also { cache.put(k, it) } ?: gate.withPermit {
                val b = extract(app, uri, frame, durationMs)
                    ?: loadThumbnail(app, uri)
                if (b != null) {
                    cache.put(k, b)
                    writeDisk(file, b)
                }
                b
            }
        }
    }

    private fun extract(app: Context, uri: Uri, frame: Long, durationMs: Long): Bitmap? {
        val first = frameAt(app, uri, frame)
        if (first != null && !blank(first)) return first
        first?.recycle()
        val second = frameAt(app, uri, fallbackFrameMs(durationMs)) ?: return null
        if (!blank(second)) return second
        second.recycle()
        return null
    }

    private fun frameAt(app: Context, uri: Uri, frameMs: Long): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(app, uri)
            val us = frameMs * 1000
            val raw = if (Build.VERSION.SDK_INT >= 27) {
                mmr.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, W, H)
            } else {
                mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            // Pre-27 has no scaled decode; full-size frame is scaled down here.
            raw?.let { r -> if (r.width == W && r.height == H) r else Bitmap.createScaledBitmap(r, W, H, true).also { r.recycle() } }
        } catch (e: RuntimeException) {
            null
        } finally {
            mmr.release()
        }
    }

    private fun loadThumbnail(app: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching { app.contentResolver.loadThumbnail(uri, Size(W, H), null) }.getOrNull()
    }

    private fun blank(b: Bitmap) = pixels(b).let { isMostlyBlack(it) || isMostlyWhite(it) }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun decodeDisk(f: File): Bitmap? =
        if (f.isFile) BitmapFactory.decodeFile(f.path) else null

    private fun writeDisk(f: File, b: Bitmap) = runCatching {
        f.parentFile?.mkdirs()
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    private fun key(uri: Uri, frameMs: Long) = "$uri#$frameMs"

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** Off-main-thread thumbnail with a placeholder (primaryContainer to tertiaryContainer, movie icon) until it loads. */
@Composable
fun Thumb(
    uri: Uri,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    durationMs: Long = 0,
    savedPosMs: Long = -1,
    shared: Boolean = false,
) {
    val ctx = LocalContext.current
    val frame = pickFrameMs(durationMs, savedPosMs)
    val bmp by produceState(Thumbs.peek(uri, frame)?.asImageBitmap(), uri, frame, durationMs) {
        if (value == null) value = Thumbs.load(ctx, uri, durationMs, savedPosMs)?.asImageBitmap()
    }
    val placeholder = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
    )
    Box((if (shared) modifier.sharedVideo(uri) else modifier).clip(shape), contentAlignment = Alignment.Center) {
        Crossfade(targetState = bmp, animationSpec = tween(200), label = "thumb") { b ->
            if (b != null) {
                Image(bitmap = b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(placeholder), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}
