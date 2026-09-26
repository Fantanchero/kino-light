package com.arkiv.player.ui.titleinfo

import com.arkiv.player.AppGraph
import com.arkiv.player.data.MagisEntities
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.MAGIS_SERIES
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.local.DownloadSource
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.ui.home.MagisDownloadActions
import com.arkiv.player.ui.home.magisDownloadActions
import com.arkiv.player.ui.home.toGatewayResult
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback

/** Where a series' seasons come from. */
sealed interface SeasonModel {
    /**
     * Every season is a separate title the source lists as a sibling (Magis). Choosing one swaps the
     * page's item for the one [itemFor] builds.
     */
    class Siblings(val itemFor: (current: CatalogItem, season: SeasonRef) -> CatalogItem) : SeasonModel

    /**
     * One chapter list holds every season (plugins): the seasons are read from the chapters and
     * choosing one only changes which are shown.
     */
    data object InList : SeasonModel
}

/** What TMDB can be asked from: an id the source published, either may be empty. Never a title. */
data class TmdbHint(val tmdbId: Int = 0, val imdbId: String = "")

/** The name and color a source shows next to a title (a plugin's name and its manifest color). */
data class TitleBadge(val label: String, val colorArgb: Long)

/**
 * Everything the information page needs from the source a title came from, so the view model and
 * the two screens never name a source's ids or playback paths. [MagisTitleSource] wraps what the
 * page did before sources existed; `PluginTitleSource` is the plugin one.
 */
interface TitleSource {
    val seasons: SeasonModel

    /** Null when the source cannot download (plugins); the screens then draw no download UI. */
    val downloads: MagisDownloadActions?
    val canDownload: Boolean get() = downloads != null

    val badge: TitleBadge? get() = null

    /** A year the source already knows, shown before TMDB answers. */
    val initialYear: String get() = ""

    /** The library item id progress rows are keyed under. */
    fun itemId(item: CatalogItem): String
    fun movieEpisodeId(item: CatalogItem): String
    fun chapterEpisodeId(item: CatalogItem, chapter: GatewayEpisode): String

    /** The item as the playback and download code of the source expects it. */
    fun gatewayResult(item: CatalogItem): GatewayResult

    suspend fun tmdbHint(item: CatalogItem): TmdbHint
    suspend fun playMovie(result: GatewayResult): PlaybackResult
    suspend fun playSeason(
        result: GatewayResult,
        chapters: List<GatewayEpisode>,
        chosen: GatewayEpisode,
        series: GatewaySeries?,
    ): PlaybackResult
}

/** The behavior the page had for Magis before sources existed, unchanged. */
class MagisTitleSource(
    override val downloads: MagisDownloadActions?,
    private val movieImdbId: suspend (ref: String) -> String?,
    private val onPlayMovie: suspend (GatewayResult) -> PlaybackResult,
    private val onPlaySeason: suspend (GatewayResult, List<GatewayEpisode>, GatewayEpisode, GatewaySeries?) -> PlaybackResult,
) : TitleSource {

    override val seasons: SeasonModel = SeasonModel.Siblings { current, season ->
        current.copy(
            id = season.contentId,
            ref = MagisRef(season.contentId, current.type, 0).encode(),
            title = seasonTitle(current.title, season.number),
            episodeCount = 0,
        )
    }

    override fun itemId(item: CatalogItem): String = MagisEntities.itemIdFor(item.id)
    override fun movieEpisodeId(item: CatalogItem): String = MagisEntities.movieEpisodeId(itemId(item))
    override fun chapterEpisodeId(item: CatalogItem, chapter: GatewayEpisode): String =
        MagisEntities.episodeIdFor(itemId(item), chapter.number)

    override fun gatewayResult(item: CatalogItem): GatewayResult = item.toGatewayResult()

    override suspend fun tmdbHint(item: CatalogItem): TmdbHint =
        if (item.type in MAGIS_SERIES) TmdbHint() else TmdbHint(imdbId = movieImdbId(item.ref).orEmpty())

    override suspend fun playMovie(result: GatewayResult): PlaybackResult = onPlayMovie(result)

    override suspend fun playSeason(
        result: GatewayResult,
        chapters: List<GatewayEpisode>,
        chosen: GatewayEpisode,
        series: GatewaySeries?,
    ): PlaybackResult = onPlaySeason(result, chapters, chosen, series)
}

/** Wires [PluginTitleSource] to the app's real plugin playback paths. */
internal fun pluginTitleSource(graph: AppGraph, extras: PluginTitleExtras): PluginTitleSource {
    val playback = SearchPlayback(graph)
    return PluginTitleSource(
        extras = extras,
        onPlayMovie = { playback.playPlugin(it) },
        onPlaySeason = { season, chapters, chosen, series -> playback.playPluginSeason(season, chapters, chosen, series) },
    )
}

/** Wires [MagisTitleSource] to the app's real Magis paths. */
internal fun magisTitleSource(graph: AppGraph): MagisTitleSource {
    val playback = SearchPlayback(graph)
    return MagisTitleSource(
        downloads = magisDownloadActions(graph, playback)
            .takeIf { DownloadSource.hasStrategy("magis", graph.downloadStrategies.keys) },
        movieImdbId = { ref -> graph.contentSource.movieImdbId(ref) },
        onPlayMovie = { playback.playMagis(it) },
        onPlaySeason = { season, chapters, chosen, series -> playback.playMagisSeason(season, chapters, chosen, series) },
    )
}
