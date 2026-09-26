package com.arkiv.player.ui.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A focus requester is only attached to an item its lazy list has composed. Pointing `focusProperties`
 * at a detached one drops the D-pad key (Compose logs "FocusRequester is not initialized" and the
 * focus does not move; older versions throw). Measured on the TV: with the episode carousel scrolled
 * to the end, Down from the first season chip did nothing.
 */
class TvTitleInfoFocusTest {

    @Test fun `an item the list has composed can be a focus destination`() {
        assertTrue(isComposedItem(listOf(4, 5, 6), 5))
    }

    @Test fun `an item scrolled out of composition cannot`() {
        assertFalse(isComposedItem(listOf(6, 7, 8), 1))
    }

    @Test fun `there is nothing to point at without a key or without items`() {
        assertFalse(isComposedItem(listOf(1, 2), null))
        assertFalse(isComposedItem(emptyList(), 1))
    }
}
