package com.homestream.app

/** Keep sorting and watched-state filtering consistent across library screens. */
fun libraryItems(items: List<Media>, sort: Int, filter: Int): List<Media> {
    val filtered = items.filter {
        when (filter) {
            1 -> !it.json.optBoolean("played")
            2 -> it.json.optBoolean("played")
            3 -> it.resumeMs > 0 && !it.json.optBoolean("played")
            else -> true
        }
    }
    return when (sort) {
        1 -> filtered.sortedByDescending { it.title.lowercase(java.util.Locale.ROOT) }
        2 -> filtered.sortedWith(compareByDescending<Media> { it.json.optInt("year") }.thenBy { it.title.lowercase(java.util.Locale.ROOT) })
        3 -> filtered.sortedWith(compareBy<Media> { it.json.optBoolean("played") }.thenBy { it.title.lowercase(java.util.Locale.ROOT) })
        else -> filtered.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
    }
}
