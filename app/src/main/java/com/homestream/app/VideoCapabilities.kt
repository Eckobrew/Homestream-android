package com.homestream.app

fun videoCodecsForMimeTypes(types: List<String>): String {
    val names = mapOf("video/avc" to "h264", "video/hevc" to "hevc", "video/x-vnd.on2.vp8" to "vp8", "video/x-vnd.on2.vp9" to "vp9", "video/av01" to "av1")
    return types.mapNotNull { names[it.lowercase(java.util.Locale.ROOT)] }.distinct().joinToString(",").ifBlank { "h264" }
}
