package com.arkiv.player.ui.home

import com.arkiv.player.AppGraph
import com.arkiv.player.ui.search.SearchPlayback

/** Wires [MagisDownloadActions] to the app's real playback helper and download queue. */
internal fun magisDownloadActions(graph: AppGraph, playback: SearchPlayback) = MagisDownloadActions(
    episodeIdForMovie = { playback.magisEpisodeId(it) },
    episodeIdForChapter = { season, chapter, series -> playback.magisEpisodeIdFor(season, chapter, series) },
    enqueue = { episodeId, source -> graph.localDownloads.enqueue(episodeId, source) },
)
