package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.ChapterProgress
import com.arkiv.player.data.ContinueWatchingRule
import com.arkiv.player.data.MagisEntities
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.gateway.GatewayEpisode

/** What the page's main button says and, for a series, which chapter it plays. */
data class PrimaryAction(val label: String, val chapterNumber: Int?)

/**
 * The main button. Reads progress, never writes: ids come from the pure [MagisEntities] functions
 * and [progress] is keyed by episode id.
 *
 * Movie: "Continuar" when it was left halfway, else "Reproducir".
 *
 * Series: the SAME rule the home's "Continue watching" row and `ItemDetail.resumeEpisode` use
 * ([ContinueWatchingRule]), so the page cannot disagree with them. "The first unwatched chapter"
 * would be wrong (see that rule's KDoc: it sent people thirty chapters back). Its fallbacks are
 * `resumeEpisode`'s: the first chapter not watched, else the first chapter.
 *
 * Null for a series whose chapters are not loaded yet: there is nothing to play.
 */
fun primaryAction(
    kind: TitleKind,
    contentId: String,
    chapters: List<GatewayEpisode>?,
    progress: Map<String, PlaybackEntity>,
): PrimaryAction? {
    val itemId = MagisEntities.itemIdFor(contentId)
    if (kind == TitleKind.MOVIE) {
        val row = progress[MagisEntities.movieEpisodeId(itemId)]
        val halfway = row != null && row.positionMs > 0 && !row.watched
        return PrimaryAction(if (halfway) "Continuar" else "Reproducir", null)
    }
    if (chapters.isNullOrEmpty()) return null

    val idOf = { chapter: GatewayEpisode -> MagisEntities.episodeIdFor(itemId, chapter.number) }
    val rows = chapters.mapNotNull { chapter ->
        progress[idOf(chapter)]?.let { ChapterProgress(it.episodeId, it.positionMs, it.watched, it.lastPlayedAt) }
    }
    val offer = ContinueWatchingRule.choose(rows) { id ->
        val index = chapters.indexOfFirst { idOf(it) == id }
        if (index >= 0) chapters.getOrNull(index + 1)?.let(idOf) else null
    }
    val offered = offer?.let { o ->
        chapters.firstOrNull { idOf(it) == o.episodeId }?.let { it to !o.isNext }
    }
    val (target, continuing) = offered
        ?: ((chapters.firstOrNull { progress[idOf(it)]?.watched != true } ?: chapters.first()) to false)
    val verb = if (continuing) "Continuar" else "Reproducir"
    return PrimaryAction("$verb episodio ${target.number}", target.number)
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
