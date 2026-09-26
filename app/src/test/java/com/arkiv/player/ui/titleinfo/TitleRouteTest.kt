package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.data.plugin.PluginRef
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleRouteTest {

    /** What Navigation does with a route: split path and query, URL-decode each value. */
    private fun argsOf(route: String): Map<String, String> {
        val (path, query) = route.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val out = mutableMapOf("id" to URLDecoder.decode(path.removePrefix("title/"), "UTF-8"))
        query.split("&").filter { it.isNotEmpty() }.forEach { pair ->
            val (k, v) = pair.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            out[k] = URLDecoder.decode(v, "UTF-8")
        }
        return out
    }

    private fun roundTrip(item: CatalogItem): CatalogItem? = titleRoute(item)?.let { r -> argsOf(r).let { m -> titleItemFrom { m[it] } } }

    private fun base(
        id: String = "c1",
        title: String = "Nos vemos en la oficina",
        type: String = "teleplay",
        description: String = "Ji-yoon es una oficinista.",
    ) = CatalogItem(
        id = id, title = title, poster = "https://img/p.jpg?w=300&h=450", durationS = 6224,
        adult = true, ref = MagisRef(id, type, 0).encode(), type = type,
        genres = listOf("Drama", "Sci-Fi"), score = 7.7, backdrop = "https://img/b.jpg",
        description = description, episodeCount = 12,
    )

    @Test
    fun `a card survives the route round trip`() {
        val item = base()
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `characters that break URLs come back intact`() {
        val nasty = "Ñandú & Co? #1 100% \"A+B\" it's 🎬 ¡Hola!"
        val out = roundTrip(base(title = nasty, description = nasty))!!
        assertEquals(nasty, out.title)
        assertEquals(nasty, out.description)
    }

    @Test
    fun `a long synopsis is cut at 600 characters with an ellipsis`() {
        val out = roundTrip(base(description = "a".repeat(5000)))!!
        assertEquals(TITLE_DESC_MAX, out.description.length)
        assertTrue(out.description.endsWith("…"))
    }

    @Test
    fun `a synopsis of exactly 600 characters is left alone`() {
        val text = "b".repeat(TITLE_DESC_MAX)
        assertEquals(text, clipSynopsis(text))
        assertEquals(text, roundTrip(base(description = text))!!.description)
    }

    @Test
    fun `the worst-case route stays well under a few kilobytes`() {
        val accents = "é".repeat(TITLE_DESC_MAX)
        assertTrue(titleRoute(base(description = accents))!!.length < 6000)
    }

    @Test
    fun `a blank id has no route`() {
        assertNull(titleRoute(base(id = " ")))
    }

    @Test
    fun `missing arguments rebuild a plain movie`() {
        val out = titleItemFrom { if (it == "id") "c5" else null }!!
        assertEquals("c5", out.id)
        assertEquals("movie", out.type)
        assertEquals("", out.title)
        assertNull(out.poster)
        assertNull(out.backdrop)
        assertNull(out.score)
        assertEquals(emptyList<String>(), out.genres)
        assertEquals(0, out.episodeCount)
        assertFalse(out.adult)
    }

    @Test
    fun `a route with no id cannot be rebuilt`() {
        assertNull(titleItemFrom { null })
        assertNull(titleItemFrom { if (it == "id") "  " else null })
    }

    @Test
    fun `the ref is rebuilt from the id and the type`() {
        val out = roundTrip(base(id = "c7", type = "variety"))!!
        assertEquals(MagisRef("c7", "variety", 0).encode(), out.ref)
    }

    @Test
    fun `a search result gets a route only when it is a Magis title`() {
        fun result(source: String, extra: Map<String, String>) =
            GatewayResult(source = source, title = "T", ref = "r", extra = extra)
        assertTrue(titleRoute(result("magis", mapOf("content_id" to "c9")))!!.startsWith("title/c9"))
        assertNull(titleRoute(result("ditu", mapOf("content_id" to "c9"))))
        assertNull(titleRoute(result("magis", emptyMap())))
    }

    // ---- plugin titles ----

    private fun pluginResult(
        kind: String = "series",
        itemId: String = "i1",
        ref: String = PluginRef("demo", itemId, if (kind == "series") PluginRef.SERIES else PluginRef.MOVIE, "own").encode(),
        title: String = "Título ñ & Co",
        extra: Map<String, String> = emptyMap(),
    ) = GatewayResult(
        source = "plugin:demo", title = title, ref = ref, kind = kind, year = "2020",
        extra = mapOf(
            "pluginItemId" to itemId, "pluginName" to "Demo", "color" to "#FF8800",
            "poster" to "https://img/p.jpg?w=300&h=450", "backdrop" to "https://img/b.jpg",
            "overview" to "Sinopsis del plugin.", "genres" to "Drama, Comedia", "rating" to "7.5",
            "runtimeMinutes" to "45", "tmdbId" to "123", "imdbId" to "tt1234567",
        ) + extra,
    )

    private fun pluginArgs(result: GatewayResult): Map<String, String> = argsOf(titleRoute(result)!!)

    @Test
    fun `a plugin series result survives the route round trip`() {
        val result = pluginResult()
        val args = pluginArgs(result)
        val item = titleItemFrom { args[it] }!!
        assertEquals("i1", item.id)
        assertEquals("Título ñ & Co", item.title)
        assertEquals(result.ref, item.ref)
        assertEquals("series", item.type)
        assertEquals("https://img/p.jpg?w=300&h=450", item.poster)
        assertEquals("https://img/b.jpg", item.backdrop)
        assertEquals("Sinopsis del plugin.", item.description)
        assertEquals(listOf("Drama", "Comedia"), item.genres)
        assertEquals(7.5, item.score!!, 0.001)
        assertEquals(2700, item.durationS)
        assertEquals(
            TitleOrigin.Plugin(PluginTitleExtras("Demo", "#FF8800", "2020", 123, "tt1234567")),
            titleOriginFrom { args[it] },
        )
    }

    @Test
    fun `a plugin movie keeps its kind`() {
        val args = pluginArgs(pluginResult(kind = "movie"))
        assertEquals("movie", titleItemFrom { args[it] }!!.type)
    }

    @Test
    fun `a Magis route keeps the Magis origin and a route with no source is Magis`() {
        val args = argsOf(titleRoute(base())!!)
        assertEquals(TitleOrigin.Magis, titleOriginFrom { args[it] })
        assertEquals(TitleOrigin.Magis, titleOriginFrom { null })
    }

    @Test
    fun `hostile characters in a plugin title come back intact`() {
        val nasty = "Ñandú & Co? #1 100% \"A+B\" it's 🎬 ¡Hola!"
        val args = pluginArgs(pluginResult(title = nasty, extra = mapOf("overview" to nasty)))
        val item = titleItemFrom { args[it] }!!
        assertEquals(nasty, item.title)
        assertEquals(nasty, item.description)
    }

    @Test
    fun `a huge ref and huge image URLs stay under the cap and the URLs are left out`() {
        val hugeRef = PluginRef("demo", "i1", PluginRef.SERIES, "r".repeat(3000)).encode()
        val huge = "https://img/" + "x".repeat(3000)
        val route = titleRoute(
            pluginResult(ref = hugeRef, extra = mapOf("poster" to huge, "backdrop" to huge, "overview" to "é".repeat(5000))),
        )!!
        assertTrue("route was ${route.length} chars", route.length < 9000)
        val args = argsOf(route)
        val item = titleItemFrom { args[it] }!!
        assertEquals(hugeRef, item.ref)
        assertNull(item.poster)
        assertNull(item.backdrop)
        assertEquals(TITLE_DESC_MAX, item.description.length)
    }

    @Test
    fun `an image URL at the limit is kept`() {
        val atLimit = "https://img/" + "x".repeat(TITLE_URL_MAX - "https://img/".length)
        val args = pluginArgs(pluginResult(extra = mapOf("poster" to atLimit)))
        assertEquals(atLimit, titleItemFrom { args[it] }!!.poster)
    }

    @Test
    fun `a plugin result that cannot be opened has no route`() {
        assertNull(titleRoute(pluginResult(itemId = " ")))
        assertNull(titleRoute(pluginResult(ref = "garbage")))
        // Another plugin's ref under this plugin's source.
        assertNull(titleRoute(pluginResult(ref = PluginRef("other", "i1", PluginRef.SERIES, "own").encode())))
        // Not a plugin, not Magis: Caracol keeps its own flow.
        assertNull(titleRoute(GatewayResult(source = "ditu", title = "x", ref = "ditu1:a:b")))
    }

    @Test
    fun `a plugin route with no ref cannot rebuild an item`() {
        assertNull(titleItemFrom { if (it == "id") "i1" else if (it == "src") "plugin" else null })
    }

    @Test
    fun `a plugin route with an unknown type rebuilds a movie`() {
        val item = titleItemFrom {
            mapOf("id" to "i1", "src" to "plugin", "ref" to "plg1:demo:abc", "type" to "teleplay")[it]
        }!!
        assertEquals("movie", item.type)
    }

    @Test
    fun `a bad TMDB id or a missing plugin name is tolerated`() {
        val origin = titleOriginFrom { mapOf("src" to "plugin", "tmdb" to "abc")[it] }
        assertEquals(TitleOrigin.Plugin(PluginTitleExtras()), origin)
    }
}
