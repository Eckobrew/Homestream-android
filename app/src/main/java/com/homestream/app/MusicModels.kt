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
