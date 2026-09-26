package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.MagisEntities
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.local.EnqueueOutcome
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.ui.home.MagisDownloadActions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisTitleSourceTest {

    private fun source(
        downloads: MagisDownloadActions? = null,
        imdb: String? = "tt1234567",
    ) = MagisTitleSource(
        downloads = downloads,
        movieImdbId = { imdb },
        onPlayMovie = { error("not used") },
        onPlaySeason = { _, _, _, _ -> error("not used") },
    )

    private fun show(id: String = "c1", title: String = "Show T1", type: String = "teleplay") = CatalogItem(
        id = id, title = title, poster = null, durationS = 0, ref = MagisRef(id, type, 0).encode(), type = type,
        episodeCount = 8,
    )

    private fun film() = show(id = "m1", title = "Film", type = "movie")

    @Test
    fun `library ids are the Magis ones`() {
        val s = source()
        val item = show()
        assertEquals(MagisEntities.itemIdFor("c1"), s.itemId(item))
        assertEquals(MagisEntities.movieEpisodeId(MagisEntities.itemIdFor("c1")), s.movieEpisodeId(item))
        val chapter = GatewayEpisode(number = 4, title = "t", ref = "r")
        assertEquals(MagisEntities.episodeIdFor(MagisEntities.itemIdFor("c1"), 4), s.chapterEpisodeId(item, chapter))
    }

    @Test
    fun `a sibling season is a new item with its own ref and title`() {
        val model = source().seasons as SeasonModel.Siblings
        val next = model.itemFor(show(), SeasonRef("c2", 2))
        assertEquals("c2", next.id)
        assertEquals(MagisRef("c2", "teleplay", 0).encode(), next.ref)
        assertEquals("Show T2", next.title)
        assertEquals(0, next.episodeCount)
    }

    @Test
    fun `downloads exist only when the source was given them`() {
        assertFalse(source().canDownload)
        val actions = MagisDownloadActions({ null }, { _, _, _ -> null }, { _, _ -> EnqueueOutcome.QUEUED })
        assertTrue(source(downloads = actions).canDownload)
    }

    @Test
    fun `the gateway result is the home card's`() {
        val r = source().gatewayResult(show())
        assertEquals("magis", r.source)
        assertEquals("c1", r.extra["content_id"])
        assertEquals("series", r.kind)
    }

    @Test
    fun `a movie hints its IMDb id and a series hints nothing`() = runTest {
        val s = source(imdb = "tt7654321")
        assertEquals(TmdbHint(imdbId = "tt7654321"), s.tmdbHint(film()))
        assertEquals(TmdbHint(), s.tmdbHint(show()))
        assertEquals(TmdbHint(), source(imdb = null).tmdbHint(film()))
    }

    @Test
    fun `Magis keeps the fixed failure line on the TV, as before sources existed`() {
        // The raw cause of a Magis failure carries the portal's host and English fragments.
        assertFalse(source().showsFailureDetail)
    }

    @Test
    fun `a source with no badge and no year of its own`() {
        val s = source()
        assertEquals(null, s.badge)
        assertEquals("", s.initialYear)
    }
}
