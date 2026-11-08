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
    val requested get() = json.optBoolean("requested")
    val inLibrary get() = json.optBoolean("inLibrary")
}
data class MusicTrack(val json: JSONObject) {
    val id get() = json.optString("id")
    val title get() = json.optString("title")
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
