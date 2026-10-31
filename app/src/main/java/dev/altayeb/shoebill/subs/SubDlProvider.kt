package dev.altayeb.shoebill.subs

import org.json.JSONObject
import java.io.IOException
import java.util.zip.ZipInputStream

/** SubDL search/download API, per https://subdl.com/api-doc (confirmed 2026-10-29). Free API key required. */
class SubDlProvider(private val apiKey: String) : SubtitleProvider {
    init {
        require(apiKey.isNotBlank()) { "SubDL needs an API key" }
    }

    override val id = "subdl"
    private val base = "https://api.subdl.com/api/v1/subtitles"
    private val dl = "https://dl.subdl.com"

    override suspend fun search(q: SubQuery): List<SubResult> {
        if (q.imdbId == null && q.title == null) return emptyList()
        val params = buildList {
            add("api_key" to apiKey)
            if (q.imdbId != null) add("imdb_id" to q.imdbId) else add("film_name" to q.title!!)
            add("type" to if (q.season != null) "tv" else "movie")
            q.year?.let { add("year" to it.toString()) }
            q.season?.let { add("season_number" to it.toString()) }
            q.episode?.let { add("episode_number" to it.toString()) }
            if (q.languages.isNotEmpty()) add("languages" to q.languages.joinToString(",") { it.uppercase() })
            add("subs_per_page" to "30")
        }
        val json = JSONObject(String(Http.get(base + "?" + params.joinToString("&") { "${it.first}=${enc(it.second)}" }), Charsets.UTF_8))
        if (!json.optBoolean("status", false)) return emptyList()
        val subs = json.optJSONArray("subtitles") ?: return emptyList()
        val out = ArrayList<SubResult>()
        for (i in 0 until subs.length()) {
            val s = subs.getJSONObject(i)
            val path = s.optString("url")
            if (!path.startsWith("/subtitle/")) continue
            out += SubResult(
                providerId = id,
                id = path,
                releaseName = s.optString("release_name").ifBlank { s.optString("name") },
                language = s.optString("lang").ifBlank { q.languages.firstOrNull().orEmpty() }.lowercase(),
                hashMatch = false,
                downloads = null,
                rating = null,
                format = "srt",
                extra = mapOf("name" to s.optString("name"), "fps" to s.optString("fps")),
            )
        }
        return out
    }

    override suspend fun download(r: SubResult): ByteArray {
        require(r.id.startsWith("/subtitle/")) { "bad SubDL path" }
        val bytes = Http.get("$dl${r.id}", mapOf("X-API-Key" to apiKey))
        return if (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) unzipSubtitle(bytes) else bytes
    }

    // ponytail: first subtitle entry in the archive only. Season packs need an episode pick, add when needed.
    internal fun unzipSubtitle(zip: ByteArray): ByteArray {
        ZipInputStream(zip.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val n = e.name.lowercase()
                if (!e.isDirectory && SUB_EXT.any { n.endsWith(it) }) return zis.readBytes()
                e = zis.nextEntry
            }
        }
        throw IOException("no subtitle file in SubDL archive")
    }

    private companion object {
        val SUB_EXT = listOf(".srt", ".ass", ".ssa", ".sub")
    }
}
