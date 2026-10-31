package dev.altayeb.shoebill.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import dev.altayeb.shoebill.subs.KeyStore
import dev.altayeb.shoebill.subs.OsHash
import dev.altayeb.shoebill.subs.SubCharset
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

data class VideoHash(val hash: String, val size: Long)

/** OpenSubtitles hash, reading only the two 64 KiB chunks. Null when the file cannot be read. */
fun videoHash(context: Context, uri: Uri): VideoHash? = runCatching {
    if (uri.scheme == "content") {
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val size = ch.size()
            VideoHash(OsHash.compute(size) { off, n -> readAt(ch, off, n) }, size)
        }
    } else {
        RandomAccessFile(File(uri.path!!), "r").use { VideoHash(OsHash.compute(it), it.length()) }
    }
}.getOrNull()

private fun readAt(ch: FileChannel, off: Long, n: Int): ByteArray {
    val buf = ByteBuffer.allocate(n)
    var pos = off
    while (buf.hasRemaining()) {
        val r = ch.read(buf, pos)
        if (r < 0) break
        pos += r
    }
    return buf.array()
}

/** Decodes any charset to UTF-8 (SubCharset) and writes it under cacheDir/subs. */
fun cacheSubtitle(context: Context, name: String, bytes: ByteArray, ext: String): File {
    val dir = File(context.cacheDir, "subs").apply { mkdirs() }
    val file = File(dir, "${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.$ext")
    file.writeText(SubCharset.decode(bytes), Charsets.UTF_8)
    return file
}

/** Keys live in SharedPreferences "subkeys". Only the provider's own key is stored there. */
fun prefsKeyStore(context: Context): KeyStore {
    val prefs: SharedPreferences = context.getSharedPreferences("subkeys", Context.MODE_PRIVATE)
    return object : KeyStore {
        override fun get(name: String): String? = prefs.getString(name, null)
        override fun put(name: String, value: String) {
            prefs.edit().putString(name, value).apply()
        }
    }
}

/** Sibling subtitles: same base name, extension srt/ass/ssa/vtt (covers `.srt`, `.ar.srt`, `.ara.srt`). API 29+ for RELATIVE_PATH. */
fun siblingSubtitleUris(context: Context, video: Uri): List<Uri> = runCatching {
    val id = android.content.ContentUris.parseId(video)
    val cr = context.contentResolver
    val videoRow = cr.query(
        android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id),
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null } ?: return@runCatching emptyList()
    val stem = videoRow.first.substringBeforeLast('.')
    val files = MediaStore.Files.getContentUri("external")
    cr.query(files, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(videoRow.second), null)?.use { c ->
        buildList {
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith("$stem.") && name.substringAfterLast('.').lowercase() in SUB_EXT)
                    add(android.content.ContentUris.withAppendedId(files, c.getLong(0)))
            }
        }
    } ?: emptyList()
}.getOrDefault(emptyList())

private val SUB_EXT = setOf("srt", "ass", "ssa", "vtt")
