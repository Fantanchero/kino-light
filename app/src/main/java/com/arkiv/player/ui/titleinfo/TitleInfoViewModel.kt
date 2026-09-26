package com.arkiv.player.ui.titleinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.AppGraph
import com.arkiv.player.data.MagisEntities
import com.arkiv.player.data.catalog.TmdbInfo
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.local.ChapterDownloadState
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.local.DownloadSource
import com.arkiv.player.data.local.EnqueueOutcome
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.ui.home.MagisDownloadActions
import com.arkiv.player.ui.home.chapterEnqueueMessage
import com.arkiv.player.ui.home.magisDownloadActions
import com.arkiv.player.ui.home.toGatewayResult
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.search.queuedDownloadToastText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a series' chapters are: a movie has none to load. */
sealed interface EpisodesState {
    /** A movie: there is nothing to load. */
    data object None : EpisodesState
    data object Loading : EpisodesState
    data class Loaded(val chapters: List<GatewayEpisode>, val series: GatewaySeries?) : EpisodesState
    data class Failed(val message: String) : EpisodesState
}

data class TitleInfoState(
    /** The card the page was opened with (or the sibling season switched to). Kept whole so `toGatewayResult()` works exactly as on the home. */
    val item: CatalogItem,
    val info: TitleInfo,
    val episodes: EpisodesState,
    /** Every season the portal lists, this one included; the selector shows only when there is more than one. */
    val seasons: List<SeasonRef> = emptyList(),
    /** Playback progress by episode id. Read-only. */
    val progress: Map<String, PlaybackEntity> = emptyMap(),
    val downloads: Map<String, DownloadDisplayState> = emptyMap(),
    /** A play is being prepared: the button shows a spinner and ignores taps. */
    val resolving: Boolean = false,
) {
    val itemId: String get() = MagisEntities.itemIdFor(item.id)

    val primary: PrimaryAction?
        get() = primaryAction(
            info.kind,
            MagisEntities.movieEpisodeId(itemId),
            { MagisEntities.episodeIdFor(itemId, it.number) },
            (episodes as? EpisodesState.Loaded)?.chapters,
            progress,
        )

    val showSeasonSelector: Boolean get() = seasons.size > 1
}

/** One-shot things the screen reacts to. */
sealed interface TitleInfoEvent {
    data class OpenPlayer(val episodeId: String) : TitleInfoEvent
    data class Message(val text: String) : TitleInfoEvent

    /**
     * The outcome of a download request, for the Compose-only duplicate notice and toast.
     * [noticeDuplicates] is true for a movie (its toast says nothing about duplicates, the notice
     * does) and false for a chapter batch (its own message already covers "ya estaban guardados",
     * and showing both would say the same thing twice).
     */
    data class Downloaded(
        val outcomes: List<EnqueueOutcome>,
        val toast: String?,
        val noticeDuplicates: Boolean,
    ) : TitleInfoEvent
}

