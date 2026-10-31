package com.homestream.app

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
        ?: CastProfile("auto-probe", "Automatic receiver probe", true, 3840, 2160)
