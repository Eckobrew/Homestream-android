package com.homestream.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CastPoll(
    val sessionKey: String,
    val id: String,
    val position: Long,
    val paused: Boolean,
    val ready: Boolean,
)

internal data class CastProgressEvent<ApiT>(
    val api: ApiT,
    val id: String,
    val position: Long,
    val paused: Boolean,
    val started: Boolean = false,
    val stopped: Boolean = false,
)

/**
 * Pure state machine for Cast progress reporting.
 * Pins each receiver media session to the account/API that started it, preserves final
 * `stopped = true` reporting on idle/disconnect, and retains invalidation across transient
 * reconnects of the same invalidated media session.
 */
internal class CastProgressTracker<ApiT> {
    private data class Snapshot<ApiT>(
        val sessionKey: String,
        val id: String,
        val position: Long,
        val paused: Boolean,
        val api: ApiT,
    )

    private var last: Snapshot<ApiT>? = null
    private var reportedAt: Long = 0L
    private var pinnedSessionKey: String? = null
    private var pinnedAccount: String? = null
    private var pinnedApi: ApiT? = null
    private val invalidatedSessionKeys = LinkedHashSet<String>()

    fun step(
        rawCurrent: CastPoll?,
        liveAccount: String,
        nowMs: Long,
        createPinnedApi: () -> ApiT?,
    ): List<CastProgressEvent<ApiT>> {
        val events = mutableListOf<CastProgressEvent<ApiT>>()

        if (rawCurrent == null) {
            val prev = last
            if (prev != null) {
                events.add(CastProgressEvent(prev.api, prev.id, prev.position, prev.paused, stopped = true))
            }
            pinnedSessionKey = null
            pinnedAccount = null
            pinnedApi = null
            last = null
            return events
        }

        if (rawCurrent.sessionKey in invalidatedSessionKeys) {
            return events
        }

        if (rawCurrent.sessionKey != pinnedSessionKey) {
            pinnedSessionKey = rawCurrent.sessionKey
            pinnedAccount = liveAccount
            pinnedApi = createPinnedApi()
        }

        if (liveAccount != pinnedAccount) {
            val ownerApi = pinnedApi
            val prev = last
            if (ownerApi != null && prev != null && prev.sessionKey == pinnedSessionKey) {
                events.add(CastProgressEvent(ownerApi, prev.id, prev.position, prev.paused, stopped = true))
            }
            if (invalidatedSessionKeys.size >= 32) {
                invalidatedSessionKeys.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            }
            invalidatedSessionKeys.add(rawCurrent.sessionKey)
            pinnedSessionKey = null
            pinnedAccount = null
            pinnedApi = null
            last = null
            return events
        }

        if (!rawCurrent.ready) {
            return events
        }

        val activeApi = pinnedApi ?: return events
        val previous = last
        val changed = previous?.sessionKey != rawCurrent.sessionKey
        if (changed && previous != null) {
            events.add(CastProgressEvent(previous.api, previous.id, previous.position, previous.paused, stopped = true))
        }
        if (changed || nowMs - reportedAt >= 10_000L || previous?.paused != rawCurrent.paused) {
            events.add(
                CastProgressEvent(
                    activeApi,
                    rawCurrent.id,
                    rawCurrent.position,
                    rawCurrent.paused,
                    started = changed,
                )
            )
            reportedAt = nowMs
        }
        last = Snapshot(rawCurrent.sessionKey, rawCurrent.id, rawCurrent.position, rawCurrent.paused, activeApi)
        return events
    }
}

internal fun castMediaSessionKey(
    castSessionId: String?,
    itemId: String,
    mediaItemId: Int?,
    loadId: String = "",
): String = "${castSessionId ?: "cast"}|$itemId|${mediaItemId ?: ""}|$loadId"

/** Cast progress belongs to the session/profile that started it; identity switches stop reporting that media. */
internal fun HomeStreamApplication.observeCastProgress() {
    val cast = castContext(this) ?: return
    playbackReports.launch {
        val tracker = CastProgressTracker<HomeStreamApi>()

        while (isActive) {
            val poll = withContext(Dispatchers.Main) {
                val session = cast.sessionManager.currentCastSession
                val client = session?.remoteMediaClient
                val customData = client?.mediaInfo?.customData
                val id = customData?.optString("streamItemId").orEmpty().ifBlank { customData?.optString("itemId").orEmpty() }
                val settings = ServerSettings(this@observeCastProgress)
                val liveAccount = "${settings.baseUrl}|${settings.profile}|${settings.token}"
                if (session?.isConnected != true || !hasCastVideo(client) || id.isBlank() ||
                    client?.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE
                ) {
                    Triple<CastPoll?, String, ServerSettings?>(null, liveAccount, null)
                } else {
                    val sKey = castMediaSessionKey(
                        session.sessionId,
                        id,
                        client!!.mediaStatus?.currentItemId,
                        customData?.optString("loadId").orEmpty(),
                    )
                    Triple(
                        CastPoll(
                            sKey,
                            id,
                            client.approximateStreamPosition,
                            client.isPaused,
                            client.isPlaying || client.isPaused,
                        ),
                        liveAccount,
                        settings,
                    )
                }
            }

            val events = tracker.step(
                rawCurrent = poll.first,
                liveAccount = poll.second,
                nowMs = android.os.SystemClock.elapsedRealtime(),
                createPinnedApi = {
                    poll.third?.let { s ->
                        HomeStreamApi(
                            object : ConnectionSettings {
                                override val baseUrl = s.baseUrl
                                override val token = s.token
                                override var profile = s.profile
                            }
                        )
                    }
                },
            )

            for (event in events) {
                val recordedAt = System.currentTimeMillis()
                enqueuePlaybackReport {
                    runCatching {
                        event.api.progress(
                            event.id,
                            event.position,
                            event.paused,
                            started = event.started,
                            stopped = event.stopped,
                            recordedAtMs = recordedAt,
                        )
                    }.onSuccess {
                        if (event.stopped) playbackProgressSaved.tryEmit(Unit)
                    }.onFailure {
                        val label = if (event.stopped) "Cast stop progress" else "Cast progress"
                        android.util.Log.w("HomeStream", "Could not save $label")
                    }
                }
            }

            delay(1000)
        }
    }
}
