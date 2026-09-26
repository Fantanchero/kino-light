package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.MAGIS_SERIES
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.ui.formatRuntime
import com.arkiv.player.ui.plainSynopsis
import java.util.Locale

enum class TitleKind { MOVIE, SERIES }

/**
 * What the info page draws. Every field but [title] and [kind] is optional: the page paints only
 * what exists, so it looks right with just the portal's data and gets better when TMDB adds more.
 *
 * Deliberately NOT `GatewayResult`: that one still carries torrent-era fields (`seeders`,
 * `sizeBytes`) and no synopsis, genres or backdrop. Chapters do reuse `GatewayEpisode` and
 * `GatewaySeries`, which are already source-neutral.
 */
data class TitleInfo(
    val title: String,
    val kind: TitleKind,
    val poster: String? = null,
    val backdrop: String? = null,
    val synopsis: String = "",
    val genres: List<String> = emptyList(),
    val year: String = "",
    val score: Double? = null,
    val runtimeMinutes: Int = 0,
    val episodeCount: Int = 0,
    val seasonNumber: Int? = null,
)

/** The card the person tapped, as the page's first (instant) paint. */
fun CatalogItem.toTitleInfo(): TitleInfo = TitleInfo(
    title = title.ifBlank { id },
    kind = if (type in MAGIS_SERIES) TitleKind.SERIES else TitleKind.MOVIE,
    poster = poster?.takeIf { it.isNotBlank() },
    backdrop = backdrop?.takeIf { it.isNotBlank() },
    synopsis = plainSynopsis(description),
    genres = genres.filter { it.isNotBlank() },
    score = score,
    runtimeMinutes = durationS / 60,
    episodeCount = episodeCount,
)

/**
 * A Magis search result as the catalog item the page opens with, or null when it is not a Magis
 * title (another source, or the portal sent no `content_id`).
 *
 * The ref is rebuilt with [MagisRef] instead of copying the result's: it is the same descriptor
 * home cards carry, and it is what a route can reproduce after process death.
 */
fun GatewayResult.toMagisCatalogItem(): CatalogItem? {
    val contentId = extra["content_id"].orEmpty()
    if (source != "magis" || contentId.isBlank()) return null
    val type = extra["program_type"].orEmpty().ifBlank { "movie" }
    return CatalogItem(
        id = contentId,
        title = title,
        poster = extra["poster"]?.takeIf { it.isNotBlank() },
        durationS = 0,
        ref = MagisRef(contentId, type, 0).encode(),
        type = type,
        backdrop = extra["backdrop"]?.takeIf { it.isNotBlank() },
        episodeCount = extra["episode_count"]?.toIntOrNull() ?: 0,
    )
}

/** "★ 7.9  ·  2026  ·  1 h 43 min", leaving out whatever is not known. */
fun TitleInfo.metaLine(): String = listOfNotNull(
    score?.let { String.format(Locale.US, "★ %.1f", it) },
    year.takeIf { it.isNotBlank() },
    runtimeMinutes.takeIf { it > 0 }?.let { formatRuntime(it * 60.0) },
).joinToString("  ·  ")

/** The small line above a TV title: "Película", "Serie" or "Serie · 12 episodios". */
fun TitleInfo.kindLine(): String = when (kind) {
    TitleKind.MOVIE -> "Película"
    TitleKind.SERIES -> listOfNotNull("Serie", episodesWord(episodeCount)).joinToString("  ·  ")
}

/** "Temporada 1 · 12 episodios", or whatever part of that is known. */
fun seasonHeader(number: Int?, episodeCount: Int): String = listOfNotNull(
    number?.takeIf { it > 0 }?.let { "Temporada $it" },
    episodesWord(episodeCount),
).joinToString("  ·  ")

private fun episodesWord(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 episodio"
    else -> "$count episodios"
}

/** "T1 · E3", or "E3" when the season is not known. */
fun chapterNumberLabel(season: Int?, number: Int): String =
    if (season != null && season > 0) "T$season · E$number" else "E$number"

/** The chapter's own name: TMDB's when there is one, else the portal's, else null. */
fun chapterName(chapter: GatewayEpisode): String? =
    chapter.tmdbTitle?.takeIf { it.isNotBlank() } ?: chapter.title.takeIf { it.isNotBlank() }

/** "4. El ataque", or "Episodio 4" when the chapter has no name. */
fun chapterLine(chapter: GatewayEpisode): String =
    chapterName(chapter)?.let { "${chapter.number}. $it" } ?: "Episodio ${chapter.number}"

/** The text on the movie's download button. */
fun downloadLabel(state: DownloadDisplayState): String = when (state) {
    DownloadDisplayState.NotDownloaded -> "Descargar"
    DownloadDisplayState.Queued -> "En cola"
    is DownloadDisplayState.Downloading ->
        state.fraction?.let { "Descargando ${(it * 100).toInt()} %" } ?: "Descargando"
    DownloadDisplayState.Done -> "Descargada"
    is DownloadDisplayState.Failed -> "Falló la descarga"
    DownloadDisplayState.NeedsConfirmation -> "En espera"
}
