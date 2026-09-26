package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.MagisEntities
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SearchEvent
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.local.EnqueueOutcome
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.ui.home.MagisDownloadActions
import com.arkiv.player.ui.search.PlaybackResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TitleInfoViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // ---- fixtures ----

    private class FakeContent(
        var episodesFor: suspend (String) -> Pair<List<GatewayEpisode>, GatewaySeries?> = { emptyList<GatewayEpisode>() to null },
        var seasonsFor: suspend (String) -> List<SeasonRef> = { emptyList() },
    ) : ContentSource {
        val episodeRequests = mutableListOf<String>()
        override fun recognizes(ref: String) = true
        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = emptyFlow()
        override suspend fun resolve(ref: String): GatewayPlayable = error("not used by the page")
        override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> {
            episodeRequests += ref
            return episodesFor(ref)
        }
        override suspend fun seasonsOf(ref: String): List<SeasonRef> = seasonsFor(ref)
    }

    private fun movie(id: String = "m1") = CatalogItem(
        id = id, title = "Una peli", poster = "p", durationS = 6000, ref = MagisRef(id, "movie", 0).encode(),
        type = "movie", genres = listOf("Drama"), description = "Sinopsis del portal",
    )

    private fun show(id: String = "s1", title: String = "Una serie T1", description: String = "Sinopsis del portal") = CatalogItem(
        id = id, title = title, poster = "p", durationS = 0, ref = MagisRef(id, "teleplay", 0).encode(),
        type = "teleplay", description = description, episodeCount = 3,
    )

    private fun chapters(vararg numbers: Int) = numbers.map { GatewayEpisode(number = it, title = "E$it", ref = "r$it") }
    private fun series(tmdbId: Int = 5, season: Int = 1) = GatewaySeries(imdbId = "tt1", tmdbId = tmdbId, seasonNumber = season)
    private fun tmdb(year: String = "2026", overview: String = "Sinopsis TMDB") = TmdbDetail(
        id = 5, type = "tv", title = "t", originalTitle = "t", posterUrl = "", backdropUrl = "",
        overview = overview, year = year, imdbId = "tt1", seasons = emptyList(),
    )
    private fun noDownloads() = MagisDownloadActions({ null }, { _, _, _ -> null }, { _, _ -> EnqueueOutcome.QUEUED })

    private fun vm(
        item: CatalogItem,
        content: FakeContent = FakeContent(),
        playMovie: suspend (GatewayResult) -> PlaybackResult = { PlaybackResult.Ready("magis:${it.extra["content_id"]}::0") },
        playSeason: suspend (GatewayResult, List<GatewayEpisode>, GatewayEpisode, GatewaySeries?) -> PlaybackResult =
            { _, _, chosen, _ -> PlaybackResult.Ready("ep${chosen.number}") },
        downloads: MagisDownloadActions = noDownloads(),
        progress: (String) -> Flow<Map<String, PlaybackEntity>> = { flowOf(emptyMap()) },
        downloadStates: Flow<Map<String, DownloadDisplayState>> = flowOf(emptyMap()),
        tmdbDetail: suspend (Int) -> TmdbDetail? = { null },
    ) = TitleInfoViewModel(item, content, playMovie, playSeason, downloads, progress, downloadStates, tmdbDetail, canDownload = true)

    private fun TestScope.collect(vm: TitleInfoViewModel): List<TitleInfoEvent> {
        val events = mutableListOf<TitleInfoEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    // ---- loading ----

    @Test
    fun `a movie opens with its own data and loads nothing`() = runTest {
        val content = FakeContent()
        val vm = vm(movie(), content)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("Una peli", state.info.title)
        assertEquals(TitleKind.MOVIE, state.info.kind)
        assertEquals(EpisodesState.None, state.episodes)
        assertTrue(content.episodeRequests.isEmpty())
        assertEquals(PrimaryAction("Reproducir", null), state.primary)
    }

    @Test
    fun `a series shows Loading and then its chapters`() = runTest {
        val gate = CompletableDeferred<Pair<List<GatewayEpisode>, GatewaySeries?>>()
        val vm = vm(show(), FakeContent(episodesFor = { gate.await() }))
        advanceUntilIdle()
        assertEquals(EpisodesState.Loading, vm.state.value.episodes)
        assertNull(vm.state.value.primary)

        gate.complete(chapters(1, 2, 3) to series())
        advanceUntilIdle()
        val loaded = vm.state.value.episodes as EpisodesState.Loaded
        assertEquals(listOf(1, 2, 3), loaded.chapters.map { it.number })
        assertEquals(PrimaryAction("Reproducir episodio 1", 1), vm.state.value.primary)
        assertEquals(1, vm.state.value.info.seasonNumber)
    }

    @Test
    fun `TMDB adds the year and keeps the portal's synopsis`() = runTest {
        val vm = vm(show(), FakeContent(episodesFor = { chapters(1) to series() }), tmdbDetail = { tmdb() })
        advanceUntilIdle()
        assertEquals("2026", vm.state.value.info.year)
        assertEquals("Sinopsis del portal", vm.state.value.info.synopsis)
    }

    @Test
    fun `TMDB fills the synopsis only when the portal sent none`() = runTest {
        val vm = vm(show(description = ""), FakeContent(episodesFor = { chapters(1) to series() }), tmdbDetail = { tmdb() })
        advanceUntilIdle()
        assertEquals("Sinopsis TMDB", vm.state.value.info.synopsis)
    }

    @Test
    fun `TMDB is not asked when the series has no TMDB id, and a TMDB failure changes nothing`() = runTest {
        var calls = 0
        val noId = vm(show(), FakeContent(episodesFor = { chapters(1) to series(tmdbId = 0) }), tmdbDetail = { calls++; tmdb() })
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals("", noId.state.value.info.year)

        val failing = vm(show(), FakeContent(episodesFor = { chapters(1) to series() }), tmdbDetail = { error("no key") })
        advanceUntilIdle()
        assertEquals("", failing.state.value.info.year)
        assertTrue(failing.state.value.episodes is EpisodesState.Loaded)
    }

    @Test
    fun `sibling seasons show a selector, a single season does not, and a failure is harmless`() = runTest {
        val many = vm(show(), FakeContent(episodesFor = { chapters(1) to series() }, seasonsFor = { listOf(SeasonRef("s1", 1), SeasonRef("s2", 2)) }))
        val single = vm(show(), FakeContent(episodesFor = { chapters(1) to series() }, seasonsFor = { emptyList() }))
        val broken = vm(show(), FakeContent(episodesFor = { chapters(1) to series() }, seasonsFor = { throw GatewayException("caído") }))
        advanceUntilIdle()
        assertTrue(many.state.value.showSeasonSelector)
        assertFalse(single.state.value.showSeasonSelector)
        assertFalse(broken.state.value.showSeasonSelector)
        assertTrue(broken.state.value.episodes is EpisodesState.Loaded)
    }

    @Test
    fun `a failed load shows Failed and a retry recovers`() = runTest {
        var calls = 0
        val played = mutableListOf<Int>()
        val vm = vm(
            show(),
            FakeContent(episodesFor = { if (calls++ == 0) throw GatewayException("Sin conexión") else chapters(1, 2) to series() }),
            playSeason = { _, _, chosen, _ -> played += chosen.number; PlaybackResult.Ready("ep") },
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.episodes is EpisodesState.Failed)
        assertNull(vm.state.value.primary)

        vm.play()
        advanceUntilIdle()
        assertTrue("nothing plays while the chapters are missing", played.isEmpty())

        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.episodes is EpisodesState.Loaded)
        assertEquals(PrimaryAction("Reproducir episodio 1", 1), vm.state.value.primary)
    }

    // ---- seasons ----

    @Test
    fun `switching season rebuilds the item and reloads its chapters`() = runTest {
        val requested = mutableListOf<String>()
        val content = FakeContent(
            episodesFor = { ref -> requested += ref; chapters(1, 2) to series(season = if (ref.endsWith("s2")) 2 else 1) },
            seasonsFor = { listOf(SeasonRef("s1", 1), SeasonRef("s2", 2)) },
        )
        val vm = vm(show(id = "s1", title = "Una serie T1"), content)
        advanceUntilIdle()

        vm.selectSeason(SeasonRef("s2", 2))
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("s2", state.item.id)
        assertEquals("Una serie T2", state.item.title)
        assertEquals("Una serie T2", state.info.title)
        assertEquals(MagisRef("s2", "teleplay", 0).encode(), state.item.ref)
        assertEquals(2, state.info.seasonNumber)
        assertEquals(2, state.seasons.size)
        assertEquals(listOf(MagisRef("s1", "teleplay", 0).encode(), MagisRef("s2", "teleplay", 0).encode()), requested)

        vm.selectSeason(SeasonRef("s2", 2))
        advanceUntilIdle()
        assertEquals("choosing the current season is a no-op", 2, requested.size)
    }

    @Test
    fun `a stale season answer is ignored`() = runTest {
        val first = CompletableDeferred<Pair<List<GatewayEpisode>, GatewaySeries?>>()
        val content = FakeContent(episodesFor = { ref -> if (ref.endsWith("s1")) first.await() else chapters(20, 21) to series(season = 2) })
        val vm = vm(show(id = "s1"), content)
        advanceUntilIdle()

        vm.selectSeason(SeasonRef("s2", 2))
        advanceUntilIdle()
        first.complete(chapters(1, 2, 3) to series(season = 1))
        advanceUntilIdle()

        val loaded = vm.state.value.episodes as EpisodesState.Loaded
        assertEquals("s2", vm.state.value.item.id)
        assertEquals(listOf(20, 21), loaded.chapters.map { it.number })
    }

    // ---- progress ----

    @Test
    fun `progress flows into the main button`() = runTest {
        val progress = MutableStateFlow<Map<String, PlaybackEntity>>(emptyMap())
        val vm = vm(show(), FakeContent(episodesFor = { chapters(1, 2, 3) to series() }), progress = { progress })
        advanceUntilIdle()
        assertEquals(PrimaryAction("Reproducir episodio 1", 1), vm.state.value.primary)

        val id = MagisEntities.episodeIdFor(MagisEntities.itemIdFor("s1"), 2)
        progress.value = mapOf(id to PlaybackEntity(id, 60_000, 1_000_000, false, 100))
        advanceUntilIdle()
        assertEquals(PrimaryAction("Continuar episodio 2", 2), vm.state.value.primary)
    }

    @Test
    fun `download states flow into the state`() = runTest {
        val vm = vm(movie(), downloadStates = flowOf(mapOf("magis:m1::0" to DownloadDisplayState.Done)))
        advanceUntilIdle()
        assertEquals(DownloadDisplayState.Done, vm.state.value.downloads["magis:m1::0"])
    }

    // ---- play ----

    @Test
    fun `playing a series hands the loaded chapters and series to the season path`() = runTest {
        val calls = mutableListOf<Triple<GatewayResult, List<Int>, Int>>()
        val seriesBlock = series()
        var seenSeries: GatewaySeries? = null
        val vm = vm(
            show(),
            FakeContent(episodesFor = { chapters(1, 2, 3) to seriesBlock }),
            playSeason = { season, chs, chosen, s -> calls += Triple(season, chs.map { it.number }, chosen.number); seenSeries = s; PlaybackResult.Ready("magis:s1::e${chosen.number}") },
        )
        val events = collect(vm)
        advanceUntilIdle()

        vm.play()
        advanceUntilIdle()
        assertEquals(1, calls.size)
        assertEquals(listOf(1, 2, 3), calls[0].second)
        assertEquals(1, calls[0].third)
        assertEquals("s1", calls[0].first.extra["content_id"])
        assertEquals(seriesBlock, seenSeries)
        assertEquals(listOf<TitleInfoEvent>(TitleInfoEvent.OpenPlayer("magis:s1::e1")), events)
        assertFalse(vm.state.value.resolving)

        vm.play(3)
        advanceUntilIdle()
        assertEquals(3, calls[1].third)
    }

    @Test
    fun `playing a movie uses the movie path`() = runTest {
        val vm = vm(movie())
        val events = collect(vm)
        advanceUntilIdle()
        vm.play()
        advanceUntilIdle()
        assertEquals(listOf<TitleInfoEvent>(TitleInfoEvent.OpenPlayer("magis:m1::0")), events)
    }

    @Test
    fun `a failed play says why and clears the spinner`() = runTest {
        val vm = vm(movie(), playMovie = { PlaybackResult.Failed("No se pudo preparar la reproducción de Xuper.") })
        val events = collect(vm)
        advanceUntilIdle()
        vm.play()
        advanceUntilIdle()
        assertEquals(listOf<TitleInfoEvent>(TitleInfoEvent.Message("No se pudo preparar la reproducción de Xuper.")), events)
        assertFalse(vm.state.value.resolving)
    }

    @Test
    fun `a play that throws becomes a message too`() = runTest {
        val vm = vm(movie(), playMovie = { error("boom") })
        val events = collect(vm)
        advanceUntilIdle()
        vm.play()
        advanceUntilIdle()
        assertTrue(events.single() is TitleInfoEvent.Message)
        assertFalse(vm.state.value.resolving)
    }

    @Test
    fun `a second play is ignored while the first is resolving`() = runTest {
        val gate = CompletableDeferred<PlaybackResult>()
        var calls = 0
        val vm = vm(movie(), playMovie = { calls++; gate.await() })
        advanceUntilIdle()
        vm.play()
        advanceUntilIdle()
        assertTrue(vm.state.value.resolving)
        vm.play()
        advanceUntilIdle()
        assertEquals(1, calls)
        gate.complete(PlaybackResult.Ready("magis:m1::0"))
        advanceUntilIdle()
        assertFalse(vm.state.value.resolving)
    }

    // ---- downloads ----

    @Test
    fun `downloading a season enqueues every chapter and reports the batch`() = runTest {
        val queued = mutableListOf<String>()
        val downloads = MagisDownloadActions({ null }, { _, chapter, _ -> "magis:s1::e${chapter.number}" }, { id, _ -> queued += id; EnqueueOutcome.QUEUED })
        val vm = vm(show(), FakeContent(episodesFor = { chapters(1, 2, 3) to series() }), downloads = downloads)
        val events = collect(vm)
        advanceUntilIdle()

        vm.downloadSeason()
        advanceUntilIdle()
        assertEquals(listOf("magis:s1::e1", "magis:s1::e2", "magis:s1::e3"), queued)
        assertEquals(
            TitleInfoEvent.Downloaded(List(3) { EnqueueOutcome.QUEUED }, "Descargando 3 capítulo(s)…", noticeDuplicates = false),
            events.single(),
        )
    }

    @Test
    fun `downloading chosen chapters enqueues only those`() = runTest {
        val queued = mutableListOf<String>()
        val downloads = MagisDownloadActions({ null }, { _, chapter, _ -> "magis:s1::e${chapter.number}" }, { id, _ -> queued += id; EnqueueOutcome.QUEUED })
        val vm = vm(show(), FakeContent(episodesFor = { chapters(1, 2, 3) to series() }), downloads = downloads)
        advanceUntilIdle()
        vm.downloadChapters(listOf(2))
        advanceUntilIdle()
        assertEquals(listOf("magis:s1::e2"), queued)
    }

    @Test
    fun `downloading a movie reports the queued toast`() = runTest {
        val downloads = MagisDownloadActions({ "magis:m1::0" }, { _, _, _ -> null }, { _, _ -> EnqueueOutcome.QUEUED })
        val vm = vm(movie(), downloads = downloads)
        val events = collect(vm)
        advanceUntilIdle()
        vm.downloadMovie()
        advanceUntilIdle()
        assertEquals(
            TitleInfoEvent.Downloaded(listOf(EnqueueOutcome.QUEUED), "Descarga de \"Una peli\" en cola", noticeDuplicates = true),
            events.single(),
        )
    }

    @Test
    fun `a movie whose download cannot be prepared says so`() = runTest {
        val vm = vm(movie(), downloads = noDownloads())
        val events = collect(vm)
        advanceUntilIdle()
        vm.downloadMovie()
        advanceUntilIdle()
        assertEquals(listOf<TitleInfoEvent>(TitleInfoEvent.Message("No se pudo preparar la descarga.")), events)
    }
}
