package dev.altayeb.shoebill.download.logic
import java.util.Locale

enum class Quality(val height: Int?, val audio: String?) {
    BEST(null, null), P1080(1080, null), P720(720, null), P480(480, null), M4A(null, "m4a"), MP3(null, "mp3"), OPUS(null, "opus"), FLAC(null, "flac")
}

data class DownloadOptions(
    val quality: Quality = Quality.BEST,
    val playlist: Boolean = false,
    val subtitles: Boolean = false,
    val limit: String? = null, // yt-dlp --limit-rate value, such as 1M
    val proxy: String? = null,
    val formatId: String? = null, // one exact format picked from the list; overrides quality
    val formatAudioOnly: Boolean = false,
    val skipSponsors: Boolean = false, // SponsorBlock: cut sponsor segments out of the file
    val sponsorCategories: String = "default",
    val container: String = "mkv",
    val codec: String? = null, // preferred video codec, a yt-dlp sort key value such as h264
    val filename: Int = 0, // index into DownloadLogic.FILENAMES
    val restrictNames: Boolean = false,
    val embedThumbnail: Boolean = true,
    val embedMetadata: Boolean = true,
    val subLangs: String = "ar.*,en.*",
    val autoSubs: Boolean = true,
    val forceIpv4: Boolean = false,
    val threads: Int = 1,
    val cookiesFile: String? = null,
    val archiveFile: String? = null,
    val extra: List<String> = emptyList(),
    val playlistFolder: Boolean = false, // save into a folder named after the playlist
    val playlistName: String? = null, // the playlist's title, for entries picked one by one (playlist = false)
    val thumbnailFile: Boolean = false, // keep the thumbnail as a .jpg beside the video
    val subtitleFiles: Boolean = false, // keep subtitles as .srt/.vtt files beside the video instead of embedding them
    val folder: String? = null, // a folder of the user's choice under Shoebill/, one plain name (see folderName)
)

/** One format yt-dlp offers. Size is 0 when the site does not say. */
data class Fmt(val id: String, val ext: String, val height: Int, val hasVideo: Boolean, val hasAudio: Boolean, val tbr: Int, val size: Long) {
    val audioOnly get() = !hasVideo
}
data class PlEntry(val url: String, val title: String, val durationSec: Long, val thumb: String?)

object DownloadLogic {
    private val URL = Regex("""(?i)\bhttps?://[^\s<>"']+""")

