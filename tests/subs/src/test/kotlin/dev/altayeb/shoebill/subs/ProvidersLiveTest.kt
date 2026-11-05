package dev.altayeb.shoebill.subs

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

/**
 * Live searches. Each needs its key in an env var (never committed) and network access.
 * Skipped, not failed, when either is missing. No provider has a confirmed key-free search.
 */
class ProvidersLiveTest {
    private val query = SubQuery(title = "Inception", year = 2010, imdbId = "tt1375666", languages = listOf("en"))

    private fun live(key: String?, make: (String) -> SubtitleProvider) = runBlocking {
        assumeTrue("set $key to run", !System.getenv(key!!).isNullOrBlank())
        try {
            val results = make(System.getenv(key)).search(query)
            println("$key -> ${results.size} results")
            assumeTrue("no results, endpoint or key problem", results.isNotEmpty())
        } catch (e: IOException) {
            assumeNoException("network unavailable: ${e.message}", e)
        }
    }

    @Test
    fun subdlLive() = live("SHOEBILL_SUBDL_KEY") { SubDlProvider(it) }

    @Test
    fun jimakuLive() = live("SHOEBILL_JIMAKU_KEY") { JimakuProvider(it) }

    @Test
    fun opensubtitlesLive() = live("SHOEBILL_OS_KEY") { OpenSubtitlesProvider(it) }
}
