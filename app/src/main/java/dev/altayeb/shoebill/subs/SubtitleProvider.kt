package dev.altayeb.shoebill.subs

interface SubtitleProvider {
    val id: String
    suspend fun search(q: SubQuery): List<SubResult>
    suspend fun download(r: SubResult): ByteArray
}

data class SubQuery(
    val title: String? = null,
    val year: Int? = null,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val languages: List<String> = emptyList(),
    val fileHash: String? = null,
    val fileSize: Long? = null,
)

data class SubResult(
    val providerId: String,
    val id: String,
    val releaseName: String,
    val language: String,
    val hashMatch: Boolean,
    val downloads: Int?,
    val rating: Double?,
    val format: String,
    val extra: Map<String, String> = emptyMap(),
)
