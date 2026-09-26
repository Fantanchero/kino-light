package com.arkiv.player.data.gateway

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonListTest {

    private fun parse(json: String?, ownId: String) = parseSeasonList(json?.let { JSONArray(it) }, ownId)

    @Test
    fun `a single-season series sends an empty list and is season 1`() {
        val out = parse("[]", "c1")
        assertEquals(1, out.own)
        assertEquals(emptyList<SeasonRef>(), out.all)
    }

    @Test
    fun `no list at all is also season 1`() {
        val out = parse(null, "c1")
        assertEquals(1, out.own)
        assertEquals(emptyList<SeasonRef>(), out.all)
    }

    @Test
    fun `a multi-season series lists every season including its own, in order`() {
        val out = parse(
            """[{"contentId":"c3","seasonNumber":3},{"contentId":"c1","seasonNumber":1},{"contentId":"c2","seasonNumber":"2"}]""",
            "c2",
        )
        assertEquals(2, out.own)
        assertEquals(listOf(SeasonRef("c1", 1), SeasonRef("c2", 2), SeasonRef("c3", 3)), out.all)
    }

    @Test
    fun `a list that does not include this season leaves its number unknown`() {
        // Guessing 1 here would enrich this season with another season's chapters.
        val out = parse("""[{"contentId":"c8","seasonNumber":1}]""", "c1")
        assertNull(out.own)
        assertEquals(listOf(SeasonRef("c8", 1)), out.all)
    }

    @Test
    fun `malformed entries are dropped and never crash`() {
        val out = parse(
            """[{"contentId":"","seasonNumber":1},{"contentId":"c4"},{"contentId":"c5","seasonNumber":"x"},{"contentId":"c1","seasonNumber":1}]""",
            "c1",
        )
        assertEquals(1, out.own)
        assertEquals(listOf(SeasonRef("c1", 1)), out.all)
    }

    @Test
    fun `this season with an unreadable number stays unknown`() {
        val out = parse("""[{"contentId":"c1","seasonNumber":"x"},{"contentId":"c2","seasonNumber":2}]""", "c1")
        assertNull(out.own)
        assertEquals(listOf(SeasonRef("c2", 2)), out.all)
    }
}
