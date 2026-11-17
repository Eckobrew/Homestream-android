package com.homestream.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastProgressReporterTest {
    @Test
    fun idleOrDisconnectPreservesFinalStoppedReportViaPinnedApi() {
        val tracker = CastProgressTracker<String>()
        val key = castMediaSessionKey("cast-1", "movie-1", mediaItemId = 42, loadId = "load-1")

        val start = tracker.step(
            CastPoll(key, "movie-1", position = 1_000L, paused = false, ready = true),
            liveAccount = "http://hs|alice|tokA",
            nowMs = 1_000L,
            createPinnedApi = { "api-alice" },
        )
        assertEquals(1, start.size)
        assertEquals("api-alice", start[0].api)
        assertTrue(start[0].started)
        assertFalse(start[0].stopped)

        // Advance playback without hitting the 10s interval so snapshot position updates.
        val tick = tracker.step(
            CastPoll(key, "movie-1", position = 5_000L, paused = false, ready = true),
            liveAccount = "http://hs|alice|tokA",
            nowMs = 5_000L,
            createPinnedApi = { "api-alice" },
        )
        assertTrue(tick.isEmpty())

        // Receiver becomes idle / disconnects (rawCurrent == null).
        val stop = tracker.step(
            rawCurrent = null,
            liveAccount = "http://hs|alice|tokA",
            nowMs = 6_000L,
            createPinnedApi = { "api-alice" },
        )
        assertEquals(1, stop.size)
        assertEquals("api-alice", stop[0].api)
        assertEquals("movie-1", stop[0].id)
        assertEquals(5_000L, stop[0].position)
        assertTrue(stop[0].stopped)
    }

    @Test
    fun profileSwitchRetainsInvalidationAcrossTransientDisconnectAndReconnect() {
        val tracker = CastProgressTracker<String>()
        val key = castMediaSessionKey("cast-1", "movie-1", mediaItemId = 101, loadId = "load-101")

        // Profile A starts casting.
        val start = tracker.step(
            CastPoll(key, "movie-1", position = 12_000L, paused = false, ready = true),
            liveAccount = "http://hs|alice|tokA",
            nowMs = 1_000L,
            createPinnedApi = { "api-alice" },
        )
        assertEquals(1, start.size)
        assertEquals("api-alice", start[0].api)

        // User switches to Profile B while Cast continues playing Profile A's media.
        val switchEvents = tracker.step(
            CastPoll(key, "movie-1", position = 13_000L, paused = false, ready = true),
            liveAccount = "http://hs|bob|tokB",
            nowMs = 2_000L,
            createPinnedApi = { "api-bob" },
        )
        assertEquals(1, switchEvents.size)
        assertEquals("api-alice", switchEvents[0].api)
        assertEquals(12_000L, switchEvents[0].position)
        assertTrue(switchEvents[0].stopped)

        // Transient disconnect (rawCurrent == null) while on Profile B.
        val disconnect = tracker.step(
            rawCurrent = null,
            liveAccount = "http://hs|bob|tokB",
            nowMs = 3_000L,
            createPinnedApi = { "api-bob" },
        )
        assertTrue(disconnect.isEmpty())

        // Same mediaSessionId reconnects while still on Profile B -> must remain invalidated.
        val reconnect = tracker.step(
            CastPoll(key, "movie-1", position = 15_000L, paused = false, ready = true),
            liveAccount = "http://hs|bob|tokB",
            nowMs = 4_000L,
            createPinnedApi = { "api-bob" },
        )
        assertTrue(reconnect.isEmpty())
    }

    @Test
    fun repeatedLoadOfSameTitleGetsNewMediaSessionKeyAndReportsToActiveProfile() {
        val tracker = CastProgressTracker<String>()
        val firstLoad = castMediaSessionKey("cast-1", "movie-1", mediaItemId = 101, loadId = "load-101")
        val secondLoad = castMediaSessionKey("cast-1", "movie-1", mediaItemId = 202, loadId = "load-202")
        assertNotEquals(firstLoad, secondLoad)

        tracker.step(
            CastPoll(firstLoad, "movie-1", position = 1_000L, paused = false, ready = true),
            liveAccount = "http://hs|alice|tokA",
            nowMs = 1_000L,
            createPinnedApi = { "api-alice" },
        )
        // Switch profile -> invalidates firstLoad.
        tracker.step(
            CastPoll(firstLoad, "movie-1", position = 2_000L, paused = false, ready = true),
            liveAccount = "http://hs|bob|tokB",
            nowMs = 2_000L,
            createPinnedApi = { "api-bob" },
        )

        // Bob loads the same title again (new receiver mediaSessionId).
        val bobStart = tracker.step(
            CastPoll(secondLoad, "movie-1", position = 0L, paused = false, ready = true),
            liveAccount = "http://hs|bob|tokB",
            nowMs = 3_000L,
            createPinnedApi = { "api-bob" },
        )
        assertEquals(1, bobStart.size)
        assertEquals("api-bob", bobStart[0].api)
        assertTrue(bobStart[0].started)
    }
}
