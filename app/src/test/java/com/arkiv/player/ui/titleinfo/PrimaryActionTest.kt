package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.MagisEntities
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.gateway.GatewayEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryActionTest {

    private val contentId = "c1"
    private val itemId = MagisEntities.itemIdFor(contentId)

    private fun chapters(n: Int) = (1..n).map { GatewayEpisode(number = it, title = "E$it", ref = "r$it") }

    private fun row(episodeId: String, positionMs: Long, watched: Boolean, last: Long, durationMs: Long = 1_000_000L) =
        episodeId to PlaybackEntity(episodeId, positionMs, durationMs, watched, last)

    private fun chapterRow(number: Int, positionMs: Long, watched: Boolean, last: Long) =
        row(MagisEntities.episodeIdFor(itemId, number), positionMs, watched, last)

    private fun series(chapters: List<GatewayEpisode>?, vararg rows: Pair<String, PlaybackEntity>) =
        primaryAction(TitleKind.SERIES, contentId, chapters, mapOf(*rows))

    private fun movie(vararg rows: Pair<String, PlaybackEntity>) =
        primaryAction(TitleKind.MOVIE, contentId, null, mapOf(*rows))

    // ---- movie ----

    @Test
    fun `a fresh movie says Reproducir`() {
        assertEquals(PrimaryAction("Reproducir", null), movie())
    }

    @Test
    fun `a movie left halfway says Continuar`() {
        assertEquals("Continuar", movie(row(MagisEntities.movieEpisodeId(itemId), 60_000, false, 1))!!.label)
    }

    @Test
    fun `a finished movie or one that was only opened says Reproducir`() {
        assertEquals("Reproducir", movie(row(MagisEntities.movieEpisodeId(itemId), 990_000, true, 1))!!.label)
        assertEquals("Reproducir", movie(row(MagisEntities.movieEpisodeId(itemId), 0, false, 1))!!.label)
    }

    // ---- series ----

    @Test
    fun `a series whose chapters are not loaded has no action yet`() {
        assertNull(series(null))
        assertNull(series(emptyList()))
    }

    @Test
    fun `a fresh series starts at its first chapter`() {
        assertEquals(PrimaryAction("Reproducir episodio 1", 1), series(chapters(12)))
    }

    @Test
    fun `a chapter left halfway is where you continue`() {
        val out = series(chapters(12), chapterRow(5, 60_000, false, 100))!!
        assertEquals(PrimaryAction("Continuar episodio 5", 5), out)
    }

    @Test
    fun `after finishing a chapter the next one is offered`() {
        val out = series(chapters(12), chapterRow(5, 990_000, true, 100))!!
        assertEquals(PrimaryAction("Reproducir episodio 6", 6), out)
    }

    @Test
    fun `when everything is watched it starts over from the first chapter`() {
        val rows = (1..3).map { chapterRow(it, 990_000, true, it.toLong()) }.toTypedArray()
        assertEquals(PrimaryAction("Reproducir episodio 1", 1), series(chapters(3), *rows))
    }

    @Test
    fun `a chapter that was only opened does not displace the one being watched`() {
        val out = series(
            chapters(6),
            chapterRow(3, 30_000, false, 100),
            chapterRow(4, 0, false, 200),
        )!!
        assertEquals(PrimaryAction("Continuar episodio 3", 3), out)
    }

    @Test
    fun `a chapter abandoned long ago does not pull you back thirty chapters`() {
        // Dragon Ball: e104 left at 38% the morning before, then e127..e136 finished that night.
        val rows = buildList {
            add(chapterRow(104, 380_000, false, 1))
            (127..136).forEach { add(chapterRow(it, 990_000, true, it.toLong() + 10)) }
        }.toTypedArray()
        assertEquals(PrimaryAction("Reproducir episodio 137", 137), series(chapters(153), *rows))
    }

    @Test
    fun `a very long series with progress deep inside it still picks the right chapter`() {
        // 153 chapters (Dragon Ball's size), chapter 100 left halfway: the answer must not be the
        // first chapter and must not depend on how long the list is.
        val out = series(chapters(153), chapterRow(100, 60_000, false, 100))!!
        assertEquals(PrimaryAction("Continuar episodio 100", 100), out)
        assertEquals(PrimaryAction("Reproducir episodio 1", 1), series(chapters(153)))
    }

    // ---- seasonTitle ----

    @Test
    fun `a season suffix is replaced`() {
        assertEquals("Breaking Bad T2", seasonTitle("Breaking Bad T5", 2))
        assertEquals("Show T3", seasonTitle("Show T10", 3))
        assertEquals("Show T2", seasonTitle("Show t1", 2))
    }

    @Test
    fun `a title without a suffix gets one`() {
        assertEquals("Dragon Ball Daima T2", seasonTitle("Dragon Ball Daima", 2))
    }

    @Test
    fun `a blank title becomes just the season`() {
        assertEquals("T2", seasonTitle("  ", 2))
        assertEquals("T2", seasonTitle("", 2))
    }

    @Test
    fun `T and digits inside the name are not a suffix`() {
        assertEquals("T2000 T2", seasonTitle("T2000", 2))
        assertEquals("Alien T T2", seasonTitle("Alien T", 2))
    }
}
