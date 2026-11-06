package dev.altayeb.shoebill.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.altayeb.shoebill.library.logic.NaturalOrder
import dev.altayeb.shoebill.library.logic.PlaylistCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class Song(
    val id: Long, val title: String, val artist: String, val album: String, val albumId: Long,
    val durationMs: Long, val track: Int, val folder: String, val path: String?, val genre: String = "", val dateAdded: Long = 0,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val art: Uri get() = ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), albumId)

    fun item(): MediaItem = MediaItem.Builder().setMediaId(id.toString()).setUri(uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setIsPlayable(true)
            // No album tag: Android names the album after the folder, so its art is some other song's. The file's own cover is read at play time.
            .apply { if (album != folder) setArtworkUri(art) }.build(),
    ).build()
}

/** [raw] ignores the library filters: for the settings page, which must list hidden folders, and for restoring a queue. */
suspend fun querySongs(context: Context, raw: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
    val m = MediaStore.Audio.Media.TITLE
    val cols = arrayOf(MediaStore.Audio.Media._ID, m, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_ADDED,
        *(if (Build.VERSION.SDK_INT >= 30) arrayOf(MediaStore.Audio.Media.GENRE) else emptyArray()))
    val out = ArrayList<Song>()
    context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
        while (c.moveToNext()) {
            val path = c.getString(7)
            out += Song(c.getLong(0), c.getString(1).orEmpty(), c.getString(2)?.takeIf { it != "<unknown>" }.orEmpty(), c.getString(3).orEmpty(), c.getLong(4),
                c.getLong(5), c.getInt(6) % 1000, path?.let { File(it).parentFile?.name }.orEmpty(), path, if (c.columnCount > 9) c.getString(9).orEmpty() else "", c.getLong(8))
        }
    }
    val f = dev.altayeb.shoebill.settings.AppSettings.get(context).settings.value
    (if (raw) out else out.filter { it.durationMs >= f.minSongSec * 1000L && it.folder !in f.hiddenFolders }).sortedByDescending { it.dateAdded } // newest first: a fresh download is at the top of every list
}

/** Saved lists of song ids. Songs deleted from the phone simply stop appearing. */
class MusicLists(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("music_lists", Context.MODE_PRIVATE)
    fun names(): List<String> = prefs.all.keys.sortedWith(NaturalOrder)
    fun songs(name: String, all: List<Song>): List<Song> {
        val byId = all.associateBy { it.id }
        return PlaylistCodec.decode(prefs.getString(name, null)).mapNotNull(byId::get)
    }
    /** An empty list, to fill later from the song menu. An existing name is left as it is. */
    fun create(name: String) { if (!prefs.contains(name)) prefs.edit().putString(name, "").apply() }
    fun add(name: String, id: Long) {
        val ids = PlaylistCodec.decode(prefs.getString(name, null))
        if (id !in ids) prefs.edit().putString(name, PlaylistCodec.encode(ids + id)).apply()
    }
    fun remove(name: String, id: Long) = prefs.edit().putString(name, PlaylistCodec.encode(PlaylistCodec.decode(prefs.getString(name, null)) - id)).apply()
    fun delete(name: String) = prefs.edit().remove(name).apply()
    fun exists(name: String) = prefs.contains(name)
    /** Moves the songs to [to]; false (nothing changed) when [to] is blank or already taken. */
    fun rename(from: String, to: String): Boolean {
        if (to.isBlank() || prefs.contains(to) || !prefs.contains(from)) return false
        prefs.edit().putString(to, prefs.getString(from, "")).remove(from).apply()
        return true
    }
}

/** Lyrics: a chosen file kept by the app, else a .lrc beside the song when Android lets us read it. */
object Lyrics {
    private const val MAX = 512 * 1024L
    private fun saved(c: Context, id: Long) = File(c.filesDir, "lyrics/$id.lrc")

    suspend fun load(c: Context, s: Song): String? = withContext(Dispatchers.IO) {
        val own = saved(c, s.id)
        val beside = s.path?.let { File(it.substringBeforeLast('.') + ".lrc") }
        listOf(own, beside).firstNotNullOfOrNull { f ->
            runCatching { f?.takeIf { it.isFile && it.length() <= MAX }?.readText() }.getOrNull()
        }
    }

    suspend fun save(c: Context, s: Song, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val text = c.contentResolver.openInputStream(uri)!!.use { it.readNBytesCompat(MAX.toInt()) }.decodeToString()
            saved(c, s.id).apply { parentFile?.mkdirs(); writeText(text) }
            text
        }.getOrNull()
    }

    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) { val n = read(buf); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }
}
