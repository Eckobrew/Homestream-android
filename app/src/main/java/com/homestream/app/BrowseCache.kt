package com.homestream.app

import org.json.JSONObject

/** Metadata only. Authentication, playback URLs and stream negotiations are never cached here. */
internal object BrowseCache {
    private data class Entry(val json: String, val saved: Long)
    private val values = LinkedHashMap<String, Entry>(32, 0.75f, true)
    private var bytes = 0
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val busy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun supported(path: String) = path == "home" || path == "profiles" || path == "profiles/users" || path.startsWith("library/") || path.startsWith("media/") || path.startsWith("categories/")
    @Synchronized fun get(key: String): JSONObject? {
        val entry = values[key] ?: return null
        if (System.currentTimeMillis() - entry.saved > 10 * 60 * 1000L) { values.remove(key); bytes -= entry.json.length * 2; return null }
        return runCatching { JSONObject(entry.json) }.getOrNull()
    }
    @Synchronized fun put(key: String, value: JSONObject) {
        val text = value.toString()
        if (text.length > 1024 * 1024) return
        values.remove(key)?.let { bytes -= it.json.length * 2 }
        values[key] = Entry(text, System.currentTimeMillis()); bytes += text.length * 2
        while (bytes > MAX_BYTES && values.isNotEmpty()) {
            val oldest = values.entries.iterator().next()
            bytes -= oldest.value.json.length * 2; values.remove(oldest.key)
        }
    }
    @Synchronized fun clear() { values.clear(); bytes = 0 }
    fun start(key: String) = busy.add(key)
    fun finish(key: String) { busy.remove(key) }
}
