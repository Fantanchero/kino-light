package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.magis.MagisRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleInfoTest {

    private fun item(
        type: String = "movie",
        title: String = "Nos vemos en la oficina",
        description: String = "",
        poster: String? = "https://img/p.jpg",
        backdrop: String? = "https://img/b.jpg",
        genres: List<String> = listOf("Drama", "Romance"),
        score: Double? = 7.9,
        durationS: Int = 6224,
        episodeCount: Int = 0,
    ) = CatalogItem(
        id = "c1", title = title, poster = poster, durationS = durationS,
        ref = MagisRef("c1", type, 0).encode(), type = type, genres = genres, score = score,
        backdrop = backdrop, description = description, episodeCount = episodeCount,
    )

    // ---- CatalogItem -> TitleInfo ----

    @Test
    fun `a catalog item keeps everything the page draws`() {
        val info = item(type = "teleplay", description = "Una oficinista harta.", episodeCount = 12).toTitleInfo()
        assertEquals("Nos vemos en la oficina", info.title)
        assertEquals(TitleKind.SERIES, info.kind)
        assertEquals("https://img/p.jpg", info.poster)
        assertEquals("https://img/b.jpg", info.backdrop)
        assertEquals("Una oficinista harta.", info.synopsis)
        assertEquals(listOf("Drama", "Romance"), info.genres)
        assertEquals(7.9, info.score!!, 0.0)
        assertEquals(103, info.runtimeMinutes)
        assertEquals(12, info.episodeCount)
    }

    @Test
    fun `every portal series type is a series and everything else is a movie`() {
        for (type in listOf("teleplay", "series", "variety")) {
            assertEquals(type, TitleKind.SERIES, item(type = type).toTitleInfo().kind)
        }
        assertEquals(TitleKind.MOVIE, item(type = "movie").toTitleInfo().kind)
        assertEquals(TitleKind.MOVIE, item(type = "").toTitleInfo().kind)
    }

    @Test
    fun `blank images become null and blank genres are dropped`() {
        val info = item(poster = " ", backdrop = "", genres = listOf("Drama", " ", "")).toTitleInfo()
        assertNull(info.poster)
        assertNull(info.backdrop)
        assertEquals(listOf("Drama"), info.genres)
    }

    @Test
    fun `html in a synopsis is stripped`() {
        assertEquals("Hola mundo", item(description = "Hola<br>mundo").toTitleInfo().synopsis)
    }

    @Test
    fun `a blank title falls back to the id`() {
        assertEquals("c1", item(title = "").toTitleInfo().title)
    }

    // ---- GatewayResult -> CatalogItem ----

    private fun result(source: String = "magis", extra: Map<String, String>) =
        GatewayResult(source = source, title = "Dragon Ball Daima T1", ref = "ignored", kind = "series", extra = extra)

    @Test
    fun `a Magis search result becomes a catalog item with a rebuilt ref`() {
        val out = result(
            extra = mapOf(
                "content_id" to "c9", "program_type" to "teleplay", "episode_count" to "20",
                "poster" to "https://img/p.jpg", "backdrop" to "https://img/b.jpg",
            ),
        ).toMagisCatalogItem()!!
        assertEquals("c9", out.id)
        assertEquals("Dragon Ball Daima T1", out.title)
        assertEquals("teleplay", out.type)
        assertEquals(20, out.episodeCount)
        assertEquals("https://img/p.jpg", out.poster)
        assertEquals("https://img/b.jpg", out.backdrop)
        assertEquals(MagisRef("c9", "teleplay", 0).encode(), out.ref)
    }

    @Test
    fun `a result from another source or with no content id is not a Magis title`() {
        assertNull(result(source = "ditu", extra = mapOf("content_id" to "c9")).toMagisCatalogItem())
        assertNull(result(extra = mapOf("content_id" to " ")).toMagisCatalogItem())
        assertNull(result(extra = emptyMap()).toMagisCatalogItem())
    }

    @Test
    fun `missing extras default to a movie with no chapters and no images`() {
        val out = result(extra = mapOf("content_id" to "c9", "episode_count" to "x", "poster" to "")).toMagisCatalogItem()!!
        assertEquals("movie", out.type)
        assertEquals(0, out.episodeCount)
        assertNull(out.poster)
        assertNull(out.backdrop)
    }

    // ---- text helpers ----

    private fun info(
        kind: TitleKind = TitleKind.MOVIE,
        score: Double? = null,
        year: String = "",
        runtimeMinutes: Int = 0,
        episodeCount: Int = 0,
    ) = TitleInfo(title = "t", kind = kind, score = score, year = year, runtimeMinutes = runtimeMinutes, episodeCount = episodeCount)

    @Test
    fun `the meta line joins what is known and skips the rest`() {
        assertEquals("★ 7.9  ·  2026  ·  1 h 43 min", info(score = 7.9, year = "2026", runtimeMinutes = 103).metaLine())
        assertEquals("★ 8.0", info(score = 8.0).metaLine())
        assertEquals("2026  ·  45 min", info(year = "2026", runtimeMinutes = 45).metaLine())
        assertEquals("", info().metaLine())
    }

    @Test
    fun `the kind line says movie or series with its chapter count`() {
        assertEquals("Película", info().kindLine())
        assertEquals("Serie", info(kind = TitleKind.SERIES).kindLine())
        assertEquals("Serie  ·  1 episodio", info(kind = TitleKind.SERIES, episodeCount = 1).kindLine())
        assertEquals("Serie  ·  12 episodios", info(kind = TitleKind.SERIES, episodeCount = 12).kindLine())
    }

    @Test
    fun `the season header names the season and counts its episodes`() {
        assertEquals("Temporada 1  ·  12 episodios", seasonHeader(1, 12))
        assertEquals("Temporada 1  ·  1 episodio", seasonHeader(1, 1))
        assertEquals("12 episodios", seasonHeader(null, 12))
        assertEquals("Temporada 2", seasonHeader(2, 0))
        assertEquals("", seasonHeader(null, 0))
    }

    @Test
    fun `a chapter label carries the season only when it is known`() {
        assertEquals("T1 · E3", chapterNumberLabel(1, 3))
        assertEquals("E3", chapterNumberLabel(null, 3))
        assertEquals("E3", chapterNumberLabel(0, 3))
    }

    @Test
    fun `a chapter prefers the TMDB name, then the portal's, then a plain number`() {
        fun ch(title: String, tmdb: String?) = GatewayEpisode(number = 4, title = title, ref = "r", tmdbTitle = tmdb)
        assertEquals("El ataque", chapterName(ch("Daima T1_4", "El ataque")))
        assertEquals("Daima T1_4", chapterName(ch("Daima T1_4", " ")))
        assertNull(chapterName(ch(" ", null)))
        assertEquals("4. El ataque", chapterLine(ch("Daima T1_4", "El ataque")))
        assertEquals("Episodio 4", chapterLine(ch(" ", null)))
    }

    @Test
    fun `the download label follows the download state`() {
        assertEquals("Descargar", downloadLabel(DownloadDisplayState.NotDownloaded))
        assertEquals("En cola", downloadLabel(DownloadDisplayState.Queued))
        assertEquals("Descargando 45 %", downloadLabel(DownloadDisplayState.Downloading(0.456f)))
        assertEquals("Descargando", downloadLabel(DownloadDisplayState.Downloading(null)))
        assertEquals("Descargada", downloadLabel(DownloadDisplayState.Done))
        assertEquals("Falló la descarga", downloadLabel(DownloadDisplayState.Failed(null)))
        assertEquals("En espera", downloadLabel(DownloadDisplayState.NeedsConfirmation))
    }
}
