package dev.altayeb.shoebill.download.logic

enum class Quality(val height: Int?, val audio: String?) {
    BEST(null, null), P1080(1080, null), P720(720, null), P480(480, null), M4A(null, "m4a"), MP3(null, "mp3")
}

data class DownloadOptions(
    val quality: Quality = Quality.BEST,
    val playlist: Boolean = false,
    val subtitles: Boolean = false,
    val limit: String? = null, // yt-dlp --limit-rate value, such as 1M
    val proxy: String? = null,
    val formatId: String? = null, // one exact format picked from the list; overrides quality
    val formatAudioOnly: Boolean = false,
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
        a += listOf("-o", "$outputDir/%(title).150B [%(id)s].%(ext)s")
        a += listOf("--no-mtime", "--no-part", "--newline", "--trim-filenames", "180")
        a += if (o.playlist) "--yes-playlist" else "--no-playlist"
        o.limit?.let { a += listOf("--limit-rate", it) }
        o.proxy?.let { a += listOf("--proxy", it) }
        if (o.formatId != null) {
            a += listOf("-f", if (o.formatAudioOnly) o.formatId else "${o.formatId}+ba/${o.formatId}")
            if (!o.formatAudioOnly) a += listOf("--merge-output-format", "mkv")
            a += "--embed-metadata"
        } else if (o.quality.audio != null) {
            a += listOf("-f", "ba/b", "-x", "--audio-format", o.quality.audio, "--embed-metadata", "--embed-thumbnail")
        } else {
            val h = o.quality.height
            a += listOf("-f", if (h == null) "bv*+ba/b" else "bv*[height<=$h]+ba/b[height<=$h]/b")
            a += listOf("--merge-output-format", "mkv", "--embed-metadata")
            if (o.subtitles) a += listOf("--write-subs", "--write-auto-subs", "--embed-subs", "--sub-langs", "ar.*,en.*")
        }
        return a
    }

    /** What is worth showing: storyboards and duplicates gone, best of each height and container, video first. */
    fun formats(all: List<Fmt>): List<Fmt> = all.filter { it.hasVideo || it.hasAudio }
        .groupBy { Triple(it.height, it.ext.lowercase(), it.audioOnly) }.values.map { g -> g.maxByOrNull { it.size.takeIf { s -> s > 0 } ?: it.tbr.toLong() }!! }
        .sortedWith(compareBy<Fmt> { it.audioOnly }.thenByDescending { it.height }.thenByDescending { it.tbr })

    fun formatLabel(f: Fmt): String = listOfNotNull(
        if (f.audioOnly) "${f.tbr.takeIf { it > 0 } ?: "?"} kbps" else "${f.height}p",
        f.ext.uppercase(java.util.Locale.ROOT),
        f.size.takeIf { it > 0 }?.let { size(it) },
    ).joinToString(" · ")

    fun size(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%d MB".format(java.util.Locale.ROOT, b / (1L shl 20))
        else -> "%d KB".format(java.util.Locale.ROOT, b / 1024)
    }

    fun looksLikePlaylist(url: String): Boolean = Regex("""[?&]list=|/playlist\b|/sets/""").containsMatchIn(url)

    val LIMITS = listOf("500K", "1M", "2M", "5M")
    private val PROXY = Regex("""(?i)^(https?|socks4a?|socks5h?)://([^\s/@]+@)?[^\s/:@]+:([0-9]{1,5})/?$""")

    /** Blank means no proxy and is valid. */
    fun validProxy(s: String): Boolean {
        if (s.isBlank()) return true
        val port = PROXY.find(s.trim())?.groupValues?.get(3)?.toIntOrNull() ?: return false
        return port in 1..65535
    }

    /** "[download]  42.0% of 10MiB at 1.20MiB/s ETA 00:05" to "1.20MiB/s". Null on any other line. */
    fun speed(line: String?): String? = line?.let { Regex("""\bat\s+([0-9.]+\s*[KMG]?i?B/s)""").find(it)?.groupValues?.get(1) }

    /** A failure message may repeat the proxy address, which can hold a password. */
    fun scrub(message: String, proxy: String?): String = if (proxy.isNullOrEmpty()) message else message.replace(proxy, "(proxy)")

    fun time(seconds: Long): String {
        val t = seconds.coerceAtLeast(0)
        val h = t / 3600; val m = t % 3600 / 60; val x = t % 60
        return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, x) else "%d:%02d".format(java.util.Locale.ROOT, m, x)
    }

    fun isAudioFile(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3", "opus", "ogg", "flac", "wav", "aac")
    fun isMediaFile(name: String) = isAudioFile(name) || name.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "webm", "mov", "avi", "ts")

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"; "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "avi" -> "video/x-msvideo"; "ts" -> "video/mp2t"
        "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "opus" -> "audio/opus"; "ogg" -> "audio/ogg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "aac" -> "audio/aac"
        else -> "application/octet-stream"
    }
}
