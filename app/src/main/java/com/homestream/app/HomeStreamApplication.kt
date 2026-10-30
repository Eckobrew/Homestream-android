package com.homestream.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HomeStreamApplication : Application() {
    // Bounded HTTP requests finish even when the player Activity is destroyed.
    val playbackReports = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
