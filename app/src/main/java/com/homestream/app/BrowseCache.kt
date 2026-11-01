package com.homestream.app

import android.util.LruCache
import org.json.JSONObject

/** Metadata only. Authentication, playback URLs and stream negotiations are never cached here. */
internal object BrowseCache {
    private data class Entry(val json: String, val saved: Long)
    private val values = object : LruCache<String, Entry>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Entry) = value.json.length * 2
    }
    private val busy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun supported(path: String) = path == "home" || path.startsWith("library/") || path.startsWith("media/") || path.startsWith("categories/")
    fun get(key: String): JSONObject? {
        val entry = values.get(key) ?: return null
        if (System.currentTimeMillis() - entry.saved > 10 * 60 * 1000L) { values.remove(key); return null }
        return runCatching { JSONObject(entry.json) }.getOrNull()
    }
    fun put(key: String, value: JSONObject) {
        val text = value.toString()
        if (text.length <= 1024 * 1024) values.put(key, Entry(text, System.currentTimeMillis()))
    }
    fun clear() { values.evictAll() }
    fun start(key: String) = busy.add(key)
    fun finish(key: String) { busy.remove(key) }
}
