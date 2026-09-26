package com.arkiv.player.data.catalog

import org.json.JSONArray
import org.json.JSONObject

/** How many billed actors the info page keeps. */
internal const val TMDB_CAST_LIMIT = 8

/**
 * Age-rating countries in order of preference. The audience is Colombian; the United States is the
 * one every title has.
 */
private val CERTIFICATION_REGIONS = listOf("CO", "MX", "US")

/**
 * What the info page paints from TMDB, in Spanish (es-MX): the parts of a title's detail that
 * [TmdbDetail] does not carry because the search flows never needed them.
 */
data class TmdbInfo(
    val id: Int,
    val overview: String,
    val tagline: String,
    val year: String,
    /** A movie's runtime, or a series' typical episode runtime; 0 when TMDB has none. */
    val runtimeMinutes: Int,
    val genres: List<String>,
    /** TMDB's audience score (0-10); null when nobody has voted. */
    val voteAverage: Double?,
    /** A movie's directors, or a series' creators. */
    val directors: List<String>,
    val cast: List<String>,
    /** "PG-13", "12+", "TV-MA"…; empty when TMDB has none. */
    val certification: String,
)

/** `null` (JSON) and a missing key are both empty; Android's `optString` would say "null". */
private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/**
 * Parses a movie's or series' detail fetched with credits and its age ratings appended. Pure (no
 * network). Null when [json] is null, unreadable or carries no `id` (TMDB's error bodies have none).
 */
internal fun parseTmdbInfo(json: String?, type: String): TmdbInfo? {
    if (json == null) return null
    return runCatching {
        val o = JSONObject(json)
        val id = o.optInt("id").takeIf { it > 0 } ?: return@runCatching null
        val isTv = type == "tv"
        val credits = o.optJSONObject("credits")
        TmdbInfo(
            id = id,
            overview = o.text("overview"),
            tagline = o.text("tagline"),
            year = o.text(if (isTv) "first_air_date" else "release_date").take(4),
            runtimeMinutes = if (isTv) o.optJSONArray("episode_run_time")?.optInt(0) ?: 0 else o.optInt("runtime"),
            genres = o.optJSONArray("genres").objects().map { it.text("name") }.filter { it.isNotBlank() },
            voteAverage = o.optDouble("vote_average", 0.0).takeIf { !it.isNaN() && it > 0 },
            directors = if (isTv) {
                o.optJSONArray("created_by").objects().map { it.text("name") }
            } else {
                credits?.optJSONArray("crew").objects().filter { it.text("job") == "Director" }.map { it.text("name") }
            }.filter { it.isNotBlank() }.distinct(),
            cast = credits?.optJSONArray("cast").objects().map { it.text("name") }
                .filter { it.isNotBlank() }.take(TMDB_CAST_LIMIT),
            certification = certificationOf(o, isTv),
        )
    }.getOrNull()
}

/** The age rating of the preferred country, else the first one there is, else empty. */
private fun certificationOf(o: JSONObject, isTv: Boolean): String {
    val byRegion: List<Pair<String, String>> = if (isTv) {
        o.optJSONObject("content_ratings")?.optJSONArray("results").objects()
            .map { it.text("iso_3166_1") to it.text("rating") }
    } else {
        o.optJSONObject("release_dates")?.optJSONArray("results").objects().map { r ->
            r.text("iso_3166_1") to r.optJSONArray("release_dates").objects()
                .map { it.text("certification") }.firstOrNull { it.isNotBlank() }.orEmpty()
        }
    }.filter { it.second.isNotBlank() }
    return CERTIFICATION_REGIONS.firstNotNullOfOrNull { region -> byRegion.firstOrNull { it.first == region }?.second }
        ?: byRegion.firstOrNull()?.second.orEmpty()
}

/** The movie id in `/find/{imdb}?external_source=imdb_id`'s answer, or null when it has none. */
internal fun parseFindMovieId(json: String?): Int? {
    if (json == null) return null
    return runCatching {
        JSONObject(json).optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }.getOrNull()
}
