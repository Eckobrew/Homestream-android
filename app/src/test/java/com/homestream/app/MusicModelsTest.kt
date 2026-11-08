package com.homestream.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicModelsTest {
    @Test fun releaseDatesPreserveMetadataPrecision() {
        assertEquals("2027", releaseLabel("2027"))
        assertTrue(releaseLabel("2027-04").contains("2027"))
        assertFalse(releaseLabel("2027-04").contains("01"))
        assertNotEquals("Unknown", releaseLabel("2027-04-03"))
        listOf(null, "", "null", "2027-02-30", "2027-13", "2027-04-03T00:00:00Z").forEach {
            assertEquals("Unknown", releaseLabel(it))
        }
    }
    @Test fun albumAndTrackKeepOpaqueIdsSeparateFromVideo() {
        val album = MusicAlbum(JSONObject("""{"id":"release-group","title":"Album","requested":true,"inLibrary":false}"""))
        assertTrue(album.requested)
        assertFalse(album.inLibrary)
        assertEquals("release-group", album.id)
        val track = MusicTrack(JSONObject("""{"id":"opaque-track","streamUrl":"/api/mobile/v1/music/stream?trackId=opaque-track"}"""))
        assertEquals("opaque-track", track.id)
    }
}
