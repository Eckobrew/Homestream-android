package com.homestream.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.TimeUnit

data class NetworkSession(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val profileId: String,
    val mediaId: String,
    val mediaType: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val canSeek: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrev: Boolean,
    val updatedAt: Long,
) {
    val isTv: Boolean get() = deviceType == "android-tv"
    val isIdle: Boolean get() = mediaType == "idle" || title.isBlank()

    companion object {
        fun fromJson(json: JSONObject): NetworkSession = NetworkSession(
            deviceId = json.optString("deviceId"),
            deviceName = json.optString("deviceName"),
            deviceType = json.optString("deviceType"),
            profileId = json.optString("profileId"),
            mediaId = json.optString("mediaId"),
            mediaType = json.optString("mediaType"),
            title = json.optString("title"),
            subtitle = json.optString("subtitle"),
            artworkUrl = json.optString("artworkUrl"),
            isPlaying = json.optBoolean("isPlaying"),
            positionMs = json.optLong("positionMs", 0L),
            durationMs = json.optLong("durationMs", 0L),
            canSeek = json.optBoolean("canSeek"),
            canSkipNext = json.optBoolean("canSkipNext"),
            canSkipPrev = json.optBoolean("canSkipPrev"),
            updatedAt = json.optLong("updatedAt", 0L),
        )
    }
}

data class SessionsSnapshot(
    val localNetwork: Boolean,
    val sessions: List<NetworkSession>,
    val tvDevices: List<NetworkSession>,
)

object PlaybackSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var foregroundActivityRef = WeakReference<Activity>(null)
    private var videoCommandHandler: ((type: String, positionMs: Long?, deltaMs: Long?) -> Boolean)? = null
    private var tvPresenceJob: Job? = null
    private var tvSseJob: Job? = null

    fun setForegroundActivity(activity: Activity?) {
        foregroundActivityRef = WeakReference(activity)
    }

    fun registerVideoHandler(handler: (type: String, positionMs: Long?, deltaMs: Long?) -> Boolean) {
        videoCommandHandler = handler
    }

    fun clearVideoHandler(handler: (type: String, positionMs: Long?, deltaMs: Long?) -> Boolean) {
        if (videoCommandHandler === handler) {
            videoCommandHandler = null
        }
    }

    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("server", Context.MODE_PRIVATE)
        val existing = prefs.getString("sessionDeviceId", "").orEmpty()
        if (existing.isNotBlank()) return existing
        val prefix = if (context.isTelevision()) "tv" else "mob"
        val created = "$prefix-${UUID.randomUUID()}"
        prefs.edit().putString("sessionDeviceId", created).apply()
        return created
    }

    fun deviceName(context: Context): String {
        val model = Build.MODEL.orEmpty().trim()
        return if (model.isNotBlank()) model else if (context.isTelevision()) "Android TV" else "Android"
    }

    suspend fun reportHeartbeat(
        context: Context,
        mediaId: String = "",
        mediaType: String = "idle",
        title: String = "",
        subtitle: String = "",
        artworkUrl: String = "",
        isPlaying: Boolean = false,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        canSeek: Boolean = false,
        canSkipNext: Boolean = false,
        canSkipPrev: Boolean = false,
        isStopped: Boolean = false,
    ) {
        val settings = ServerSettings(context.applicationContext)
        if (settings.baseUrl.isBlank() || settings.token.isBlank()) return
        val body = JSONObject()
            .put("deviceId", deviceId(context))
            .put("deviceName", deviceName(context))
            .put("deviceType", if (context.isTelevision()) "android-tv" else "android-mobile")
            .put("mediaId", mediaId)
            .put("mediaType", mediaType)
            .put("title", title)
            .put("subtitle", subtitle)
            .put("artworkUrl", artworkUrl)
            .put("isPlaying", isPlaying)
            .put("positionMs", positionMs.coerceAtLeast(0L))
            .put("durationMs", durationMs.coerceAtLeast(0L))
            .put("canSeek", canSeek)
            .put("canSkipNext", canSkipNext)
            .put("canSkipPrev", canSkipPrev)
            .put("isStopped", isStopped)
        runCatching {
            HomeStreamApi(settings).call("sessions/heartbeat", body = body)
        }
    }

    fun reportHeartbeatAsync(
        context: Context,
        mediaId: String = "",
        mediaType: String = "idle",
        title: String = "",
        subtitle: String = "",
        artworkUrl: String = "",
        isPlaying: Boolean = false,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        canSeek: Boolean = false,
        canSkipNext: Boolean = false,
        canSkipPrev: Boolean = false,
        isStopped: Boolean = false,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            reportHeartbeat(
                context = appContext,
                mediaId = mediaId,
                mediaType = mediaType,
                title = title,
                subtitle = subtitle,
                artworkUrl = artworkUrl,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                canSeek = canSeek,
                canSkipNext = canSkipNext,
                canSkipPrev = canSkipPrev,
                isStopped = isStopped,
            )
        }
    }

    suspend fun fetchSessions(context: Context): SessionsSnapshot {
        val settings = ServerSettings(context.applicationContext)
        if (settings.baseUrl.isBlank() || settings.token.isBlank()) {
            return SessionsSnapshot(localNetwork = false, sessions = emptyList(), tvDevices = emptyList())
        }
        return runCatching {
            val res = HomeStreamApi(settings).call(
                "sessions",
                params = mapOf("excludeDeviceId" to deviceId(context)),
            )
            val localNetwork = res.optBoolean("localNetwork", false)
            if (!localNetwork) {
                SessionsSnapshot(localNetwork = false, sessions = emptyList(), tvDevices = emptyList())
            } else {
                SessionsSnapshot(
                    localNetwork = true,
                    sessions = parseSessionArray(res.optJSONArray("sessions")),
                    tvDevices = parseSessionArray(res.optJSONArray("tvDevices")),
                )
            }
        }.getOrElse {
            SessionsSnapshot(localNetwork = false, sessions = emptyList(), tvDevices = emptyList())
        }
    }

    private fun parseSessionArray(arr: JSONArray?): List<NetworkSession> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { idx ->
            arr.optJSONObject(idx)?.let { NetworkSession.fromJson(it) }
        }
    }

    suspend fun sendCommand(
        context: Context,
        targetDeviceId: String,
        type: String,
        positionMs: Long? = null,
        deltaMs: Long? = null,
    ): Boolean {
        val settings = ServerSettings(context.applicationContext)
        if (settings.baseUrl.isBlank() || settings.token.isBlank()) return false
        val body = JSONObject()
            .put("targetDeviceId", targetDeviceId)
            .put("type", type)
            .apply {
                if (positionMs != null) put("positionMs", positionMs.coerceAtLeast(0L))
                if (deltaMs != null) put("deltaMs", deltaMs)
            }
        return runCatching {
            HomeStreamApi(settings).call("sessions/command", body = body).optBoolean("ok", false)
        }.getOrDefault(false)
    }

    fun startTvRemoteReceiver(context: Context) {
        val appContext = context.applicationContext
        if (!appContext.isTelevision()) return

        if (tvPresenceJob?.isActive != true) {
            tvPresenceJob = scope.launch {
                while (isActive) {
                    val hasVideo = videoCommandHandler != null
                    val musicPlayer = withContext(Dispatchers.Main) { MusicService.activePlayer }
                    val hasMusic = musicPlayer != null && musicPlayer.mediaItemCount > 0
                    if (!hasVideo && !hasMusic) {
                        reportHeartbeat(appContext, mediaType = "idle", isStopped = false)
                    }
                    delay(10_000)
                }
            }
        }

        if (tvSseJob?.isActive != true) {
            tvSseJob = scope.launch {
                while (isActive) {
                    val settings = ServerSettings(appContext)
                    if (settings.baseUrl.isBlank() || settings.token.isBlank()) {
                        delay(5_000)
                        continue
                    }
                    runCatching {
                        val sseClient = HomeStreamApi(settings).mediaClient().newBuilder()
                            .readTimeout(0, TimeUnit.MILLISECONDS)
                            .build()
                        val url = "${settings.baseUrl.trimEnd('/')}/api/mobile/v1/sessions/commands?deviceId=${android.net.Uri.encode(deviceId(appContext))}"
                        val request = Request.Builder()
                            .url(url)
                            .header("Authorization", "Bearer ${settings.token}")
                            .header("Accept", "text/event-stream")
                            .build()
                        sseClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                delay(5_000)
                                return@use
                            }
                            val source = response.body?.source() ?: return@use
                            while (isActive && !source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("data:")) {
                                    val payload = line.removePrefix("data:").trim()
                                    if (payload.isNotBlank()) {
                                        runCatching { JSONObject(payload) }.getOrNull()?.let { cmd ->
                                            dispatchTvCommand(appContext, cmd)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    delay(2_500)
                }
            }
        }
    }

    fun stopTvRemoteReceiver() {
        tvPresenceJob?.cancel()
        tvPresenceJob = null
        tvSseJob?.cancel()
        tvSseJob = null
    }

    private fun dispatchTvCommand(context: Context, cmd: JSONObject) {
        val type = cmd.optString("type")
        val positionMs = if (cmd.has("positionMs") && !cmd.isNull("positionMs")) cmd.optLong("positionMs") else null
        val deltaMs = if (cmd.has("deltaMs") && !cmd.isNull("deltaMs")) cmd.optLong("deltaMs") else null

        mainHandler.post {
            if (videoCommandHandler?.invoke(type, positionMs, deltaMs) == true) {
                return@post
            }

            val music = MusicService.activePlayer
            if (music != null && music.mediaItemCount > 0) {
                when (type) {
                    "play" -> { music.play(); return@post }
                    "pause" -> { music.pause(); return@post }
                    "togglePlayPause" -> { if (music.isPlaying) music.pause() else music.play(); return@post }
                    "stop" -> {
                        context.stopService(Intent(context, MusicService::class.java))
                        reportHeartbeatAsync(context, mediaType = "idle", isStopped = true)
                        return@post
                    }
                    "next" -> { if (music.hasNextMediaItem()) music.seekToNext(); return@post }
                    "previous" -> {
                        if (music.currentPosition > 3000L) music.seekTo(0)
                        else if (music.hasPreviousMediaItem()) music.seekToPrevious()
                        else music.seekTo(0)
                        return@post
                    }
                    "seekTo" -> if (positionMs != null) { music.seekTo(positionMs); return@post }
                    "seekBy" -> if (deltaMs != null) {
                        val target = (music.currentPosition + deltaMs).coerceAtLeast(0L)
                        music.seekTo(if (music.duration > 0) target.coerceAtMost(music.duration) else target)
                        return@post
                    }
                }
            }

            when (type) {
                "volumeUp" -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                }
                "volumeDown" -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                }
                "muteToggle" -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                }
                "home" -> {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                }
                "dpadUp" -> sendKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
                "dpadDown" -> sendKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
                "dpadLeft" -> sendKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                "dpadRight" -> sendKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
                "dpadCenter" -> sendKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER)
                "back" -> sendKeyEvent(KeyEvent.KEYCODE_BACK)
            }
        }
    }

    private fun sendKeyEvent(keyCode: Int) {
        val activity = foregroundActivityRef.get() ?: return
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
