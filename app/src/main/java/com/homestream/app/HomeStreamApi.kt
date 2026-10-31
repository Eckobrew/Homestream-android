package com.homestream.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private class ResponseException(message: String) : IOException(message)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun call(path: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val primaryBase = settings.baseUrl
        try {
            executeCall(primaryBase, path, params, body).also {
                (settings as? ServerSettings)?.rememberSuccessfulUrl(primaryBase)
            }
        } catch (e: IOException) {
            if (e is ResponseException) throw e
            val serverSettings = settings as? ServerSettings
            val altBase = if (serverSettings != null && serverSettings.externalUrl.isNotBlank() && serverSettings.localUrl.isNotBlank()) {
                if (primaryBase == serverSettings.localUrl) serverSettings.externalUrl else serverSettings.localUrl
            } else null

            if (altBase != null && altBase != primaryBase) {
                try {
                    executeCall(altBase, path, params, body).also { serverSettings?.rememberSuccessfulUrl(altBase) }
                } catch (_: Exception) {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    private fun executeCall(baseUrl: String, path: String, params: Map<String, String>, body: JSONObject?): JSONObject {
        val url = (baseUrl.trimEnd('/') + "/api/mobile/v1/" + path).toHttpUrl().newBuilder()
        if (settings.profile.isNotBlank()) url.addQueryParameter("userId", settings.profile)
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("Authorization", "Bearer ${settings.token}")
            .header("Accept", "application/json")
        if (body != null) {
            val payload = JSONObject(body.toString()).put("userId", settings.profile)
            request.post(payload.toString().toRequestBody("application/json".toMediaType()))
        }
        return client.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val data = runCatching { JSONObject(text) }.getOrNull()
            if (response.isSuccessful && data == null) throw ResponseException("Server returned an invalid response (${response.code}). Check the HomeStream URL.")
            if (!response.isSuccessful) throw ResponseException(when (response.code) {
                401 -> "Pairing token rejected. Check Settings."
                503 -> "Service unavailable. Check the mobile token and Jellyfin configuration."
                else -> data?.optString("error", "Server error (${response.code})") ?: "Server error (${response.code})"
            })
            checkNotNull(data)
        }
    }
    fun absolute(url: String): String {
        if (url.isBlank()) return ""
        val base = settings.baseUrl.toHttpUrl()
        val target = base.resolve(url) ?: return ""
        return if (target.scheme == base.scheme && target.host == base.host && target.port == base.port &&
            target.encodedPath.endsWith("/api/stream/download")) {
            target.newBuilder().encodedPath(target.encodedPath.removeSuffix("/api/stream/download") + "/api/mobile/v1/stream/download").build().toString()
        } else target.toString()
    }

    /** Network interceptors run again for redirects and every HLS/subtitle request. */
    fun mediaClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder().removeHeader("Authorization")
            if (isServerUrl(request.url.toString()) && settings.token.isNotBlank()) {
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
