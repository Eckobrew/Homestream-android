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

    override fun onCreate() {
        super.onCreate()
        BrowseCache.initialize(cacheDir.resolve("browse-metadata"))
        WatchNext.schedule(this)
        playbackReports.launch { cacheDir.resolve("image_cache").takeIf { it.isDirectory }?.deleteRecursively() }
        val settings = ServerSettings(this)
        val imageClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val server = settings.baseUrl.toHttpUrlOrNull()
                val builder = request.newBuilder()
                if (server != null && request.url.scheme == server.scheme && request.url.host == server.host && request.url.port == server.port && settings.token.isNotBlank()) {
                    builder.header("Authorization", "Bearer ${settings.token}")
                }
                chain.proceed(builder.build())
            }
            .build()

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
