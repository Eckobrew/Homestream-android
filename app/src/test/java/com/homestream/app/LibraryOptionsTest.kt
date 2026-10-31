package com.homestream.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryOptionsTest {
    private fun item(id: String, title: String, year: Int, played: Boolean = false, position: Long = 0) = Media(JSONObject()
        .put("id", id).put("title", title).put("year", year).put("played", played).put("playbackPositionTicks", position))
    private val items = listOf(item("z", "Zulu", 2000, true), item("a", "alpha", 2025, position = 100000), item("b", "Beta", 2010))
    @Test fun sortsTitlesAndYearsWithoutChangingInput() {
        assertEquals(listOf("a", "b", "z"), libraryItems(items, 0, 0).map { it.id })
        assertEquals(listOf("z", "b", "a"), libraryItems(items, 1, 0).map { it.id })
        assertEquals(listOf("a", "b", "z"), libraryItems(items, 2, 0).map { it.id })
        assertEquals("z", items.first().id)
    }
    @Test fun filtersWatchedAndInProgressIndependently() {
        assertEquals(listOf("a", "b"), libraryItems(items, 0, 1).map { it.id })
        assertEquals(listOf("z"), libraryItems(items, 0, 2).map { it.id })
        assertEquals(listOf("a"), libraryItems(items, 0, 3).map { it.id })
    }
}
