package com.homestream.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HomeStreamApi(private val settings: ConnectionSettings) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()
    suspend fun call(path: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val url = (settings.baseUrl + "/api/mobile/v1/" + path).toHttpUrl().newBuilder()
        if (settings.profile.isNotBlank()) url.addQueryParameter("userId", settings.profile)
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("Authorization", "Bearer ${settings.token}")
            .header("Accept", "application/json")
        if (body != null) {
            val payload = JSONObject(body.toString()).put("userId", settings.profile)
            request.post(payload.toString().toRequestBody("application/json".toMediaType()))
        }
        client.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val data = runCatching { JSONObject(text) }.getOrElse { throw IOException("Server returned an invalid response (${response.code}). Check the HomeStream URL.") }
            if (!response.isSuccessful) throw IOException(when (response.code) {
                401 -> "Pairing token rejected. Check Settings."
                503 -> "Service unavailable. Check the mobile token and Jellyfin configuration."
                else -> data.optString("error", "Server error (${response.code})")
            })
            data
        }
    }
    fun absolute(url: String): String = settings.baseUrl.toHttpUrl().resolve(url)?.toString().orEmpty()
    suspend fun progress(id: String, positionMs: Long, paused: Boolean, stopped: Boolean = false) = call("progress", body = JSONObject()
        .put("itemId", id).put("positionTicks", msToTicks(positionMs)).put("isPaused", paused).put("isStopped", stopped))
}
