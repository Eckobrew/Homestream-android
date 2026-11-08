package com.homestream.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class HomeStreamApplication : Application() {
    // Bounded HTTP requests finish even when the player Activity is destroyed.
    val playbackReports = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val progressQueue = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(64)
    init {
        playbackReports.launch { for (report in progressQueue) runCatching { report() } }
    }
    fun enqueuePlaybackReport(report: suspend () -> Unit) {
        if (progressQueue.trySend(report).isFailure) android.util.Log.w("HomeStream", "Playback report queue is full")
    }

    override fun onCreate() {
        super.onCreate()
        BrowseCache.initialize(cacheDir.resolve("browse-metadata"))
        WatchNext.schedule(this)
        observeCastProgress()
        playbackReports.launch { cacheDir.resolve("image_cache").takeIf { it.isDirectory }?.deleteRecursively() }
        val settings = ServerSettings(this)
        val imageClient = HomeStreamApi(settings).mediaClient()

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(imageClient)
                .memoryCache(coil.memory.MemoryCache.Builder(this).maxSizeBytes(if (getSystemService(android.app.ActivityManager::class.java).isLowRamDevice) 16 * 1024 * 1024 else 32 * 1024 * 1024).build())
                .diskCache(coil.disk.DiskCache.Builder().directory(cacheDir.resolve("artwork"))
                    .maxSizeBytes(256L * 1024 * 1024).build())
                .crossfade(true)
                .build()
        )
    }
}
