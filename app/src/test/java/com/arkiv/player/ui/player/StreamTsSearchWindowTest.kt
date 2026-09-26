package com.arkiv.player.ui.player

import com.arkiv.player.playback.MpegTs
import com.arkiv.player.playback.TsDurationProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExoPlayer learns an MPEG-TS title's duration -- and with it whether it can seek at all -- by
 * looking for the last PCR in the final [STREAM_TS_SEARCH_BYTES] of the file. media3's default
 * window is 112 800 bytes (600 packets).
 *
 * Measured on a Magis movie with three audio tracks: the video, which carries the PCR (pid 256),
 * ends 224 096 bytes before the end of the file while the audio keeps going. The default window
 * held no PCR at all, the duration came out as `TIME_UNSET`, the stream was reported
 * `seekable=false`, and every seek silently restarted the film from byte 0 while the bar sat
 * full. A title whose tail did have a PCR in that window (42 of them) reported its duration and
 * seeked fine, which is what pinned the cause down.
 *
 * These pin the three things the window has to satisfy. They do NOT prove ExoPlayer reads the
 * duration -- that only shows up on a device playing that title (`seekable=true`, `dur` known).
 */
class StreamTsSearchWindowTest {

    /** Distance from the end of the file to the last video packet, measured on the failing title. */
    private val measuredWorstCaseBytes = 224_096 + MpegTs.PACKET

    @Test
    fun `reaches back past the audio-only tail that starved the default window`() {
        assertTrue(
            "window $STREAM_TS_SEARCH_BYTES B would miss a PCR $measuredWorstCaseBytes B before the end",
            STREAM_TS_SEARCH_BYTES >= measuredWorstCaseBytes,
        )
    }

    @Test
    fun `is wider than the media3 default that failed`() {
        assertTrue(STREAM_TS_SEARCH_BYTES > 112_800)
    }

    /**
     * The proxy keeps the last [TsDurationProbe.PROBE_BYTES] of the file in memory. A window that
     * reaches further back would send ExoPlayer's very first tail read to the CDN, which answers a
     * range in anywhere from 0.2 s to 20 s -- a spinner on every start.
     */
    @Test
    fun `fits inside the tail the proxy already holds in memory`() {
        assertTrue(STREAM_TS_SEARCH_BYTES <= TsDurationProbe.PROBE_BYTES)
    }

    @Test
    fun `is a whole number of transport packets`() {
        assertEquals(0, STREAM_TS_SEARCH_BYTES % MpegTs.PACKET)
    }
}
