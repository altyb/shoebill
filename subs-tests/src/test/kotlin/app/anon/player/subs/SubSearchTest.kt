package app.anon.player.subs

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SubSearchTest {
    private fun r(name: String, hash: Boolean, downloads: Int?) =
        SubResult("fake", name, name, "en", hash, downloads, null, "srt")

    @Test
    fun hashMatchRanksFirstEvenWithFewerDownloads() {
        val ranked = SubSearch.rank(
            listOf(
                r("Movie.2010.1080p.BluRay", hash = false, downloads = 99_999),
                r("Movie.2010.720p.WEB", hash = true, downloads = 1),
            ),
            videoName = "Movie.2010.1080p.BluRay.mkv",
        )
        assertEquals("Movie.2010.720p.WEB", ranked[0].id)
    }

    @Test
    fun nameSimilarityBeatsDownloadsWithoutHashMatch() {
        val ranked = SubSearch.rank(
            listOf(
                r("Other.Show.S01E01", hash = false, downloads = 500),
                r("Movie.2010.1080p.BluRay", hash = false, downloads = 10),
            ),
            videoName = "Movie.2010.1080p.BluRay.mkv",
        )
        assertEquals("Movie.2010.1080p.BluRay", ranked[0].id)
    }

    @Test
    fun searchAllDropsFailuresAndTimeouts() = runBlocking {
        val ok = object : SubtitleProvider {
            override val id = "ok"
            override suspend fun search(q: SubQuery) = listOf(r("a", false, 1))
            override suspend fun download(r: SubResult) = ByteArray(0)
        }
        val boom = object : SubtitleProvider {
            override val id = "boom"
            override suspend fun search(q: SubQuery): List<SubResult> = error("down")
            override suspend fun download(r: SubResult) = ByteArray(0)
        }
        val slow = object : SubtitleProvider {
            override val id = "slow"
            override suspend fun search(q: SubQuery): List<SubResult> { delay(60_000); return listOf(r("late", false, 1)) }
            override suspend fun download(r: SubResult) = ByteArray(0)
        }
        val out = SubSearch.searchAll(listOf(ok, boom, slow), SubQuery(title = "x"), timeoutMs = 300)
        assertEquals(listOf("a"), out.map { it.id })
    }
}
