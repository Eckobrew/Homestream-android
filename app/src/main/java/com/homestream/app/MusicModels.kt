package com.homestream.app

import org.json.JSONObject
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class MusicAlbum(val json: JSONObject) {
    val id get() = json.optString("id")
    val title get() = json.optString("title")
    val artist get() = json.optString("artist")
    val year get() = json.optInt("year", 0)
    val coverUrl get() = json.optString("coverUrl")
    val trackCount get() = json.optInt("trackCount", 0)
    val requested get() = json.optBoolean("requested")
    val inLibrary get() = json.optBoolean("inLibrary")
    val isDownloaded get() = json.optBoolean("isDownloaded", inLibrary)
}
data class MusicTrack(val json: JSONObject) {
    val id get() = json.optString("id")
    val title get() = json.optString("title")
    val artist get() = json.optString("artist")
    val album get() = json.optString("album")
    val trackNumber get() = json.optInt("trackNumber", 1)
    val discNumber get() = json.optInt("discNumber", 1)
    val durationSeconds get() = json.optInt("durationSeconds", 0)
    val streamUrl get() = json.optString("streamUrl")
    val coverUrl get() = json.optString("coverUrl")
}
data class MusicPlaylist(val json: JSONObject) {
    val id get() = json.optString("id")
    val name get() = json.optString("name")
    val description get() = json.optString("description")
    val coverUrl get() = json.optString("coverUrl")
    val totalTracks get() = json.optInt("totalTracks", 0)
    val downloadedTracks get() = json.optInt("downloadedTracks", 0)
    val spotifyId get() = json.optString("spotifyId")
}
data class MusicPlaylistTrack(val json: JSONObject) {
    val id get() = json.optString("id")
    val playlistId get() = json.optString("playlistId")
    val trackId get() = json.optString("trackId")
    val title get() = json.optString("title")
    val artist get() = json.optString("artist")
    val album get() = json.optString("album")
    val durationSeconds get() = json.optInt("durationSeconds", 0)
    val coverUrl get() = json.optString("coverUrl")
    val status get() = json.optString("status", "pending")
    val errorMessage get() = json.optString("errorMessage")
    val streamUrl get() = json.optString("streamUrl")
    val position get() = json.optInt("position", 0)

    val isReady get() = status == "completed" && streamUrl.isNotBlank()
}
fun releaseLabel(value: String?): String = runCatching {
    when {
        value == null -> "Unknown"
        value.matches(Regex("\\d{4}")) -> Year.parse(value).toString()
        value.matches(Regex("\\d{4}-\\d{2}")) -> YearMonth.parse(value).format(DateTimeFormatter.ofPattern("MMMM uuuu"))
        value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        else -> "Unknown"
    }
}.getOrDefault("Unknown")
fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { a ->
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}.orEmpty()
