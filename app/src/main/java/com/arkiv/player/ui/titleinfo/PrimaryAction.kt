package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.ChapterProgress
import com.arkiv.player.data.ContinueWatchingRule
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.gateway.GatewayEpisode

/**
 * What the page's main button says and, for a series, which chapter it plays. [season] is set when
 * the source gives each chapter its own season (plugins repeat episode 1 in every season); null
 * means the number alone identifies the chapter (Magis: each season is a separate title).
 */
data class PrimaryAction(val label: String, val chapterNumber: Int?, val season: Int? = null) {
    /** Whether [chapter] is the one this action plays. */
    fun plays(chapter: GatewayEpisode): Boolean =
        chapter.number == chapterNumber && (season == null || chapter.seasonOrOne == season)

    /** The target's key in the page's lists (see [GatewayEpisode.listKey]); null when there is none. */
    val listKey: String? get() = chapterNumber?.let { chapterListKey(season ?: 1, it) }
}

/**
 * The main button. Reads progress, never writes: [movieEpisodeId] and [chapterId] name the library
 * rows in the source's own scheme and [progress] is keyed by episode id.
 *
 * Movie: "Continuar" when it was left halfway, else "Reproducir".
 *
 * Series: the SAME rule the home's "Continue watching" row and `ItemDetail.resumeEpisode` use
 * ([ContinueWatchingRule]), so the page cannot disagree with them. "The first unwatched chapter"
 * would be wrong (see that rule's KDoc: it sent people thirty chapters back). Its fallbacks are
 * `resumeEpisode`'s: the first chapter not watched, else the first chapter. With several seasons
 * in one list the label names the season ("Continuar T2 · E5").
 *
 * Null for a series whose chapters are not loaded yet: there is nothing to play.
 */
fun primaryAction(
    kind: TitleKind,
    movieEpisodeId: String,
    chapterId: (GatewayEpisode) -> String,
    chapters: List<GatewayEpisode>?,
    progress: Map<String, PlaybackEntity>,
): PrimaryAction? {
    if (kind == TitleKind.MOVIE) {
        val row = progress[movieEpisodeId]
        val halfway = row != null && row.positionMs > 0 && !row.watched
        return PrimaryAction(if (halfway) "Continuar" else "Reproducir", null)
    }
    if (chapters.isNullOrEmpty()) return null

    val rows = chapters.mapNotNull { chapter ->
        progress[chapterId(chapter)]?.let { ChapterProgress(it.episodeId, it.positionMs, it.watched, it.lastPlayedAt) }
    }
    val offer = ContinueWatchingRule.choose(rows) { id ->
        val index = chapters.indexOfFirst { chapterId(it) == id }
        if (index >= 0) chapters.getOrNull(index + 1)?.let(chapterId) else null
    }
    val offered = offer?.let { o ->
        chapters.firstOrNull { chapterId(it) == o.episodeId }?.let { it to !o.isNext }
    }
    val (target, continuing) = offered
        ?: ((chapters.firstOrNull { progress[chapterId(it)]?.watched != true } ?: chapters.first()) to false)
    val verb = if (continuing) "Continuar" else "Reproducir"
    val severalSeasons = chapters.any { it.season != null } && chapters.map { it.seasonOrOne }.distinct().size > 1
    val label = if (severalSeasons) "$verb T${target.seasonOrOne} · E${target.number}" else "$verb episodio ${target.number}"
    return PrimaryAction(label, target.number, target.season?.let { target.seasonOrOne })
}

private val SEASON_SUFFIX = Regex("""\s+T\d+$""", RegexOption.IGNORE_CASE)

/**
 * The title a sibling season is saved under. The portal's sibling entries carry only a content id
 * and a season number, so the title is derived: the current one without its trailing " T<n>", plus
 * " T<number>" ("Breaking Bad T5" -> "Breaking Bad T2").
 */
fun seasonTitle(currentTitle: String, number: Int): String {
    val base = currentTitle.trim().replace(SEASON_SUFFIX, "").trim()
    return if (base.isEmpty()) "T$number" else "$base T$number"
}