/**
 * State and actions of the info page, shared by the phone and TV screens.
 *
 * Opens instantly from the card that was tapped and then loads, in the background: the series'
 * chapters (the only step whose failure the person sees), its sibling seasons, and, when TMDB
 * knows this exact title, its year, runtime, genres, cast and rating. Reads playback progress and download state; **writes nothing**.
 * Only [play] and the download functions reach code that saves to the library, and they do it
 * through the same paths the home and search already use.
 *
 * Every dependency is a function or a flow so the whole thing is testable without an `AppGraph`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TitleInfoViewModel(
    initial: CatalogItem,
    private val content: ContentSource,
    private val playMovie: suspend (GatewayResult) -> PlaybackResult,
    private val playSeason: suspend (GatewayResult, List<GatewayEpisode>, GatewayEpisode, GatewaySeries?) -> PlaybackResult,
    private val downloads: MagisDownloadActions,
    observeProgress: (itemId: String) -> Flow<Map<String, PlaybackEntity>>,
    downloadStates: Flow<Map<String, DownloadDisplayState>>,
    private val tmdbInfo: suspend (type: String, tmdbId: Int) -> TmdbInfo?,
    private val tmdbMovieId: suspend (imdbId: String) -> Int?,
    val canDownload: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(
        initial.toTitleInfo().let { info ->
            TitleInfoState(
                item = initial,
                info = info,
                episodes = if (info.kind == TitleKind.SERIES) EpisodesState.Loading else EpisodesState.None,
            )
        },
    )
    val state: StateFlow<TitleInfoState> = _state.asStateFlow()

    private val _events = Channel<TitleInfoEvent>(Channel.BUFFERED)
    val events: Flow<TitleInfoEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        // Progress follows the season being shown: switching season restarts the observation.
        viewModelScope.launch {
            _state.map { it.item.id }.distinctUntilChanged()
                .flatMapLatest { contentId -> observeProgress(MagisEntities.itemIdFor(contentId)) }
                .collect { rows -> _state.update { it.copy(progress = rows) } }
        }
        viewModelScope.launch {
            downloadStates.collect { states -> _state.update { it.copy(downloads = states) } }
        }
        loadEpisodes()
        if (_state.value.info.kind == TitleKind.MOVIE) {
            val item = _state.value.item
            viewModelScope.launch { enrich(item, null) }
        }
    }

    // ---- loading ----

    private fun loadEpisodes() {
        val item = _state.value.item
        if (_state.value.info.kind == TitleKind.MOVIE) return
        loadJob?.cancel()
        _state.update { it.copy(episodes = EpisodesState.Loading) }
        loadJob = viewModelScope.launch {
            val (chapters, series) = try {
                content.episodesWithSeries(item.ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    if (s.item.id != item.id) s
                    else s.copy(episodes = EpisodesState.Failed(e.message ?: "No se pudieron cargar los episodios"))
                }
                return@launch
            }
            // The person may have switched season while this request was in flight.
            if (_state.value.item.id != item.id) return@launch
            // The portal answers a transient failure with a detail that has no chapters instead of
            // an error. An empty Loaded left the button on "Cargando…" for ever with no way out.
            if (chapters.isEmpty()) {
                _state.update { it.copy(episodes = EpisodesState.Failed("No hay episodios disponibles por ahora")) }
                return@launch
            }
            _state.update { s ->
                s.copy(
                    episodes = EpisodesState.Loaded(chapters, series),
                    info = s.info.copy(
                        seasonNumber = series?.seasonNumber?.takeIf { it > 0 } ?: s.info.seasonNumber,
                        episodeCount = chapters.size.takeIf { it > 0 } ?: s.info.episodeCount,
                    ),
                )
            }
            launch { loadSeasons(item) }
            launch { enrich(item, series) }
        }
    }

    /** Best-effort: a failure just means no selector. */
    private suspend fun loadSeasons(item: CatalogItem) {
        val seasons = attempt { content.seasonsOf(item.ref) } ?: emptyList()
        _state.update { if (it.item.id == item.id) it.copy(seasons = seasons) else it }
    }

    /**
     * Best-effort: when TMDB has this exact title, adds what only it knows (see [withTmdb]). A series
     * is identified by the TMDB id its chapters call already resolved; a movie by the IMDb id its
     * source publishes, never by its title. Any failure or miss leaves the page as the portal drew it.
     */
    private suspend fun enrich(item: CatalogItem, series: GatewaySeries?) {
        val (type, tmdbId) = tmdbEntry(item, series) ?: return
        val detail = attempt { tmdbInfo(type, tmdbId) } ?: return
        _state.update { s -> if (s.item.id != item.id) s else s.copy(info = s.info.withTmdb(detail)) }
    }

    /** The TMDB `(type, id)` this page is about, or null when it cannot be pinned down exactly. */
    private suspend fun tmdbEntry(item: CatalogItem, series: GatewaySeries?): Pair<String, Int>? =
        when (_state.value.info.kind) {
            TitleKind.SERIES -> series?.tmdbId?.takeIf { it > 0 }?.let { "tv" to it }
            TitleKind.MOVIE -> attempt { content.movieImdbId(item.ref) }
                ?.let { imdb -> attempt { tmdbMovieId(imdb) } }
                ?.let { "movie" to it }
        }

    fun retry() {
        if (_state.value.episodes is EpisodesState.Failed) loadEpisodes()
    }

    /**
     * Switches to a sibling season. The portal lists only a content id and a number for it, so the
     * item is rebuilt from the current one: its ref from the new id, its title through [seasonTitle].
     * Everything else on the page (what TMDB added included) is kept: it is the same series.
     */
    fun selectSeason(season: SeasonRef) {
        val current = _state.value.item
        if (season.contentId == current.id) return
        val next = current.copy(
            id = season.contentId,
            ref = MagisRef(season.contentId, current.type, 0).encode(),
            title = seasonTitle(current.title, season.number),
            episodeCount = 0,
        )
        _state.update { s ->
            s.copy(
                item = next,
                info = s.info.copy(title = next.title.ifBlank { next.id }, seasonNumber = season.number, episodeCount = 0),
                episodes = EpisodesState.Loading,
                progress = emptyMap(),
            )
        }
        loadEpisodes()
    }

    // ---- play ----

    /**
     * Plays the movie, or a chapter of the series ([chapterNumber], or the one the main button
     * offers). Ignored while another play is resolving, and for a series whose chapters are not
     * loaded: it needs them to know what to play. A series goes through the season path with the
     * chapters and series this page already loaded (that path saves the whole season and must not
     * re-request them).
     */
    fun play(chapterNumber: Int? = null) {
        val s = _state.value
        if (s.resolving) return
        val result = s.item.toGatewayResult()
        val loaded = s.episodes as? EpisodesState.Loaded
        val chosen = loaded?.chapters?.firstOrNull { it.number == (chapterNumber ?: s.primary?.chapterNumber) }
        if (s.info.kind == TitleKind.SERIES && chosen == null) return
        viewModelScope.launch {
            _state.update { it.copy(resolving = true) }
            val outcome = try {
                if (chosen != null && loaded != null) playSeason(result, loaded.chapters, chosen, loaded.series)
                else playMovie(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaybackResult.Failed(e.message ?: "No se pudo preparar la reproducción.")
            }
            _state.update { it.copy(resolving = false) }
            _events.send(
                when (outcome) {
                    is PlaybackResult.Ready -> TitleInfoEvent.OpenPlayer(outcome.episodeId)
                    is PlaybackResult.Failed -> TitleInfoEvent.Message(outcome.message)
                },
            )
        }
    }

    // ---- downloads ----

    fun downloadMovie() {
        val s = _state.value
        if (s.info.kind != TitleKind.MOVIE) return
        viewModelScope.launch {
            val outcome = attempt { downloads.enqueueMovie(s.item.toGatewayResult()) }
            if (outcome == null) {
                _events.send(TitleInfoEvent.Message("No se pudo preparar la descarga."))
                return@launch
            }
            _events.send(
                TitleInfoEvent.Downloaded(
                    listOf(outcome), queuedDownloadToastText(outcome, s.info.title), noticeDuplicates = true,
                ),
            )
        }
    }

    fun downloadChapters(numbers: List<Int>) {
        val s = _state.value
        val loaded = s.episodes as? EpisodesState.Loaded ?: return
        val chosen = loaded.chapters.filter { it.number in numbers }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val outcomes = attempt { downloads.enqueueChapters(s.item.toGatewayResult(), chosen, loaded.series) } ?: emptyList()
            _events.send(
                TitleInfoEvent.Downloaded(outcomes, chapterEnqueueMessage(outcomes, chosen.size), noticeDuplicates = false),
            )
        }
    }

    fun downloadSeason() {
        val loaded = _state.value.episodes as? EpisodesState.Loaded ?: return
        downloadChapters(loaded.chapters.map { it.number })
    }

    /** Runs [block]; null on any failure except cancellation, which propagates. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** Wires the view model to the app's real sources. Used by both the phone and the TV screen. */
internal fun titleInfoViewModel(graph: AppGraph, item: CatalogItem): TitleInfoViewModel {
    val playback = SearchPlayback(graph)
    return TitleInfoViewModel(
        initial = item,
        content = graph.contentSource,
        playMovie = { playback.playMagis(it) },
        playSeason = { season, chapters, chosen, series -> playback.playMagisSeason(season, chapters, chosen, series) },
        downloads = magisDownloadActions(graph, playback),
        observeProgress = { itemId -> graph.repository.observePlayback(itemId) },
        downloadStates = graph.repository.observeDownloadRows()
            .map { rows -> rows.associate { it.episodeId to ChapterDownloadState.of(it) } },
        tmdbInfo = { type, tmdbId -> graph.tmdbApi.info(type, tmdbId) },
        tmdbMovieId = { imdbId -> graph.tmdbApi.movieIdByImdb(imdbId) },
        canDownload = DownloadSource.hasStrategy("magis", graph.downloadStrategies.keys),
    )
}
