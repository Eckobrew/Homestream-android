package com.homestream.app

fun videoCodecsForMimeTypes(types: List<String>): String {
    val names = mapOf("video/avc" to "h264", "video/hevc" to "hevc", "video/x-vnd.on2.vp8" to "vp8", "video/x-vnd.on2.vp9" to "vp9", "video/av01" to "av1")
    return types.mapNotNull { names[it.lowercase(java.util.Locale.ROOT)] }.distinct().joinToString(",").ifBlank { "h264" }
}

/** TV capability bounds come from one decoder per codec, rather than MIME support alone. */
fun televisionVideoProfiles(): org.json.JSONArray {
    val result = org.json.JSONArray()
    val mimeNames = mapOf("video/avc" to "h264", "video/hevc" to "hevc", "video/x-vnd.on2.vp8" to "vp8", "video/x-vnd.on2.vp9" to "vp9", "video/av01" to "av1")
    val decoders = runCatching { android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
    for ((mime, codec) in mimeNames) {
        val candidates = decoders.filter { mime in it.supportedTypes }.mapNotNull { decoder ->
            runCatching {
                val caps = decoder.getCapabilitiesForType(mime)
                val dimensions = listOf(3840 to 2160, 1920 to 1080, 1280 to 720, 720 to 480)
                    .firstOrNull { caps.videoCapabilities?.isSizeSupported(it.first, it.second) == true } ?: return@runCatching null
                val profiles = caps.profileLevels.map { it.profile }.toSet()
                val names = when (codec) {
                    "h264" -> listOf(1 to "baseline", 2 to "main", 4 to "extended", 8 to "high", 16 to "high 10")
                    "hevc" -> listOf(1 to "main", 2 to "main 10")
                    else -> emptyList()
                }.filter { it.first in profiles }.map { it.second }.toMutableList()
                if ("baseline" in names) names.add("constrained baseline")
                val software = decoder.name.startsWith("OMX.google.") || decoder.name.startsWith("c2.android.")
                Triple(dimensions, names, software)
            }.getOrNull()
        }
        val best = candidates.filter { !it.third }.ifEmpty { candidates }
            .maxByOrNull { it.first.first * it.first.second } ?: continue
        // Unknown H.264/HEVC profiles must not be treated as unrestricted.
        if (codec in listOf("h264", "hevc") && best.second.isEmpty()) continue
        result.put(org.json.JSONObject().put("codec", codec).put("width", best.first.first).put("height", best.first.second)
            .put("profiles", org.json.JSONArray(best.second)))
    }
    if ((0 until result.length()).none { result.getJSONObject(it).optString("codec") == "h264" }) result.put(org.json.JSONObject().put("codec", "h264").put("width", 1280).put("height", 720)
        .put("profiles", org.json.JSONArray(listOf("baseline"))))
    return result
}
