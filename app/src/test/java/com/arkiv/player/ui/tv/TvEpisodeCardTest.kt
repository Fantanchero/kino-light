package com.arkiv.player.ui.tv

import com.arkiv.player.data.db.PlaybackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvEpisodeCardTest {

    private fun progress(positionMs: Long, durationMs: Long, watched: Boolean = false) =
        PlaybackEntity("e", positionMs, durationMs, watched, 1L)

    @Test
    fun `no progress means an empty bar and no label`() {
        assertEquals(0f, chipWatchedFraction(null), 0f)
        assertNull(chipProgressLabel(null, 24))
    }

    @Test
    fun `the bar is the fraction watched, capped at full`() {
        assertEquals(0.5f, chipWatchedFraction(progress(60_000, 120_000)), 0.0001f)
        assertEquals(1f, chipWatchedFraction(progress(500_000, 120_000)), 0f)
    }

    @Test
    fun `a chapter with no known duration has an empty bar`() {
        assertEquals(0f, chipWatchedFraction(progress(60_000, 0)), 0f)
    }

    @Test
    fun `minutes show only when the chapter has a real duration`() {
        assertEquals("10 de 24 min", chipProgressLabel(progress(600_000, 1_440_000), 24))
        assertNull(chipProgressLabel(progress(600_000, 1_440_000), 0))
    }

    @Test
    fun `a watched chapter says Visto`() {
        assertEquals("Visto", chipProgressLabel(progress(1_400_000, 1_440_000, watched = true), 24))
    }

    @Test
    fun `an unstarted chapter has no label`() {
        assertNull(chipProgressLabel(progress(0, 1_440_000), 24))
    }
}
