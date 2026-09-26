package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.magis.MagisRef
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
}
