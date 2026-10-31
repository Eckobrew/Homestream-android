package com.homestream.app

/** Episode order is independent of API response order and excludes unavailable files. */
class EpisodeQueue(items: List<Media> = emptyList()) {
    val items = items.filter { it.type == "Episode" && it.isAvailableForPlayback && !it.isDownload }
        .distinctBy { it.playbackId }
        .sortedWith(compareBy<Media> { it.json.optInt("seasonNumber", 1) }.thenBy { it.json.optInt("episodeNumber", 1) })
    fun index(id: String) = items.indexOfFirst { it.playbackId == id }
    fun next(id: String): Media? = index(id).takeIf { it >= 0 }?.let { items.getOrNull(it + 1) }
    fun previous(id: String): Media? = index(id).takeIf { it > 0 }?.let { items[it - 1] }
    fun upcoming(id: String): List<Media> = index(id).takeIf { it >= 0 }?.let { items.drop(it + 1) }.orEmpty()
}
