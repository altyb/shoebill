package app.anon.player.subs

/** Title, year and episode guessed from a release-style file name. */
data class ReleaseInfo(val title: String, val year: Int? = null, val season: Int? = null, val episode: Int? = null)

object ReleaseName {
    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "m4v", "mov", "webm", "ts", "wmv", "mpg", "mpeg", "flv")
    private val EPISODE = Regex("""(?i)(?<![\p{L}\p{N}])S(\d{1,2})E(\d{1,3})(?![\p{L}\p{N}])""")
    private val YEAR = Regex("""(?<![\p{L}\p{N}])((?:19|20)\d{2})(?![\p{L}\p{N}])""")
    private val TAG = Regex(
        """(?i)(?<![\p{L}\p{N}])(480p|576p|720p|1080p|2160p|4k|bluray|blu ray|web ?dl|webrip|hdtv|brrip|bdrip|dvdrip|hdrip|remux|x26[45]|h ?26[45]|hevc|av1|xvid|10bit|hdr|dv|aac|ac3|dts|ddp?5 ?1|flac|atmos|proper|repack|extended|uncut)(?![\p{L}\p{N}])""",
    )

    fun parse(fileName: String): ReleaseInfo {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val base = if (ext in VIDEO_EXT) fileName.substringBeforeLast('.') else fileName
        val norm = base.replace('.', ' ').replace('_', ' ')
        val ep = EPISODE.find(norm)
        // a year at the very start is part of the title (e.g. "1917"), so take the first year with a title before it
        val year = YEAR.findAll(norm).firstOrNull { cleanTitle(norm.substring(0, it.range.first)).isNotEmpty() }
        val tag = TAG.find(norm)
        val cut = listOfNotNull(ep?.range?.first, year?.range?.first, tag?.range?.first).minOrNull() ?: norm.length
        return ReleaseInfo(
            title = cleanTitle(norm.substring(0, cut)),
            year = year?.takeIf { it.range.first == cut }?.groupValues?.get(1)?.toInt(),
            season = ep?.groupValues?.get(1)?.toInt(),
            episode = ep?.groupValues?.get(2)?.toInt(),
        )
    }

    private fun cleanTitle(s: String): String =
        s.trim(' ', '-', '(', '[', '.', '_').replace(Regex("\\s+"), " ").trim()
}
