package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.local.DownloadSource
import com.arkiv.player.data.local.EnqueueOutcome

/**
 * The non-Compose core of "download a Magis title", shared by the home card's long-press and the
 * info page. Same behavior `rememberMagisActions` used to inline: a movie is enqueued with the
 * source its episode id maps to; a season is enqueued chapter by chapter under `"magis"`, each one
 * a separate file, and the queue already knows how to group them by series.
 *
 * What stays with the caller, because it is Compose-only: the notification-permission request, the
 * duplicate notice and the toasts.
 *
 * The three dependencies come in as functions so this is testable without an `AppGraph`.
 */
class MagisDownloadActions(
    private val episodeIdForMovie: suspend (GatewayResult) -> String?,
    private val episodeIdForChapter: suspend (GatewayResult, GatewayEpisode, GatewaySeries?) -> String?,
    private val enqueue: suspend (episodeId: String, source: String) -> EnqueueOutcome,
) {
    /** Enqueues a movie. Null when its episode could not be prepared, in which case nothing was queued. */
    suspend fun enqueueMovie(result: GatewayResult): EnqueueOutcome? {
        val episodeId = episodeIdForMovie(result) ?: return null
        return enqueue(episodeId, DownloadSource.sourceFor(episodeId))
    }

    /** Enqueues each chapter in turn. A chapter whose episode could not be prepared is skipped. */
    suspend fun enqueueChapters(
        season: GatewayResult,
        chapters: List<GatewayEpisode>,
        series: GatewaySeries?,
    ): List<EnqueueOutcome> = chapters.mapNotNull { chapter ->
        val episodeId = episodeIdForChapter(season, chapter, series) ?: return@mapNotNull null
        enqueue(episodeId, "magis")
    }
}

/** The toast after enqueueing [requested] chapters, from what the queue answered for each. */
fun chapterEnqueueMessage(outcomes: List<EnqueueOutcome>, requested: Int): String {
    val queued = outcomes.count { it == EnqueueOutcome.QUEUED }
    return when {
        queued == 0 -> "Esos capítulos ya estaban guardados."
        queued == requested -> "Descargando $requested capítulo(s)…"
        else -> "Se encolaron $queued de $requested (el resto ya estaba)."
    }
}
