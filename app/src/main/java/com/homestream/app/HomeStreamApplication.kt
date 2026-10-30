package com.homestream.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class HomeStreamApplication : Application() {
    // Bounded HTTP requests finish even when the player Activity is destroyed.
    val playbackReports = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val settings = ServerSettings(this)
        val imageClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val serverHost = settings.baseUrl.toHttpUrlOrNull()?.host
                val builder = request.newBuilder()
                if (serverHost != null && request.url.host.equals(serverHost, ignoreCase = true) && settings.token.isNotBlank()) {
                    builder.header("Authorization", "Bearer ${settings.token}")
                }
                chain.proceed(builder.build())
            }
            .build()

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(imageClient)
                .crossfade(true)
                .build()
        )
    }
}
