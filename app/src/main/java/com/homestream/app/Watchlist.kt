package com.homestream.app

import android.content.Context
import org.json.JSONArray
import java.security.MessageDigest

/** Device-local My List, separated by server and watch-history profile. */
class Watchlist(context: Context, private val settings: ConnectionSettings) {
    private val prefs = context.getSharedPreferences("watchlists", Context.MODE_PRIVATE)
    private fun key() = MessageDigest.getInstance("SHA-256")
        .digest("${settings.baseUrl}|${settings.profile}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun items(): List<Media> = runCatching {
        val array = JSONArray(prefs.getString(key(), "[]"))
        (0 until array.length()).map { Media(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    fun contains(item: Media) = items().any { it.id == item.id }
    fun toggle(item: Media): Boolean {
        val current = items().toMutableList()
        val added = current.none { it.id == item.id }
        current.removeAll { it.id == item.id }
        if (added) current.add(0, item)
        prefs.edit().putString(key(), JSONArray().apply { current.forEach { put(it.json) } }.toString()).apply()
        return added
    }
}