    /** The first web address in shared text ("Look at this https://x.y/z"), without trailing punctuation. Null if none. */
    fun firstUrl(text: String?): String? = text?.let { URL.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';') }

    /** yt-dlp arguments after the URL. The output folder is private; the finished file is then copied into the media library. */
    fun args(o: DownloadOptions, outputDir: String): List<String> {
        val a = ArrayList<String>()
        val folder = if (!o.playlistFolder) null else if (o.playlist) "%(playlist_title|Playlist)s" else folderName(o.playlistName)
        a += listOf("-o", "$outputDir/" + (folder?.let { "$it/" } ?: "") + (FILENAMES.getOrNull(o.filename) ?: FILENAMES[0]))
        a += listOf("--no-mtime", "--no-part", "--newline", "--trim-filenames", "180")
        a += if (o.playlist) "--yes-playlist" else "--no-playlist"
        if (o.thumbnailFile) a += listOf("--write-thumbnail", "--convert-thumbnails", "jpg")
        o.limit?.let { a += listOf("--limit-rate", it) }
        o.proxy?.let { a += listOf("--proxy", it) }
        if (o.skipSponsors) a += listOf("--sponsorblock-remove", o.sponsorCategories)
        if (o.restrictNames) a += "--restrict-filenames"
        if (o.forceIpv4) a += "--force-ipv4"
        if (o.threads > 1) a += listOf("-N", o.threads.toString())
        o.cookiesFile?.let { a += listOf("--cookies", it) }
        o.archiveFile?.let { a += listOf("--download-archive", it) }
        if (o.embedMetadata) a += "--embed-metadata"
        if (o.formatId != null) {
            a += listOf("-f", if (o.formatAudioOnly) o.formatId else "${o.formatId}+ba/${o.formatId}")
            if (!o.formatAudioOnly) a += listOf("--merge-output-format", o.container)
        } else if (o.quality.audio != null) {
            a += listOf("-f", "ba/b", "-x", "--audio-format", o.quality.audio)
            if (o.embedThumbnail) a += "--embed-thumbnail"
        } else {
            val h = o.quality.height
            a += listOf("-f", if (h == null) "bv*+ba/b" else "bv*[height<=$h]+ba/b[height<=$h]/b")
            o.codec?.let { a += listOf("-S", "vcodec:$it") }
            a += listOf("--merge-output-format", o.container)
            if (o.embedThumbnail && o.container != "webm") a += "--embed-thumbnail"
            if (o.subtitles) {
                a += "--write-subs"
                if (o.autoSubs) a += "--write-auto-subs"
                a += listOf("--sub-langs", o.subLangs)
                if (!o.subtitleFiles) a += "--embed-subs"
            }
        }
        a += o.extra
        return a
    }

    /** What is worth showing: storyboards and duplicates gone, best of each height and container, video first. */
    fun formats(all: List<Fmt>): List<Fmt> = all.filter { it.hasVideo || it.hasAudio }
        .groupBy { Triple(it.height, it.ext.lowercase(), it.audioOnly) }.values.map { g -> g.maxByOrNull { it.size.takeIf { s -> s > 0 } ?: it.tbr.toLong() }!! }
        .sortedWith(compareBy<Fmt> { it.audioOnly }.thenByDescending { it.height }.thenByDescending { it.tbr })

    fun formatLabel(f: Fmt): String = listOfNotNull(
        if (f.audioOnly) "${f.tbr.takeIf { it > 0 } ?: "?"} kbps" else "${f.height}p",
        f.ext.uppercase(Locale.ROOT),
        f.size.takeIf { it > 0 }?.let { size(it) },
    ).joinToString(" · ")

    fun size(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(Locale.ROOT, b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%d MB".format(Locale.ROOT, b / (1L shl 20))
        else -> "%d KB".format(Locale.ROOT, b / 1024)
    }

    fun looksLikePlaylist(url: String): Boolean = Regex("""[?&]list=|/playlist\b|/sets/""").containsMatchIn(url)

    val FILENAMES = listOf("%(title).150B [%(id)s].%(ext)s", "%(title).180B.%(ext)s", "%(uploader).60B - %(title).120B.%(ext)s")

    private val FORBIDDEN = Regex("""^--(exec|exec-before-download|no-exec|paths|output|config-locations?|plugin-dirs)\b|^-[oP]$""")

    /**
     * The owner's own extra yt-dlp options, split on spaces with quotes respected. Null if unbalanced or forbidden:
     * running commands and writing outside the app's folder stay off, even if someone is told to paste them.
     */
    fun parseExtra(text: String): List<String>? {
        val out = ArrayList<String>(); val cur = StringBuilder(); var quote: Char? = null; var has = false
        for (c in text) when {
            quote != null -> if (c == quote) quote = null else cur.append(c)
            c == '"' || c == '\'' -> { quote = c; has = true }
            c.isWhitespace() -> { if (has || cur.isNotEmpty()) out += cur.toString(); cur.clear(); has = false }
            else -> cur.append(c)
        }
        if (quote != null) return null
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out.takeIf { l -> l.none { FORBIDDEN.containsMatchIn(it) } }
    }

    val LIMITS = listOf("500K", "1M", "2M", "5M")
    private val PROXY = Regex("""(?i)^(https?|socks4a?|socks5h?)://([^\s/@]+@)?[^\s/:@]+:([0-9]{1,5})/?$""")

    /** Blank means no proxy and is valid. */
    fun validProxy(s: String): Boolean {
        if (s.isBlank()) return true
        val port = PROXY.find(s.trim())?.groupValues?.get(3)?.toIntOrNull() ?: return false
        return port in 1..65535
    }

    /** "[download]  42.0% of 10MiB at 1.20MiB/s ETA 00:05" to "1.20MiB/s". Null on any other line. */
    /** yt-dlp's "[download] Downloading item 4 of 12" while a whole playlist downloads in one job: "4/12", else null. */
    fun playlistItem(line: String?): String? = line?.let { Regex("""Downloading (?:item|video) (\d+) of (\d+)""").find(it) }?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }

    /** The library folder a download lands in: Shoebill, then the user's folder, then the playlist's. */
    fun libraryPath(root: String, userFolder: String?, playlistFolder: String): String =
        listOf(root, "Shoebill", folderName(userFolder).orEmpty(), playlistFolder).filter { it.isNotEmpty() }.joinToString("/")

    /** A new file name from what the user typed: the old extension kept, unsafe marks removed. Null if nothing is left. */
    fun rename(typed: String, oldName: String): String? {
        val ext = oldName.substringAfterLast('.', "")
        val base = folderName(typed.removeSuffix(".$ext")) ?: return null
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    fun speed(line: String?): String? = line?.let { Regex("""\bat\s+([0-9.]+\s*[KMG]?i?B/s)""").find(it)?.groupValues?.get(1) }

    /** A failure message may repeat the proxy address, which can hold a password. */
    fun scrub(message: String, proxy: String?): String = if (proxy.isNullOrEmpty()) message else message.replace(proxy, "(proxy)")

    fun time(seconds: Long): String {
        val t = seconds.coerceAtLeast(0)
        val h = t / 3600; val m = t % 3600 / 60; val x = t % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, x) else "%d:%02d".format(Locale.ROOT, m, x)
    }

    fun isAudioFile(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3", "opus", "ogg", "flac", "wav", "aac")
    fun isMediaFile(name: String) = isAudioFile(name) || name.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "webm", "mov", "avi", "ts")
    fun isThumbFile(name: String) = name.substringAfterLast('.', "").lowercase() == "jpg"
    fun isSubtitleFile(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("srt", "vtt")

    /**
     * One folder name from a site's title: a single plain path part, no slashes, dots-as-traversal, control or template marks,
     * at most 80 characters (code points, so Arabic and emoji are never cut in half). Null if nothing is left.
     */
    fun folderName(raw: String?): String? {
        val s = raw.orEmpty()
            .replace(Regex("""[\\/:*?"<>|%\p{Cc}‪-‮⁦-⁩]"""), " ")
            .replace("..", " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.')
        val cut = s.offsetByCodePoints(0, minOf(80, s.codePointCount(0, s.length)))
        return s.substring(0, cut).trim(' ', '.').takeIf { it.isNotEmpty() }
    }

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"; "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "avi" -> "video/x-msvideo"; "ts" -> "video/mp2t"
        "jpg" -> "image/jpeg"; "srt" -> "application/x-subrip"; "vtt" -> "text/vtt"
        "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "opus" -> "audio/opus"; "ogg" -> "audio/ogg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "aac" -> "audio/aac"
        else -> "application/octet-stream"
    }
}
