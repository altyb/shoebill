package dev.altayeb.shoebill.subs

import org.json.JSONObject

/**
 * OpenSubtitles.com REST API v1. UNVERIFIED end to end: official docs were unreachable and
 * the live API answered 403/503 during research. See PROVIDERS.md.
 */
class OpenSubtitlesProvider(
    private val apiKey: String,
    private val userAgent: String = "Shoebill v0.3",
    private val token: String? = null, // from POST /login; download quota depends on it (UNVERIFIED)
) : SubtitleProvider {
    init {
        require(apiKey.isNotBlank()) { "OpenSubtitles needs an API key" }
    }

    override val id = "opensubtitles"
    private val base = "https://api.opensubtitles.com/api/v1"

    private fun headers(): Map<String, String> = buildMap {
        put("Api-Key", apiKey)
        put("User-Agent", userAgent)
        token?.let { put("Authorization", "Bearer $it") }
    }

    override suspend fun search(q: SubQuery): List<SubResult> {
        val params = buildList {
            q.fileHash?.let { add("moviehash" to it) }
            q.fileSize?.let { add("moviebytesize" to it.toString()) }
            q.imdbId?.let { add("imdb_id" to it.removePrefix("tt")) }
            if (q.imdbId == null) q.title?.let { add("query" to it) }
            q.season?.let { add("season_number" to it.toString()) }
            q.episode?.let { add("episode_number" to it.toString()) }
            if (q.languages.isNotEmpty()) add("languages" to q.languages.joinToString(",") { it.lowercase() })
        }
        if (params.isEmpty()) return emptyList()
        val url = "$base/subtitles?" + params.joinToString("&") { "${it.first}=${enc(it.second)}" }
        val data = JSONObject(String(Http.get(url, headers()), Charsets.UTF_8)).optJSONArray("data")
            ?: return emptyList()
        val out = ArrayList<SubResult>()
        for (i in 0 until data.length()) {
            val a = data.getJSONObject(i).getJSONObject("attributes")
            val files = a.optJSONArray("files") ?: continue
            if (files.length() == 0) continue
            val f = files.getJSONObject(0)
            val fileId = f.getLong("file_id").toString()
            out += SubResult(
                providerId = id,
                id = fileId,
                releaseName = a.optString("release").ifBlank { f.optString("file_name") },
                language = a.optString("language"),
                hashMatch = a.optBoolean("moviehash_match", false),
                downloads = a.optInt("download_count", -1).takeIf { it >= 0 },
                rating = a.optDouble("ratings", Double.NaN).takeUnless { it.isNaN() },
                format = "srt",
                extra = mapOf("file_name" to f.optString("file_name")),
            )
        }
        return out
    }

    override suspend fun download(r: SubResult): ByteArray {
        val body = JSONObject().put("file_id", r.id.toLong()).put("sub_format", "srt").toString()
        val link = JSONObject(String(Http.postJson("$base/download", body, headers()), Charsets.UTF_8))
            .getString("link")
        return Http.get(link)
    }
}
