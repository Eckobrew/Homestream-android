package com.homestream.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class OfflineDownloadEntry(
    val itemId: String,
    val versionId: String?,
    val title: String,
    val localPath: String,
    val quality: String,
    val downloadedAt: Long,
)

/** Manages offline direct-file downloads scoped by server + active profile + item/version. */
class OfflineMediaStore(private val context: Context, private val settings: ConnectionSettings) {
    private val prefs = context.getSharedPreferences("offline_media", Context.MODE_PRIVATE)
    private val scopeHash: String by lazy {
        val server = (settings as? ServerSettings)?.serverIdentity ?: settings.baseUrl
        MessageDigest.getInstance("SHA-256")
            .digest("$server|${settings.profile}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }
    private val offlineDir: File by lazy { File(context.filesDir, "offline/$scopeHash").apply { mkdirs() } }
    private val indexKey = "entries_$scopeHash"
    private val progressKey = "pending_progress_$scopeHash"

    fun listDownloads(): List<OfflineDownloadEntry> = runCatching {
        val arr = JSONArray(prefs.getString(indexKey, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("localPath")
            if (path.isBlank() || !File(path).exists()) return@mapNotNull null
            OfflineDownloadEntry(
                itemId = o.optString("itemId"),
                versionId = o.optString("versionId").takeIf { it.isNotBlank() },
                title = o.optString("title"),
                localPath = path,
                quality = o.optString("quality", "original"),
                downloadedAt = o.optLong("downloadedAt"),
            )
        }
    }.getOrDefault(emptyList())

    fun localFileFor(itemId: String, versionId: String? = null): File? =
        listDownloads()
            .firstOrNull { it.itemId == itemId && (versionId == null || it.versionId == versionId) }
            ?.let { File(it.localPath) }
            ?.takeIf { it.exists() && it.length() > 0L }

    suspend fun downloadDirectItem(
        itemId: String,
        title: String,
        quality: String,
        api: HomeStreamApi,
        versionId: String? = null,
        onStatus: ((String) -> Unit)? = null,
    ): OfflineDownloadEntry = withContext(Dispatchers.IO) {
        val params = mutableMapOf("itemId" to itemId, "receiver" to "android")
        if (!versionId.isNullOrBlank()) params["versionId"] = versionId
        val pb = api.call("playback", params = params)
        val resolvedVersion = pb.optString("selectedVersionId").takeIf { it.isNotBlank() } ?: versionId
        val rawStreamUrl = pb.optString("streamUrl")
        val contentType = pb.optString("contentType")

        val downloadPath = if (itemId.startsWith("nat-") && !resolvedVersion.isNullOrBlank()) {
            val q = mutableListOf("fileId=${java.net.URLEncoder.encode(resolvedVersion, "UTF-8")}", "download=1")
            when (quality) {
                "1080p" -> q.add("maxHeight=1080")
                "720p" -> q.add("maxHeight=720")
            }
            val baseDl = "/api/mobile/v1/native/stream?${q.joinToString("&")}"
            val prepParams = mutableMapOf("fileId" to resolvedVersion, "download" to "1", "prepare" to "1")
            when (quality) {
                "1080p" -> prepParams["maxHeight"] = "1080"
                "720p" -> prepParams["maxHeight"] = "720"
            }
            onStatus?.invoke("Preparing $quality MP4…")
            var ready = false
            for (attempt in 0 until 300) {
                val prep = api.call("native/stream", params = prepParams)
                when (prep.optString("status")) {
                    "ready" -> { ready = true; break }
                    "failed" -> error(prep.optString("error", "Offline conversion failed"))
                    else -> kotlinx.coroutines.delay(2_000)
                }
            }
            require(ready) { "Timed out waiting for offline $quality MP4 export" }
            onStatus?.invoke("Downloading $quality MP4…")
            baseDl
        } else {
            require(rawStreamUrl.isNotBlank()) { "No playable stream available for offline download" }
            require(
                !pb.optBoolean("isTranscoding") &&
                    !rawStreamUrl.contains(".m3u8", ignoreCase = true) &&
                    !contentType.contains("mpegurl", ignoreCase = true)
            ) { "HLS/transcoded streams are only supported for native MP4 offline downloads" }
            rawStreamUrl
        }

        val safeBase = "${itemId}_${resolvedVersion ?: "default"}".replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val target = File(offlineDir, "$safeBase.mp4")
        val tempFile = File(offlineDir, "$safeBase.mp4.tmp")
        try {
            val req = Request.Builder().url(api.absolute(downloadPath)).build()
            api.mediaClient().newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "Download failed (${resp.code})" }
                val respType = resp.header("Content-Type").orEmpty()
                require(!respType.contains("mpegurl", ignoreCase = true)) { "Unexpected manifest response" }
                val body = resp.body ?: error("Empty download response")
                tempFile.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            require(tempFile.exists() && tempFile.length() > 0L) { "Downloaded file was empty" }
            val headBytes = tempFile.inputStream().use { inp -> ByteArray(16).also { buf -> inp.read(buf, 0, 16) } }
            require(!headBytes.decodeToString().startsWith("#EXTM3U")) { "Downloaded file was an HLS playlist instead of media" }
            if (target.exists()) target.delete()
            check(tempFile.renameTo(target)) { "Failed to finalize offline download" }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }

        val entry = OfflineDownloadEntry(itemId, resolvedVersion, title, target.absolutePath, quality, System.currentTimeMillis())
        val updated = listDownloads().filterNot { it.itemId == itemId && it.versionId == resolvedVersion } + entry
        saveEntries(updated)
        entry
    }

    fun removeDownload(itemId: String, versionId: String? = null) {
        localFileFor(itemId, versionId)?.delete()
        saveEntries(listDownloads().filterNot { it.itemId == itemId && (versionId == null || it.versionId == versionId) })
    }

    companion object {
        private val progressLock = Any()
        private val generationCounter = java.util.concurrent.atomic.AtomicLong(0L)
    }

    fun queueOfflineProgress(itemId: String, positionMs: Long) {
        if (positionMs <= 0L) return
        synchronized(progressLock) {
            val current = JSONObject(prefs.getString(progressKey, "{}") ?: "{}")
            current.put(
                itemId,
                JSONObject()
                    .put("positionMs", positionMs)
                    .put("recordedAt", System.currentTimeMillis())
                    .put("gen", generationCounter.incrementAndGet())
            )
            prefs.edit().putString(progressKey, current.toString()).apply()
        }
    }

    suspend fun syncPendingProgress(api: HomeStreamApi): Int = withContext(Dispatchers.IO) {
        data class QueuedProgress(val itemId: String, val posMs: Long, val recordedAt: Long, val gen: Long)
        val snapshot = synchronized(progressLock) {
            val current = JSONObject(prefs.getString(progressKey, "{}") ?: "{}")
            current.keys().asSequence().mapNotNull { itemId ->
                val entry = current.optJSONObject(itemId) ?: return@mapNotNull null
                val posMs = entry.optLong("positionMs", -1L)
                val recordedAt = entry.optLong("recordedAt", 0L)
                val gen = entry.optLong("gen", 0L)
                if (posMs <= 0L) null else QueuedProgress(itemId, posMs, recordedAt, gen)
            }.toList()
        }
        var synced = 0
        for (q in snapshot) {
            val ok = runCatching {
                api.progress(
                    q.itemId,
                    q.posMs,
                    paused = true,
                    stopped = true,
                    recordedAtMs = q.recordedAt.takeIf { it > 0L },
                )
            }.isSuccess
            if (ok) {
                synchronized(progressLock) {
                    val latest = JSONObject(prefs.getString(progressKey, "{}") ?: "{}")
                    val latestObj = latest.optJSONObject(q.itemId)
                    val latestGen = latestObj?.optLong("gen", 0L) ?: 0L
                    val latestRec = latestObj?.optLong("recordedAt", 0L) ?: 0L
                    if (latestObj != null && latestGen == q.gen && latestRec == q.recordedAt) {
                        latest.remove(q.itemId)
                        prefs.edit().putString(progressKey, latest.toString()).apply()
                    }
                }
                synced++
            }
        }
        synced
    }

    private fun saveEntries(list: List<OfflineDownloadEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("itemId", e.itemId)
                    .put("versionId", e.versionId ?: "")
                    .put("title", e.title)
                    .put("localPath", e.localPath)
                    .put("quality", e.quality)
                    .put("downloadedAt", e.downloadedAt)
            )
        }
        prefs.edit().putString(indexKey, arr.toString()).apply()
    }
}
