package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coming back to a Home card must also bring ITS ROW into view: on a TV the restored focus is
 * invisible when the row sits below the fold (found on a device with the last plugin row).
 * [homeRowIndexOf] locates that row; the list ends with the Magis rows, then the plugin rows, then
 * one trailing pad item, and whatever comes before them varies, so the index counts back from the end.
 */
class TvHomeRestoreRowTest {

    // Two leading items (say "Continuar viendo" and the channels row), 3 Magis rows, 2 plugin rows and
    // the trailing pad: indexes 0-1 lead, 2-4 Magis, 5-6 plugin, 7 pad.
    private val magis = listOf(listOf("a-1", "a-2"), listOf("b-1"), listOf("c-1"))
    private val plugin = listOf(listOf("plugin-p-r1-x"), listOf("plugin-p-r2-y", "plugin-p-r2-z"))
    private val total = 8

    @Test
    fun `a plugin card's row is counted back from the end`() {
        assertEquals(5, homeRowIndexOf("plugin-p-r1-x", magis, plugin, total))
        assertEquals(6, homeRowIndexOf("plugin-p-r2-z", magis, plugin, total))
    }

    @Test
    fun `a Magis card's row sits before the plugin rows`() {
        assertEquals(2, homeRowIndexOf("a-2", magis, plugin, total))
        assertEquals(4, homeRowIndexOf("c-1", magis, plugin, total))
    }

    @Test
    fun `a card no row holds has no row`() {
        assertNull(homeRowIndexOf("z-9", magis, plugin, total))
    }

    @Test
    fun `the match is the exact card key, not a prefix of a neighbouring row`() {
        val rows = listOf(listOf("a-x"), listOf("a-b-item"))
        // "a-b-item" starts with row "a"'s "a-" but belongs to the second row.
        assertEquals(1, homeRowIndexOf("a-b-item", rows, emptyList(), totalItems = 3))
    }

    @Test
    fun `a card is held only by a row that has it`() {
        assertTrue(homeCardsHold("plugin-p-r2-y", magis, plugin))
        assertTrue(homeCardsHold("b-1", magis, plugin))
        // Another plugin's row has not arrived yet (or the card is gone): nothing holds it.
        assertFalse(homeCardsHold("plugin-q-r1-x", magis, plugin))
        assertFalse(homeCardsHold("z-9", magis, plugin))
        assertFalse(homeCardsHold("a-1", emptyList(), emptyList()))
    }

    @Test
    fun `a list shorter than its rows has no index`() {
        // Only 2 items in the list but 5 rows plus the pad: the list is not laid out yet.
        assertNull(homeRowIndexOf("a-1", magis, plugin, totalItems = 2))
    }
}
