package com.homestream.app

import kotlin.coroutines.resume

data class CastProfile(val key: String, val label: String, val hevc: Boolean, val width: Int, val height: Int)
val castProfiles = listOf(
    CastProfile("google-tv-hd", "Chromecast with Google TV (HD)", true, 1920, 1080),
    CastProfile("google-tv-4k", "Google TV / Chromecast Ultra (4K)", true, 3840, 2160),
    CastProfile("legacy", "Older Chromecast (1080p)", false, 1920, 1080)
)
fun detectedCastProfile(model: String): CastProfile? {
    val name = model.lowercase(java.util.Locale.ROOT)
    return when {
        "chromecast hd" in name || ("google tv" in name && "hd" in name && "4k" !in name) -> castProfiles[0]
        ("google tv" in name && ("4k" in name || "streamer" in name)) || "chromecast ultra" in name -> castProfiles[1]
        else -> null
    }
}

fun automaticCastProfile(model: String, compatible: Boolean): CastProfile =
    if (compatible) castProfiles[2] else detectedCastProfile(model)
        ?: CastProfile("auto-probe", "Automatic receiver probe", false, 1920, 1080)

internal const val CAST_NAMESPACE = "urn:x-cast:uk.eckonet.homestream"

internal suspend fun requestCastVideoCapabilities(
    session: com.google.android.gms.cast.framework.CastSession
): String? = kotlinx.coroutines.withTimeoutOrNull(1200) {
    try {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val requestId = java.util.UUID.randomUUID().toString()
            val callback = com.google.android.gms.cast.Cast.MessageReceivedCallback { _, _, message ->
                val response = runCatching { org.json.JSONObject(message) }.getOrNull()
                if (response?.optString("type") == "capabilities" && response.optString("requestId") == requestId &&
                    continuation.isActive) {
                    continuation.resume(response.optJSONArray("video")?.takeIf { it.length() in 1..5 }?.toString())
                }
            }
            try {
                session.setMessageReceivedCallbacks(CAST_NAMESPACE, callback)
                session.sendMessage(CAST_NAMESPACE, org.json.JSONObject().put("type", "capabilities-request")
                    .put("requestId", requestId).toString())
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    } finally {
        runCatching { session.removeMessageReceivedCallbacks(CAST_NAMESPACE) }
    }
}
