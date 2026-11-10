package com.homestream.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cast progress belongs to the session, independently of which screen is visible. */
internal fun HomeStreamApplication.observeCastProgress() {
    val cast = castContext(this) ?: return
    data class Snapshot(val id: String, val position: Long, val paused: Boolean,
                        val account: String, val api: HomeStreamApi, val ready: Boolean)
    playbackReports.launch {
        var last: Snapshot? = null
        var reportedAt = 0L
        while (isActive) {
            val current = withContext(Dispatchers.Main) {
                val session = cast.sessionManager.currentCastSession
                val client = session?.remoteMediaClient
                val id = client?.mediaInfo?.customData?.optString("itemId").orEmpty()
                if (session?.isConnected != true || !hasCastVideo(client) || id.isBlank() ||
                    client?.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE) null else {
                    val settings = ServerSettings(this@observeCastProgress)
                    val connection = object : ConnectionSettings {
                        override val baseUrl = settings.baseUrl
                        override val token = settings.token
                        override var profile = settings.profile
                    }
                    Snapshot(id, client!!.approximateStreamPosition, client.isPaused,
                        "${connection.baseUrl}|${connection.profile}|${connection.token}", HomeStreamApi(connection), client.isPlaying || client.isPaused)
                }
            }
            if (current != null && !current.ready) { delay(1000); continue }
            val previous = last
            val changed = previous?.id != current?.id || previous?.account != current?.account
            if (changed && previous != null) {
                val settings = ServerSettings(this@observeCastProgress)
                val account = "${settings.baseUrl}|${settings.profile}|${settings.token}"
                if (previous.account == account) enqueuePlaybackReport {
                    runCatching { previous.api.progress(previous.id, previous.position, previous.paused, stopped = true) }
                        .onFailure { android.util.Log.w("HomeStream", "Could not save Cast stop progress") }
                }
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (current != null && (changed || now - reportedAt >= 10000 || previous?.paused != current.paused)) {
                enqueuePlaybackReport {
                    runCatching { current.api.progress(current.id, current.position, current.paused, started = changed) }
                        .onFailure { android.util.Log.w("HomeStream", "Could not save Cast progress") }
                }
                reportedAt = now
            }
            last = current
            delay(1000)
        }
    }
}
