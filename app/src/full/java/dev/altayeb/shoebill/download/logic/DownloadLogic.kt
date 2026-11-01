package dev.altayeb.shoebill.download.logic

enum class Quality(val height: Int?, val audio: String?) {
    BEST(null, null), P1080(1080, null), P720(720, null), P480(480, null), M4A(null, "m4a"), MP3(null, "mp3")
}

data class DownloadOptions(val quality: Quality = Quality.BEST, val playlist: Boolean = false, val subtitles: Boolean = false)

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
        if (o.quality.audio != null) {
            a += listOf("-f", "ba/b", "-x", "--audio-format", o.quality.audio, "--embed-metadata", "--embed-thumbnail")
        } else {
            val h = o.quality.height
            a += listOf("-f", if (h == null) "bv*+ba/b" else "bv*[height<=$h]+ba/b[height<=$h]/b")
            a += listOf("--merge-output-format", "mkv", "--embed-metadata")
            if (o.subtitles) a += listOf("--write-subs", "--write-auto-subs", "--embed-subs", "--sub-langs", "ar.*,en.*")
        }
        return a
    }

    fun isAudioFile(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3", "opus", "ogg", "flac", "wav", "aac")
    fun isMediaFile(name: String) = isAudioFile(name) || name.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "webm", "mov", "avi", "ts")

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"; "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "avi" -> "video/x-msvideo"; "ts" -> "video/mp2t"
        "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "opus" -> "audio/opus"; "ogg" -> "audio/ogg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "aac" -> "audio/aac"
        else -> "application/octet-stream"
    }
}
