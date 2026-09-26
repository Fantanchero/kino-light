package com.arkiv.player.data.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Covers how [TmdbApi] authenticates now that it talks DIRECTLY to TMDB (sub-project 2A): the key
 * goes as a query parameter and no session header travels anymore, because there's no gateway on
 * the other end to authenticate against. Not meant to be a complete suite for [TmdbApi].
 */
class TmdbApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun api() = TmdbApi(
        apiKey = "test-key",
        baseUrl = server.url("/3").toString().trimEnd('/'),
        client = OkHttpClient(),
    )

    @Test
    fun `the key and the language go in the query`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))

        api().browse("movie", 1)

        val request = server.takeRequest()
        assertEquals("test-key", request.requestUrl?.queryParameter("api_key"))
        assertEquals("es-MX", request.requestUrl?.queryParameter("language"))
        assertEquals("/3/movie/popular", request.requestUrl?.encodedPath)
    }

    @Test
    fun `no gateway session header travels`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))

        api().browse("movie", 1)

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("X-Arkiv-Device"))
        assertNull(request.getHeader("X-Arkiv-Key"))
    }

    @Test
    fun `info of a movie asks for its credits and release dates in Spanish`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":603,"runtime":136}"""))

        val info = api().info("movie", 603)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/movie/603", url.encodedPath)
        assertEquals("credits,release_dates", url.queryParameter("append_to_response"))
        assertEquals("es-MX", url.queryParameter("language"))
        assertEquals(136, info!!.runtimeMinutes)
    }

    @Test
    fun `info of a series asks for its credits and content ratings`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":2288}"""))

        api().info("tv", 2288)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/tv/2288", url.encodedPath)
        assertEquals("credits,content_ratings", url.queryParameter("append_to_response"))
    }

    @Test
    fun `info of anything but a movie or a series is refused without a request`() = runBlocking {
        assertNull(api().info("person", 1))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `info is null when TMDB fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        assertNull(api().info("movie", 603))
    }

    @Test
    fun `a movie is found by its IMDb id`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"movie_results":[{"id":1233413}],"tv_results":[]}"""))

        val id = api().movieIdByImdb("tt6300910")

        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/find/tt6300910", url.encodedPath)
        assertEquals("imdb_id", url.queryParameter("external_source"))
        assertEquals(1233413, id)
    }

    @Test
    fun `something that is not an IMDb id never reaches TMDB`() = runBlocking {
        assertNull(api().movieIdByImdb("Batman"))
        assertNull(api().movieIdByImdb("tt12"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `search sends the key and does not ask for adult content`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))

        api().search("movie", "batman")

        val url = server.takeRequest().requestUrl!!
        assertEquals("test-key", url.queryParameter("api_key"))
        assertEquals("batman", url.queryParameter("query"))
        assertEquals("false", url.queryParameter("include_adult"))
    }

    @Test
    fun `by default it points at TMDB, not at any server of our own`() {
        // The base is fixed at build time and isn't configurable from Settings, same as the key.
        assertEquals("https://api.themoviedb.org/3", TmdbApi.BASE_TMDB)
    }
}
