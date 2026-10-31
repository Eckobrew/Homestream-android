package com.homestream.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EpisodeQueueTest {
    private fun episode(id: String, season: Int, episode: Int, virtual: Boolean = false) = Media(JSONObject()
        .put("id", id).put("type", "Episode").put("status", "available")
        .put("seasonNumber", season).put("episodeNumber", episode)
        .put("locationType", if (virtual) "Virtual" else "FileSystem"))
    @Test fun advancesAcrossSeasonsAndSupportsPrevious() {
        val queue = EpisodeQueue(listOf(episode("s2e1", 2, 1), episode("s1e2", 1, 2), episode("s1e1", 1, 1)))
        assertEquals("s1e2", queue.next("s1e1")!!.id)
        assertEquals("s2e1", queue.next("s1e2")!!.id)
        assertEquals("s1e2", queue.previous("s2e1")!!.id)
        assertNull(queue.previous("s1e1"))
        assertNull(queue.next("s2e1"))
        assertNull(queue.next("unknown"))
    }
    @Test fun excludesUnavailableEpisodesAndDeduplicates() {
        val first = episode("first", 1, 1)
        val queue = EpisodeQueue(listOf(first, episode("missing", 1, 2, virtual = true), episode("third", 1, 3), first))
        assertEquals(listOf("first", "third"), queue.items.map { it.id })
        assertEquals(listOf("third"), queue.upcoming("first").map { it.id })
        assertTrue(queue.upcoming("missing").isEmpty())
    }
}
