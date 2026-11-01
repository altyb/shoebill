package dev.altayeb.shoebill.vault

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.Size
import dev.altayeb.shoebill.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.IOException

// Import from the gallery (ق-15) and return to it: the MediaStore side of the vault.

internal fun VaultViewModel.importMedia(uris: List<Uri>) = launchOp {
    val r = repo ?: return@launchOp
    val verified = mutableListOf<Uri>()
    var failed = 0
    try {
        uris.forEachIndexed { i, uri ->
            currentCoroutineContext().ensureActive()
            val name = displayName(uri)
            _ui.update { it.copy(importing = Importing(i, uris.size, name)) }
            try {
                authed { addOne(r, uri, name) }
                verified += uri
            } catch (e: VaultAuthCancelled) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("vault", "add failed: ${e.javaClass.simpleName}") // type only: messages can hold file names
                failed++
            }
            _ui.update { it.copy(entries = r.items, thumbs = r.thumbnails()) }
        }
    } finally {
        _ui.update { it.copy(entries = r.items, thumbs = r.thumbnails()) }
    }
    val app = getApplication<Application>()
    val parts = mutableListOf(plural(R.plurals.vault_added, verified.size))
    if (failed > 0) parts += plural(R.plurals.vault_verify_failed, failed)
    if (verified.isEmpty()) {
        _ui.update { it.copy(message = parts.joinToString(" · ")) }
        return@launchOp
    }
    if (Build.VERSION.SDK_INT >= 30) {
        // the photo picker hands out media/picker/... links; the delete request only takes real MediaStore items
        val mapped = verified.map(::mediaStoreUri)
        // link shapes only (authority + path without the id), never names
        // With "All files access" the vault moves files: originals are deleted here, no dialog. Without it,
        // Android requires its "Allow … to delete?" dialog.
        val leftover = if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            mapped.filter { u -> runCatching { app.contentResolver.delete(u, null, null) > 0 }.getOrDefault(false).not() }
        } else mapped
        val pi = if (leftover.isEmpty()) null else runCatching { MediaStore.createDeleteRequest(app.contentResolver, leftover) }
            .onFailure { Log.w("vault", "createDeleteRequest failed", it) }.getOrNull()
        if (leftover.isNotEmpty() && pi == null) parts += str(R.string.vault_delete_originals_failed)
        _ui.update { it.copy(deleteRequest = pi, message = parts.joinToString(" · ")) }
    } else {
        parts += str(R.string.vault_old_android_originals)
        _ui.update { it.copy(message = parts.joinToString(" · ")) }
    }
}

private fun VaultViewModel.mediaStoreUri(u: Uri): Uri {
    if (u.authority != MediaStore.AUTHORITY || "picker" !in u.pathSegments.joinToString("/")) return u
    val id = u.lastPathSegment?.toLongOrNull() ?: return u
    val mime = getApplication<Application>().contentResolver.getType(u).orEmpty()
    val collection = if (mime.startsWith("image/")) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    return ContentUris.withAppendedId(collection, id)
}

internal fun VaultViewModel.onDeleteDialogDone() = _ui.update { it.copy(deleteRequest = null) }

private fun VaultViewModel.addOne(r: VaultRepo, uri: Uri, name: String) {
    val cr = getApplication<Application>().contentResolver
    val mime = cr.getType(uri) ?: "application/octet-stream"
    val video = mime.startsWith("video/")
    // from the source, before anything is encrypted; a format the phone cannot decode just gets the placeholder tile
    val thumb = runCatching { makeThumb(uri) }.getOrNull()
    val duration = if (video) durationOf(uri) else 0L
    r.add(name, mime, duration, thumb, originalFolder(uri)) { cr.openInputStream(uri) ?: throw IOException("no input") }
}

/** Original gallery folder (e.g. "DCIM/Camera/"), or null when it cannot be read (no permission, picker, error). */
private fun VaultViewModel.originalFolder(uri: Uri): String? = runCatching {
    getApplication<Application>().contentResolver
        .query(mediaStoreUri(uri), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}.getOrNull()?.takeIf { it.isNotBlank() }

private fun VaultViewModel.makeThumb(uri: Uri): ByteArray? {
    // ponytail: pre-Android 10 gets no thumbnail (placeholder tile); add a decode fallback if that matters.
    if (Build.VERSION.SDK_INT < 29) return null
    val bmp = getApplication<Application>().contentResolver.loadThumbnail(uri, Size(320, 180), null)
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}

private fun VaultViewModel.durationOf(uri: Uri): Long {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(getApplication(), uri)
        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        mmr.release()
    }
}

// The photo picker reports names like "754.mp4" (its own id); the real name lives on the gallery row, so ask that
// first and fall back to the picker's name, then to the link itself.
private fun VaultViewModel.displayName(uri: Uri): String {
    val cr = getApplication<Application>().contentResolver
    fun nameOf(u: Uri) = runCatching {
        cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    return nameOf(mediaStoreUri(uri)) ?: nameOf(uri) ?: uri.lastPathSegment.orEmpty()
}

internal fun VaultViewModel.exportToGallery(id: String) = returnToGallery(setOf(id))

/**
 * Moves items back to the gallery: each goes into a new MediaStore row in its original folder (default folder if that
 * is refused), and leaves the vault only after its write succeeds. A failed write deletes its half-written row.
 */
internal fun VaultViewModel.returnToGallery(ids: Set<String>) = launchOp {
    val r = repo ?: return@launchOp
    val list = ids.mapNotNull { r.find(it) }
    if (list.isEmpty()) return@launchOp
    if (Build.VERSION.SDK_INT < 29) {
        _ui.update { it.copy(message = str(R.string.vault_export_needs_android10)) }
        return@launchOp
    }
    val cr = getApplication<Application>().contentResolver
    var ok = 0
    var failed = 0
    try {
        list.forEachIndexed { i, e ->
            currentCoroutineContext().ensureActive()
            _ui.update { it.copy(message = str(R.string.vault_returning_progress, i + 1, list.size)) }
            val fallback = if (e.kind == "video") Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val name = r.nameOf(e)
            val original = r.pathOf(e)
            val target = insertPending(cr, e, name, original ?: fallback)
                ?: original?.takeIf { it != fallback }?.let { insertPending(cr, e, name, fallback) }
            if (target == null) {
                failed++
                return@forEachIndexed
            }
            try {
                // a retry reopens the output in "w" mode, so a re-prompt restarts the write instead of appending
                authed { cr.openOutputStream(target)?.use { r.exportTo(e, it) } ?: throw IOException("no output") }
                cr.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                authed { r.remove(e.id) }
                ok++
            } catch (t: Throwable) {
                cr.delete(target, null, null)
                if (t is CancellationException || t is VaultAuthCancelled) throw t
                failed++
            }
        }
    } finally {
        _ui.update { it.copy(entries = r.items, thumbs = r.thumbnails()) }
    }
    _ui.update { it.copy(message = buildString { append(plural(R.plurals.vault_returned, ok)); if (failed > 0) append(str(R.string.vault_failed_suffix, failed)) }) }
}

private fun VaultViewModel.insertPending(cr: ContentResolver, e: VaultEntry, name: String, folder: String): Uri? = runCatching {
    cr.insert(
        if (e.kind == "video") MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, e.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        },
    )
}.getOrNull()
