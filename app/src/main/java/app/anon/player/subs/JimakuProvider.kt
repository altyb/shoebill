package app.anon.player.subs

import org.json.JSONArray

/**
 * Jimaku.cc anime subtitle API, per https://jimaku.cc/api/openapi.json (confirmed 2026-10-29).
 * Key goes raw in the Authorization header (format UNVERIFIED). Subs are Japanese.
 */
class JimakuProvider(private val apiKey: String) : SubtitleProvider {
    init {
        require(apiKey.isNotBlank()) { "Jimaku needs an API key" }
    }

    override val id = "jimaku"
    private val base = "https://jimaku.cc/api"
    private val headers = mapOf("Authorization" to apiKey)

    override suspend fun search(q: SubQuery): List<SubResult> {
        val title = q.title ?: return emptyList()
        val entries = JSONArray(String(Http.get("$base/entries/search?anime=true&query=${enc(title)}", headers), Charsets.UTF_8))
        val epParam = q.episode?.let { "?episode=$it" }.orEmpty()
        val out = ArrayList<SubResult>()
        // ponytail: top 3 entries only, one files call each. Add paging or a better pick when needed.
        for (i in 0 until minOf(entries.length(), 3)) {
            val e = entries.getJSONObject(i)
            val entryId = e.getLong("id")
            val files = JSONArray(String(Http.get("$base/entries/$entryId/files$epParam", headers), Charsets.UTF_8))
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                val name = f.getString("name")
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in SUB_FORMATS) continue
                out += SubResult(
                    providerId = id,
                    id = f.getString("url"),
                    releaseName = name,
                    language = "ja",
                    hashMatch = false,
                    downloads = null,
                    rating = null,
                    format = ext,
                    extra = mapOf("entry" to entryId.toString(), "entry_name" to e.optString("name")),
                )
            }
        }
        return out
    }

    override suspend fun download(r: SubResult): ByteArray {
        // The key is sent only to jimaku.cc, never to whatever URL a result carries.
        require(r.id.startsWith("https://jimaku.cc/")) { "refusing to send key to ${r.id}" }
        return Http.get(r.id, headers)
    }

    private companion object {
        val SUB_FORMATS = setOf("srt", "ass", "ssa", "vtt")
    }
}
