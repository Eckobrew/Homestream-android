package com.homestream.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HomeStreamApi(private val settings: ConnectionSettings) {
    class ResponseException(val statusCode: Int, message: String) : IOException(message)
    companion object {
        private val prefetchSlots = kotlinx.coroutines.sync.Semaphore(2)
        private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS).followRedirects(false).build()
    }

    suspend fun musicDownloadFilename(url: String, token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).head().header("Authorization", "Bearer $token").build()
        awaitResponse(client.newCall(request)).use { response ->
            if (!response.isSuccessful) throw ResponseException(response.code, "Track download unavailable (${response.code})")
            val encoded = response.header("Content-Disposition")?.substringAfter("filename*=UTF-8''", "").orEmpty()
            android.net.Uri.decode(encoded).ifBlank { "Music" }
        }
    }

    private fun cacheKey(path: String, params: Map<String, String>): String {
        val identity = (settings as? ServerSettings)?.serverIdentity ?: settings.baseUrl
        val token = java.security.MessageDigest.getInstance("SHA-256").digest(settings.token.toByteArray()).joinToString("") { "%02x".format(it) }
        return "browse-v3|$identity|$token|${settings.profile}|${(settings as? ServerSettings)?.policyRevision.orEmpty()}|$path|${params.toSortedMap()}"
    }
    fun cached(path: String, params: Map<String, String> = emptyMap()): JSONObject? =
        if (BrowseCache.supported(path)) BrowseCache.get(cacheKey(path, params)) else null
    fun cacheSeeded(path: String, params: Map<String, String> = emptyMap()) = BrowseCache.seeded(cacheKey(path, params))
    fun cacheFresh(path: String, params: Map<String, String> = emptyMap()) = BrowseCache.fresh(cacheKey(path, params), path)
    suspend fun diskCached(path: String, params: Map<String, String> = emptyMap()): JSONObject? = withContext(Dispatchers.IO) {
        if (BrowseCache.supported(path)) BrowseCache.readDisk(cacheKey(path, params)) else null
    }
    fun seed(path: String, value: JSONObject) { if (BrowseCache.supported(path) && cached(path) == null) BrowseCache.put(cacheKey(path, emptyMap()), value, seed = true) }
    suspend fun prefetch(path: String) {
        if (!BrowseCache.supported(path) || cacheFresh(path)) return
        val key = cacheKey(path, emptyMap())
        if (!BrowseCache.start(key)) return
        try { prefetchSlots.withPermit { if (!cacheFresh(path)) call(path) } } finally { BrowseCache.finish(key) }
    }

    suspend fun call(path: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null, method: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val primaryBase = settings.baseUrl
        val profile = settings.profile
        val token = settings.token
        val cache = if (body == null && method == null && BrowseCache.supported(path)) cacheKey(path, params) else null
        fun remember(value: JSONObject): JSONObject {
            if (cache != null) BrowseCache.put(cache, value)
            if (body != null && path in setOf("watched", "profiles") || (body != null && path == "progress" && body.optBoolean("isStopped"))) BrowseCache.clear()
            return value
        }
        try {
            remember(executeCall(primaryBase, path, params, body, profile, token, method)).also {
                (settings as? ServerSettings)?.rememberSuccessfulUrl(primaryBase)
            }
        } catch (e: IOException) {
            if (e is ResponseException || body != null || method != null) throw e
            val serverSettings = settings as? ServerSettings
            val altBase = if (serverSettings != null && serverSettings.externalUrl.isNotBlank() && serverSettings.localUrl.isNotBlank()) {
                if (primaryBase == serverSettings.localUrl) serverSettings.externalUrl else serverSettings.localUrl
            } else null

            if (altBase != null && altBase != primaryBase) {
                try {
                    remember(executeCall(altBase, path, params, body, profile, token, method)).also { serverSettings?.rememberSuccessfulUrl(altBase) }
                } catch (_: Exception) {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    private suspend fun executeCall(baseUrl: String, path: String, params: Map<String, String>, body: JSONObject?, profile: String, token: String, method: String? = null): JSONObject {
        val url = (baseUrl.trimEnd('/') + "/api/mobile/v1/" + path).toHttpUrl().newBuilder()
        if (profile.isNotBlank()) url.addQueryParameter("userId", profile)
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        if (body != null) {
            val payload = JSONObject(body.toString()).put("userId", profile)
            // An optional PIN must be absent, rather than empty, when none is required.
            if ((path == "profiles" || path.startsWith("profiles/")) && payload.optString("parentPin").isBlank()) {
                payload.remove("parentPin")
            }
            if (method == "PUT") {
                request.put(payload.toString().toRequestBody("application/json".toMediaType()))
            } else {
                request.post(payload.toString().toRequestBody("application/json".toMediaType()))
            }
        } else if (method == "DELETE") {
            request.delete()
        }
        return awaitResponse(client.newCall(request.build())).use { response ->
            val text = response.body?.string().orEmpty()
            val data = runCatching { JSONObject(text) }.getOrNull()
            if (response.isSuccessful && data == null) throw ResponseException(response.code, "Server returned an invalid response (${response.code}). Check the HomeStream URL.")
            if (response.code == 401) BrowseCache.clear()
            if (!response.isSuccessful) throw ResponseException(response.code, when (response.code) {
                401 -> "Pairing token rejected. Check Settings."
                503 -> "Service unavailable. Check the mobile token and Jellyfin configuration."
                else -> data?.optString("error", "Server error (${response.code})") ?: "Server error (${response.code})"
            })
            checkNotNull(data)
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun awaitResponse(call: okhttp3.Call): okhttp3.Response = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }

    /** Never render a previous profile's cache until the server confirms its policy. */
    suspend fun refreshProfilePolicy(): Boolean {
        val local = settings as? ServerSettings ?: return false
        if (local.token.isBlank()) return false
        val before = "${local.profile}|${local.policyRevision}|${local.isAdmin}"
        val status = call("session/status")
        val canManage = if (status.has("canManageIntegrations")) status.optBoolean("canManageIntegrations") else null
        local.applyProfilePolicy(status.optJSONObject("profile"), status.optString("revision"), canManage)
        return before != "${local.profile}|${local.policyRevision}|${local.isAdmin}"
    }

    fun absolute(url: String): String {
        if (url.isBlank()) return ""
        val base = settings.baseUrl.toHttpUrl()
        val normalized = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("youtube/") || url.startsWith("music/") || url.startsWith("books/") -> "/api/mobile/v1/$url"
            else -> url
        }
        val target = base.resolve(normalized) ?: return ""
        val sameOrigin = target.scheme == base.scheme && target.host == base.host && target.port == base.port
        if (sameOrigin && (target.encodedPath.startsWith("/api/v1/books/") || target.encodedPath.startsWith("/api/mobile/v1/books/") || target.encodedPath.startsWith("/api/books/"))) {
            val newPath = when {
                target.encodedPath.startsWith("/api/v1/books/") -> target.encodedPath.replaceFirst("/api/v1/books/", "/api/mobile/v1/books/")
                target.encodedPath.startsWith("/api/books/") -> target.encodedPath.replaceFirst("/api/books/", "/api/mobile/v1/books/")
                else -> target.encodedPath
            }
            return target.newBuilder().encodedPath(newPath)
                .apply { if (settings.profile.isNotBlank()) setQueryParameter("userId", settings.profile) }.build().toString()
        }
        if (sameOrigin && (target.encodedPath.startsWith("/api/v1/music/") || target.encodedPath.startsWith("/api/mobile/v1/music/") || target.encodedPath.startsWith("/api/music/"))) {
            val newPath = when {
                target.encodedPath.startsWith("/api/v1/music/") -> target.encodedPath.replaceFirst("/api/v1/music/", "/api/mobile/v1/music/")
                target.encodedPath.startsWith("/api/music/") -> target.encodedPath.replaceFirst("/api/music/", "/api/mobile/v1/music/")
                else -> target.encodedPath
            }
            return target.newBuilder().encodedPath(newPath)
                .apply { if (settings.profile.isNotBlank()) setQueryParameter("userId", settings.profile) }.build().toString()
        }
        if (sameOrigin && (target.encodedPath.startsWith("/api/v1/youtube/") || target.encodedPath.startsWith("/api/youtube/") || target.encodedPath.startsWith("/api/mobile/v1/youtube/"))) {
            val newPath = when {
                target.encodedPath.startsWith("/api/v1/youtube/") -> target.encodedPath.replaceFirst("/api/v1/youtube/", "/api/mobile/v1/youtube/")
                target.encodedPath.startsWith("/api/youtube/") -> target.encodedPath.replaceFirst("/api/youtube/", "/api/mobile/v1/youtube/")
                else -> target.encodedPath
            }
            return target.newBuilder().encodedPath(newPath)
                .apply {
                    if (settings.profile.isNotBlank()) {
                        setQueryParameter("userId", settings.profile)
                        setQueryParameter("profileId", settings.profile)
                    }
                }.build().toString()
        }
        return if (target.scheme == base.scheme && target.host == base.host && target.port == base.port &&
            target.encodedPath.endsWith("/api/stream/download")) {
            target.newBuilder().encodedPath(target.encodedPath.removeSuffix("/api/stream/download") + "/api/mobile/v1/stream/download").build().toString()
        } else target.toString()
    }

    /** Network interceptors run again for redirects and every HLS/subtitle request. */
    fun mediaClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.header("User-Agent").isNullOrBlank()) {
                builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 HomeStream/1.2.0")
            }
            if (!isServerUrl(request.url.toString())) {
                builder.removeHeader("Authorization")
            } else if (settings.token.isNotBlank()) {
                builder.header("Authorization", "Bearer ${settings.token}")
            }
            chain.proceed(builder.build())
        }.build()
    fun isServerUrl(url: String): Boolean {
        val target = absolute(url).toHttpUrlOrNull() ?: return false
        val base = settings.baseUrl.toHttpUrlOrNull() ?: return false
        return target.scheme == base.scheme && target.host == base.host && target.port == base.port
    }
    suspend fun progress(id: String, positionMs: Long, paused: Boolean, stopped: Boolean = false, started: Boolean = false) = call("progress", body = JSONObject()
        .put("itemId", id).put("positionTicks", msToTicks(positionMs)).put("isPaused", paused).put("isStopped", stopped).put("isStarted", started))
}
