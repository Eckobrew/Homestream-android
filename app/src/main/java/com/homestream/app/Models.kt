package com.homestream.app

import org.json.JSONObject

/** HomeStream uses 100 ns ticks; retain Long precision for long-running media. */
fun ticksToMs(ticks: Long) = ticks / 10_000L
fun msToTicks(ms: Long) = ms.coerceIn(0, Long.MAX_VALUE / 10_000L) * 10_000L

data class Media(val json: JSONObject) {
    val id: String get() = json.optString("id")
    val title: String get() = json.optString("title", json.optString("name"))
    val type: String get() = json.optString("type", "Episode")
    val overview: String get() = json.optString("overview")
    val poster: String get() = json.optString("posterUrl", json.optString("thumbnailUrl"))
    val backdrop: String get() = json.optString("backdropUrl", poster)
    val playbackId: String get() = json.optString("jellyfinId").ifBlank { id }
    val downloadUrl: String get() = json.optString("downloadStreamUrl").ifBlank { json.optString("videoStreamUrl") }
    val isDownload: Boolean get() = json.optBoolean("isDownloading") && downloadUrl.isNotBlank()
    val resumeMs: Long get() = ticksToMs(json.optLong("playbackPositionTicks"))
    val tmdbId: Int get() = json.optInt("tmdbId")
    val trailerKey: String get() = json.optString("trailerKey")
    val playableEpisodeId: String get() = json.optString("playableEpisodeId")
    val playable: Boolean get() = json.optString("source") != "demo" &&
        (isDownload || json.optString("status") == "available" || json.optString("status") == "partially_available" ||
            json.optString("jellyfinId").isNotBlank() || (type == "Episode" && !id.startsWith("seerr-")))
    val metadata: String get() = listOf(json.optString("year").takeUnless { it == "0" }.orEmpty(),
        json.optString("rating"), json.optString("resolution"),
        json.optInt("durationMinutes").takeIf { it > 0 }?.let { "$it min" }.orEmpty()).filter { it.isNotBlank() }.joinToString("  •  ")
}

fun JSONObject.mediaList(key: String): List<Media> = optJSONArray(key)?.let { array ->
    (0 until array.length()).map { Media(array.getJSONObject(it)) }
}.orEmpty()
