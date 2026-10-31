package dev.altayeb.shoebill.subs

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.io.IOException

class ProviderFailure(val providerId: String, val error: Throwable)

enum class FailKind { Quota, Auth, Network, Other }

object SubSearch {
    /** Runs every provider in parallel; a failing or slow provider contributes nothing, and its failure is reported. */
    suspend fun searchReport(
        providers: List<SubtitleProvider>,
        q: SubQuery,
        timeoutMs: Long = 10_000,
    ): Pair<List<SubResult>, List<ProviderFailure>> = coroutineScope {
        val parts: List<Pair<List<SubResult>, ProviderFailure?>> = providers.map { p ->
            async {
                try {
                    withTimeout(timeoutMs) { p.search(q) } to null
                } catch (e: Exception) {
                    emptyList<SubResult>() to ProviderFailure(p.id, e)
                }
            }
        }.awaitAll()
        parts.flatMap { it.first } to parts.mapNotNull { it.second }
    }

    suspend fun searchAll(
        providers: List<SubtitleProvider>,
        q: SubQuery,
        timeoutMs: Long = 10_000,
    ): List<SubResult> = searchReport(providers, q, timeoutMs).first

    fun classify(e: Throwable): FailKind = when {
        e is HttpStatusException && (e.code == 429 || e.code == 406) -> FailKind.Quota
        e is HttpStatusException && (e.code == 401 || e.code == 403) -> FailKind.Auth
        e is IOException -> FailKind.Network
        else -> FailKind.Other
    }

    /** Keeps results whose language is one of [wanted] (ISO 639-1); empty [wanted] keeps all. */
    fun filterLanguages(results: List<SubResult>, wanted: Collection<String>): List<SubResult> =
        if (wanted.isEmpty()) results else results.filter { normLang(it.language) in wanted }

    private val LANG_NAMES = mapOf(
        "arabic" to "ar", "ara" to "ar", "english" to "en", "eng" to "en", "french" to "fr", "fre" to "fr", "fra" to "fr",
        "spanish" to "es", "spa" to "es", "german" to "de", "ger" to "de", "deu" to "de", "turkish" to "tr", "tur" to "tr",
        "japanese" to "ja", "jpn" to "ja",
    )

    fun normLang(s: String): String = s.lowercase().let { LANG_NAMES[it] ?: it }

    /** Order: hash match first, then release-name similarity to the video file name, then downloads. */
    fun rank(results: List<SubResult>, videoName: String? = null): List<SubResult> =
        results.sortedWith(
            compareByDescending<SubResult> { it.hashMatch }
                .thenByDescending { similarity(videoName.orEmpty(), it.releaseName) }
                .thenByDescending { it.downloads ?: 0 },
        )

    /** Jaccard over word tokens, 0.0..1.0. */
    fun similarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
}
