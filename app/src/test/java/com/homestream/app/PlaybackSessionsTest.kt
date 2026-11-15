package com.homestream.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionsTest {
    @Test
    fun parsesNetworkSessionFromJson() {
        val json = JSONObject()
            .put("deviceId", "tv-living")
            .put("deviceName", "Living Room TV")
            .put("deviceType", "android-tv")
            .put("profileId", "nathan")
            .put("mediaId", "ep-1")
            .put("mediaType", "video")
            .put("title", "Severance")
            .put("subtitle", "S2 E1 · Hello, Ms. Cobel")
            .put("artworkUrl", "/api/image?id=1")
            .put("isPlaying", true)
            .put("positionMs", 45000L)
            .put("durationMs", 3000000L)
            .put("canSeek", true)
            .put("canSkipNext", true)
            .put("canSkipPrev", false)
            .put("updatedAt", 1700000000000L)

        val session = NetworkSession.fromJson(json)
        assertEquals("tv-living", session.deviceId)
        assertEquals("Living Room TV", session.deviceName)
        assertTrue(session.isTv)
        assertFalse(session.isIdle)
        assertTrue(session.isPlaying)
        assertEquals(45000L, session.positionMs)
        assertEquals(3000000L, session.durationMs)
    }

    @Test
    fun marksIdleTvSessionCorrectly() {
        val json = JSONObject()
            .put("deviceId", "tv-bedroom")
            .put("deviceName", "Bedroom TV")
            .put("deviceType", "android-tv")
            .put("mediaType", "idle")
            .put("title", "")

        val session = NetworkSession.fromJson(json)
        assertTrue(session.isTv)
        assertTrue(session.isIdle)
    }
}
