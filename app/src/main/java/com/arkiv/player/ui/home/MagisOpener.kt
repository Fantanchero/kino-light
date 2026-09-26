package com.arkiv.player.ui.home

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.local.DownloadSource
import com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice
import com.arkiv.player.ui.offline.rememberPostNotificationsRequest
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.search.queuedDownloadToastText
import kotlinx.coroutines.launch

/**
 * What a Magis card on the phone home can do. Tapping ([open]) goes to the title's info page. The
 * two long-press shortcuts skip the page for a movie -- [play] plays it right away, [download]
 * enqueues it -- and send a series to the page, where its chapters and downloads live. Nothing is
 * saved to the library until something plays or a download is enqueued.
 *
 * [canDownload] mirrors search's gate: it's false when no Magis download strategy is registered,
 * and the caller hides "Descargar" then.
 */
class MagisCardActions(
    val open: (CatalogItem) -> Unit,
    val play: (CatalogItem) -> Unit,
    val download: (CatalogItem) -> Unit,
    val canDownload: Boolean,
)

@Composable
fun rememberMagisActions(
    onPlay: (episodeId: String) -> Unit,
    onOpenTitle: (CatalogItem) -> Unit,
): MagisCardActions {
    val graph = rememberGraph()
    val context = LocalContext.current
    val playback = remember(graph) { SearchPlayback(graph) }
    val downloads = remember(graph, playback) { magisDownloadActions(graph, playback) }
    val scope = rememberCoroutineScope()
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnOpenTitle by rememberUpdatedState(onOpenTitle)
    // Same helpers the search screen's download path uses, so the home behaves identically: the
    // API 33+ notification prompt and the "you already have that" notice on a duplicate.
    val askNotifications = rememberPostNotificationsRequest()
    val notifyDuplicates = rememberDuplicateDownloadNotice()
    // Whether Magis has a download strategy registered (today it always does). Same gate as search.
    val canDownload = remember { DownloadSource.hasStrategy("magis", graph.downloadStrategies.keys) }

    fun handle(result: PlaybackResult) {
        when (result) {
            is PlaybackResult.Ready -> currentOnPlay(result.episodeId)
            is PlaybackResult.Failed -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    // Enqueues a movie for a device download, same path as SearchScreen.downloadMagisMovie: toast
    // only on a fresh QUEUE (the duplicate notice already covers the already-have cases -- showing
    // both would mislead).
    fun downloadMovie(result: GatewayResult) {
        askNotifications()
        scope.launch {
            val outcome = downloads.enqueueMovie(result)
            if (outcome == null) {
                Toast.makeText(context, "No se pudo preparar la descarga.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            notifyDuplicates(listOf(outcome))
            queuedDownloadToastText(outcome, result.title)?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    return remember(playback, canDownload) {
        MagisCardActions(
            open = { item -> currentOnOpenTitle(item) },
            play = { item ->
                if (item.isMagisSeries) currentOnOpenTitle(item)
                else scope.launch { handle(playback.playMagis(item.toGatewayResult())) }
            },
            download = { item ->
                // A series can't be enqueued whole: its page is where the chapters are picked.
                if (item.isMagisSeries) currentOnOpenTitle(item) else downloadMovie(item.toGatewayResult())
            },
            canDownload = canDownload,
        )
    }
}
