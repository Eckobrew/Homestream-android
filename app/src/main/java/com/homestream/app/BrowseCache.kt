package com.homestream.app

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Bounded metadata snapshots. Never caches account lists, permissions or negotiated media streams. */
internal object BrowseCache {
    private data class Entry(val json: String, val saved: Long, val seeded: Boolean)
    private val values = LinkedHashMap<String, Entry>(32, 0.75f, true)
    private var bytes = 0
    private const val MAX_MEMORY = 8 * 1024 * 1024
    private const val MAX_DISK = 16L * 1024 * 1024
    private const val MAX_AGE = 24 * 60 * 60 * 1000L
    @Volatile private var directory: File? = null
    private val diskLock = Any()
    private val busy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun hash(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    fun initialize(path: File) { directory = path; path.mkdirs() }
    fun supported(path: String) = path == "home" || path.startsWith("library/") || path.startsWith("media/") || path.startsWith("categories/")
    private fun lifetime(path: String) = when {
        path == "home" -> 30_000L
        path.startsWith("media/") -> 60_000L
        else -> 10 * 60_000L
    }
    @Synchronized fun fresh(key: String, path: String): Boolean = values[hash(key)]?.let {
        !it.seeded && System.currentTimeMillis() - it.saved < lifetime(path)
    } == true
    @Synchronized fun seeded(key: String) = values[hash(key)]?.seeded == true
    @Synchronized fun get(key: String): JSONObject? {
        val id = hash(key)
        val entry = values[id] ?: return null
        if (System.currentTimeMillis() - entry.saved > MAX_AGE) { values.remove(id); bytes -= entry.json.length * 2; return null }
        return runCatching { JSONObject(entry.json) }.getOrNull()
    }
    @Synchronized private fun memory(id: String, entry: Entry) {
        values.remove(id)?.let { bytes -= it.json.length * 2 }
        values[id] = entry; bytes += entry.json.length * 2
        while (bytes > MAX_MEMORY && values.isNotEmpty()) {
            val oldest = values.entries.iterator().next()
            bytes -= oldest.value.json.length * 2; values.remove(oldest.key)
        }
    }
    /** Caller uses Dispatchers.IO; disk reads never happen during view construction. */
    fun readDisk(key: String): JSONObject? = synchronized(diskLock) {
        val file = directory?.resolve(hash(key) + ".json") ?: return@synchronized null
        runCatching {
            if (!file.isFile || file.length() > 3L * 1024 * 1024) return@synchronized null
            val stored = JSONObject(file.readText())
            val saved = stored.getLong("saved")
            if (System.currentTimeMillis() - saved > MAX_AGE) { file.delete(); return@synchronized null }
            val json = stored.getString("json")
            if (json.length > 1024 * 1024) return@synchronized null
            memory(hash(key), Entry(json, saved, false)); file.setLastModified(System.currentTimeMillis())
            JSONObject(json)
        }.getOrNull()
    }
    fun put(key: String, value: JSONObject, seed: Boolean = false) {
        val text = value.toString()
        if (text.length > 1024 * 1024) return
        val id = hash(key)
        val saved = System.currentTimeMillis()
        memory(id, Entry(text, saved, seed))
        if (seed) return // Partial card metadata is memory-only and always refreshed.
        synchronized(diskLock) {
            val folder = directory ?: return@synchronized
            runCatching {
                folder.mkdirs()
                val file = folder.resolve("$id.json")
                val temp = folder.resolve("$id.tmp")
                temp.writeText(JSONObject().put("saved", saved).put("json", text).toString())
                if (!temp.renameTo(file)) temp.delete()
                prune(folder)
            }
        }
    }
    private fun prune(folder: File) {
        val files = folder.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var size = files.sumOf { it.length() }
        for (file in files) if (size > MAX_DISK || System.currentTimeMillis() - file.lastModified() > MAX_AGE) {
            val length = file.length(); if (file.delete()) size -= length
        }
    }
    fun clear() {
        synchronized(this) { values.clear(); bytes = 0 }
        synchronized(diskLock) { directory?.listFiles()?.forEach { it.delete() } }
    }
    fun start(key: String) = busy.add(hash(key))
    fun finish(key: String) { busy.remove(hash(key)) }
}
