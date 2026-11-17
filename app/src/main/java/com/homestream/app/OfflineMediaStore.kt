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
    ): OfflineDownloadEntry = withContext(Dispatchers.IO) {
        val params = mutableMapOf("itemId" to itemId, "receiver" to "android")
        if (!versionId.isNullOrBlank()) params["versionId"] = versionId
        when (quality) {
            "1080p" -> { params["maxWidth"] = "1920"; params["maxHeight"] = "1080" }
            "720p" -> { params["maxWidth"] = "1280"; params["maxHeight"] = "720" }
        }
        val pb = api.call("playback", params = params)
        val streamUrl = pb.optString("streamUrl")
        val contentType = pb.optString("contentType")
        require(streamUrl.isNotBlank()) { "No playable stream available for offline download" }
        require(
            !pb.optBoolean("isTranscoding") &&
                !streamUrl.contains(".m3u8", ignoreCase = true) &&
                !contentType.contains("mpegurl", ignoreCase = true)
        ) { "HLS/transcoded streams are not supported for direct offline download" }

        val resolvedVersion = pb.optString("selectedVersionId").takeIf { it.isNotBlank() } ?: versionId
        val safeBase = "${itemId}_${resolvedVersion ?: "default"}".replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val target = File(offlineDir, "$safeBase.mp4")
        val tempFile = File(offlineDir, "$safeBase.mp4.tmp")
        try {
            val req = Request.Builder().url(api.absolute(streamUrl)).build()
            api.mediaClient().newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "Download failed (${resp.code})" }
                val respType = resp.header("Content-Type").orEmpty()
                require(!respType.contains("mpegurl", ignoreCase = true)) { "Unexpected manifest response" }
                val body = resp.body ?: error("Empty download response")
                tempFile.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            require(tempFile.exists() && tempFile.length() > 0L) { "Downloaded file was empty" }
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

    fun queueOfflineProgress(itemId: String, positionMs: Long) {
        if (positionMs <= 0L) return
        val current = JSONObject(prefs.getString(progressKey, "{}") ?: "{}")
        current.put(itemId, JSONObject().put("positionMs", positionMs).put("recordedAt", System.currentTimeMillis()))
        prefs.edit().putString(progressKey, current.toString()).apply()
    }

    suspend fun syncPendingProgress(api: HomeStreamApi): Int = withContext(Dispatchers.IO) {
        val current = JSONObject(prefs.getString(progressKey, "{}") ?: "{}")
        val keys = current.keys().asSequence().toList()
        var synced = 0
        for (itemId in keys) {
            val entry = current.optJSONObject(itemId)
            val posMs = entry?.optLong("positionMs", -1L) ?: -1L
            if (posMs <= 0L) {
                current.remove(itemId)
                continue
            }
            val ok = runCatching {
                api.progress(itemId, posMs, paused = true, stopped = true)
            }.isSuccess
            if (ok) {
                current.remove(itemId)
                synced++
            }
        }
        prefs.edit().putString(progressKey, current.toString()).apply()
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
