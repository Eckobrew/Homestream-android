package com.homestream.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** My List synced with the server per active profile, with local cache for fast/offline reads. */
class Watchlist(context: Context, private val settings: ConnectionSettings) {
    private val prefs = context.getSharedPreferences("watchlists", Context.MODE_PRIVATE)
    private fun key() = MessageDigest.getInstance("SHA-256")
        .digest("${(settings as? ServerSettings)?.serverIdentity ?: settings.baseUrl}|${settings.profile}".toByteArray()).joinToString("") { "%02x".format(it) }

    fun items(): List<Media> = runCatching {
        val array = JSONArray(prefs.getString(key(), "[]"))
        (0 until array.length()).map { Media(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun contains(item: Media) = items().any { it.id == item.id }

    fun saveAll(list: List<Media>) {
        prefs.edit().putString(key(), JSONArray().apply { list.forEach { put(it.json) } }.toString()).apply()
    }

    fun toggle(item: Media): Boolean {
        val current = items().toMutableList()
        val added = current.none { it.id == item.id }
        current.removeAll { it.id == item.id }
        if (added) current.add(0, item)
        saveAll(current)
        return added
    }

    suspend fun syncFromServer(api: HomeStreamApi): List<Media> = withContext(Dispatchers.IO) {
        runCatching {
            val res = api.call("watchlist")
            val arr = res.optJSONArray("items") ?: return@runCatching items()
            val list = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val mapped = JSONObject()
                    .put("id", obj.optString("itemId"))
                    .put("title", obj.optString("title"))
                    .put("type", obj.optString("type", "Movie"))
                    .put("posterUrl", obj.optString("posterUrl"))
                    .put("year", obj.optInt("year"))
                Media(mapped)
            }
            saveAll(list)
            list
        }.getOrDefault(items())
    }

    suspend fun syncToggle(item: Media, api: HomeStreamApi): Boolean = withContext(Dispatchers.IO) {
        val added = toggle(item)
        runCatching {
            if (added) {
                api.call(
                    "watchlist",
                    body = JSONObject()
                        .put("itemId", item.id)
                        .put("title", item.title)
                        .put("type", item.type)
                        .put("posterUrl", item.json.optString("posterUrl"))
                        .put("year", item.json.optInt("year"))
                )
            } else {
                api.call("watchlist", params = mapOf("itemId" to item.id), method = "DELETE")
            }
        }
        added
    }
}
