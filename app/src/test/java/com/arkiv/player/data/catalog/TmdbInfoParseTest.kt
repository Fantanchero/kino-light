package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** What the info page reads from TMDB, parsed without the network. */
class TmdbInfoParseTest {

    private val movieJson = """
        {"id":1233413,"overview":"Benoit Blanc regresa.","tagline":"Nada es lo que parece",
         "release_date":"2025-11-26","runtime":144,"vote_average":7.6,
         "genres":[{"id":35,"name":"Comedia"},{"id":80,"name":"Crimen"}],
         "credits":{
           "cast":[{"name":"Daniel Craig"},{"name":"Josh O'Connor"},{"name":"Glenn Close"}],
           "crew":[{"name":"Nick Crowe","job":"Producer"},{"name":"Rian Johnson","job":"Director"}]},
         "release_dates":{"results":[
           {"iso_3166_1":"US","release_dates":[{"certification":"PG-13","type":3}]},
           {"iso_3166_1":"CO","release_dates":[{"certification":"12+","type":3}]}]}}
    """.trimIndent()

    @Test fun `a movie parses in full`() {
        val info = parseTmdbInfo(movieJson, "movie")
        assertNotNull(info)
        info!!
        assertEquals(1233413, info.id)
        assertEquals("Benoit Blanc regresa.", info.overview)
        assertEquals("Nada es lo que parece", info.tagline)
        assertEquals("2025", info.year)
        assertEquals(144, info.runtimeMinutes)
        assertEquals(7.6, info.voteAverage!!, 0.001)
        assertEquals(listOf("Comedia", "Crimen"), info.genres)
        assertEquals(listOf("Rian Johnson"), info.directors)
        assertEquals(listOf("Daniel Craig", "Josh O'Connor", "Glenn Close"), info.cast)
    }

    @Test fun `the certification prefers Colombia over the United States`() {
        assertEquals("12+", parseTmdbInfo(movieJson, "movie")!!.certification)
    }

    @Test fun `without a Latin American release it falls back to the United States`() {
        val json = """
            {"id":1,"release_dates":{"results":[
              {"iso_3166_1":"FR","release_dates":[{"certification":"TP","type":3}]},
              {"iso_3166_1":"US","release_dates":[{"certification":"R","type":3}]}]}}
        """.trimIndent()
        assertEquals("R", parseTmdbInfo(json, "movie")!!.certification)
    }

    @Test fun `blank certifications are skipped and none at all gives empty`() {
        val json = """
            {"id":1,"release_dates":{"results":[
              {"iso_3166_1":"CO","release_dates":[{"certification":"","type":3},{"certification":"15+","type":4}]},
              {"iso_3166_1":"US","release_dates":[{"certification":"","type":3}]}]}}
        """.trimIndent()
        assertEquals("15+", parseTmdbInfo(json, "movie")!!.certification)
        assertEquals("", parseTmdbInfo("""{"id":1,"release_dates":{"results":[]}}""", "movie")!!.certification)
        assertEquals("", parseTmdbInfo("""{"id":1}""", "movie")!!.certification)
    }

    @Test fun `a JSON null is an empty value, never the word null`() {
        // Android's optString returns "null" for a real JSON null; the JVM's org.json does not, so
        // this only guards the isNull check the parser must keep.
        val info = parseTmdbInfo(
            """{"id":1,"overview":null,"tagline":null,"release_date":null,"runtime":null,"vote_average":null}""",
            "movie",
        )!!
        assertEquals("", info.overview)
        assertEquals("", info.tagline)
        assertEquals("", info.year)
        assertEquals(0, info.runtimeMinutes)
        assertNull(info.voteAverage)
    }

    @Test fun `zero votes means no score`() {
        assertNull(parseTmdbInfo("""{"id":1,"vote_average":0}""", "movie")!!.voteAverage)
        assertNull(parseTmdbInfo("""{"id":1,"vote_average":0.0}""", "movie")!!.voteAverage)
    }

    @Test fun `the cast is capped and blank names are dropped`() {
        val names = (1..12).joinToString(",") { """{"name":"Actor $it"}""" }
        val info = parseTmdbInfo("""{"id":1,"credits":{"cast":[{"name":""},$names]}}""", "movie")!!
        assertEquals(TMDB_CAST_LIMIT, info.cast.size)
        assertEquals("Actor 1", info.cast.first())
    }

    @Test fun `a series reads its runtime, creators and rating from the tv fields`() {
        val info = parseTmdbInfo(
            """
            {"id":2288,"overview":"Emily se muda.","first_air_date":"2020-10-02","episode_run_time":[30],
             "genres":[{"id":35,"name":"Comedia"}],
             "created_by":[{"name":"Darren Star"}],
             "content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-MA"}]}}
            """.trimIndent(),
            "tv",
        )!!
        assertEquals("2020", info.year)
        assertEquals(30, info.runtimeMinutes)
        assertEquals(listOf("Darren Star"), info.directors)
        assertEquals("TV-MA", info.certification)
    }

    @Test fun `a series with no episode runtime has zero`() {
        assertEquals(0, parseTmdbInfo("""{"id":1,"episode_run_time":[]}""", "tv")!!.runtimeMinutes)
        assertEquals(0, parseTmdbInfo("""{"id":1}""", "tv")!!.runtimeMinutes)
    }

    @Test fun `no response or an unreadable one is null`() {
        assertNull(parseTmdbInfo(null, "movie"))
        assertNull(parseTmdbInfo("<html>502 Bad Gateway</html>", "movie"))
    }

    @Test fun `a response with no id is not a title`() {
        assertNull(parseTmdbInfo("""{"status_code":34,"status_message":"not found"}""", "movie"))
    }

    // ---- /find ----

    @Test fun `find returns the movie id`() {
        assertEquals(1233413, parseFindMovieId("""{"movie_results":[{"id":1233413}],"tv_results":[]}"""))
    }

    @Test fun `find with no movie result is null`() {
        assertNull(parseFindMovieId("""{"movie_results":[],"tv_results":[{"id":5}]}"""))
        assertNull(parseFindMovieId("""{}"""))
        assertNull(parseFindMovieId(null))
        assertNull(parseFindMovieId("not json"))
    }
}
