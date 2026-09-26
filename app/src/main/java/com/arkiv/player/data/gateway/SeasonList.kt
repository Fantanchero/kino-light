package com.arkiv.player.data.gateway

import org.json.JSONArray

/** What a series detail's `sameSeasonSeriesList` says about the season being read. */
internal data class SeasonList(
    /** This season's number; `null` when the list carries seasons but not this one. */
    val own: Int?,
    /** Every season with a usable id and number, this one included, ordered by number. */
    val all: List<SeasonRef>,
)

/**
 * Reads the portal's `sameSeasonSeriesList` (`{contentId, seasonNumber}` entries) for the season
 * [ownId]. Plain index loops on purpose: this lives in the tracked gateway package and must not
 * lean on the Magis package's helpers.
 */
internal fun parseSeasonList(seasons: JSONArray?, ownId: String): SeasonList {
    var own: Int? = null
    val all = mutableListOf<SeasonRef>()
    if (seasons != null) {
        for (index in 0 until seasons.length()) {
            val entry = seasons.optJSONObject(index) ?: continue
            val id = entry.optString("contentId")
            val number = entry.opt("seasonNumber")?.toString()?.toIntOrNull()
            if (id == ownId) own = number
            if (id.isNotBlank() && number != null) all.add(SeasonRef(id, number))
        }
    }
    // A SINGLE-season series doesn't show up in its own list: the portal sends an empty
    // `sameSeasonSeriesList`. That's not "which season is unknown", it's "it's the 1st", and
    // reading it as unknown turned off the ENTIRE enrichment with the imdb sitting right there
    // (measured on Dragon Ball: keyWords=tt0088509, 153 chapters, sameSeasonSeriesList=[]).
    //
    // Only when the list comes EMPTY: if it carries seasons and ours isn't in it, that IS
    // unknown, and guessing 1 would enrich with another season's chapters.
    if (own == null && (seasons == null || seasons.length() == 0)) own = 1
    return SeasonList(own, all.sortedBy { it.number })
}
