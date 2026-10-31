package com.homestream.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaTest {
    @Test fun resumeTicksRetainLongPrecision() {
        val ms = 9_000_000_000L
        assertEquals(ms, ticksToMs(msToTicks(ms)))
        assertEquals(0L, msToTicks(-1))
        assertTrue(msToTicks(Long.MAX_VALUE) > 0)
    }
    @Test fun unavailableDiscoveryEpisodesCannotPlay() {
        assertFalse(Media(JSONObject("""{"id":"seerr-ep-42-1-1","name":"Pilot"}""")).playable)
        assertFalse(Media(JSONObject("""{"id":"demo","source":"demo","status":"available"}""")).playable)
    }
    @Test fun activeDownloadsUseTheirExistingStreamUrl() {
        val media = Media(JSONObject("""{"id":"dl-abc","status":"processing","isDownloading":true,"downloadStreamUrl":"/api/stream/download?hash=abc"}"""))
        assertTrue(media.playable)
        assertTrue(media.isDownload)
        assertEquals("/api/stream/download?hash=abc", media.downloadUrl)
    }
    @Test fun jellyfinEpisodesUseCorrectPlaybackIdAndPosition() {
        val media = Media(JSONObject("""{"id":"episode","jellyfinId":"jellyfin-id","name":"Pilot","playbackPositionTicks":1234500000}"""))
        assertTrue(media.playable)
        assertEquals("jellyfin-id", media.playbackId)
        assertEquals(123450L, media.resumeMs)
        assertEquals("Pilot", media.title)
    }
    @Test fun discoveryTitlesMatchedToJellyfinCanPlay() {
        val matched = Media(JSONObject("""{"id":"seerr-movie-42","type":"Movie","status":"available","source":"jellyfin","jellyfinId":"real-file"}"""))
        assertTrue(matched.isAvailableForPlayback)
        assertEquals("real-file", matched.playbackId)
        assertFalse(Media(JSONObject("""{"id":"seerr-movie-42","type":"Movie","status":"available"}""")).isAvailableForPlayback)
    }
}
