package com.homestream.app

import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var api: HomeStreamApi
    private lateinit var media: Media
    private lateinit var playerView: PlayerView
    private lateinit var overlay: LinearLayout
    private var player: ExoPlayer? = null
    private var revealingControlsKey: Int? = null
    private var hiddenSeekKey: Int? = null
    private var castControllerOpened = false
    private var knownDurationMs = 0L
    private var position = 0L
    private var hlsStartOffsetMs = 0L
    private var torrentStreamPath: String? = null
    private var torrentStartOffsetMs = 0L
    private var isHlsTranscodeActive = false
    private var refreshInitialResume = true
    private var currentPlaybackId = ""
    private var playbackPolicy = ""
    private var prepared = false
    private var reportedStartedId = ""
    private var transcode = false
    private var playbackMethod = "DirectPlay"
    private var playbackDetails = "Preparing playback"
    private var modeBadge: TextView? = null
    private var audioButton: View? = null
    private var syncPlayButton: View? = null
    private var isStreamingDownload = false
    private var castGeneration = 0
    private var castLoading = false
    private var castStartup: Job? = null
    private var castStartPosition: Long? = null
    private var castVideoCapabilities: String? = null
    private var castCompatible = false
    private var castNeedsSafeProfile = false
    private val castRejectedItems = mutableSetOf<String>()
    private var castAudioCodecs = "aac,mp3"
    private var receiverProfile = castProfiles[2]
    private var wantsPlay = true
    private var timer: Job? = null
    private var negotiation: Job? = null
    private var retryJob: Job? = null
    private var extrasTickerJob: Job? = null
    private var trickplayJob: Job? = null
    private var playbackStarted = false
    private data class SkipSeg(val type: String, val startSec: Double, val endSec: Double)
    private data class TrickplayCue(val startSec: Double, val endSec: Double, val x: Int, val y: Int, val w: Int, val h: Int, val sheetUrl: String)
    private var skipSegments: List<SkipSeg> = emptyList()
    private var nativeVersions: List<Pair<String, String>> = emptyList()
    private var selectedVersionId: String? = null
    private var nativeAudioTracks: List<Pair<Int, String>> = emptyList()
    private var selectedAudioIndex: Int? = null
    private var burnInSubtitles: List<Pair<Int, String>> = emptyList()
    private var burnSubtitleIndex: Int? = null
    private var qualityMode: String = "auto"
    private var trickplayCues: List<TrickplayCue> = emptyList()
    private var trickplaySprites: Map<String, android.graphics.Bitmap> = emptyMap()
    private var skipBtn: TextView? = null
    private var trickplayPreview: ImageView? = null
    private var syncRoomCode: String? = null
    private var applyingSyncPlay = false
    private var remote: RemoteMediaClient? = null
    private var episodes = EpisodeQueue()
    private var episodeJob: Job? = null
    private var titleColumn: LinearLayout? = null
    private var queueBtn: android.widget.ImageButton? = null
    private val subtitleAppearance by lazy { SubtitleAppearance(this) }
    private val playbackPrefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private var autoplay: Boolean
        get() = playbackPrefs.getBoolean("autoplay", true)
        set(value) { playbackPrefs.edit().putBoolean("autoplay", value).apply() }


    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) player?.pause()
        }
    }
    private val castCtx by lazy { castContext(this) }
    private val remoteCallback: RemoteMediaClient.Callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            remote?.let { client ->
                if (client.playerState == MediaStatus.PLAYER_STATE_IDLE && client.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    castCtx?.sessionManager?.currentCastSession?.let { recoverCast(it) }
                    return
                }
                val received = client.mediaInfo?.customData?.optJSONObject("media")?.let { Media(it) }
                if (received != null && received.playbackId != currentPlaybackId) {
                    media = received; currentPlaybackId = received.playbackId; updateEpisodeControls()
                    client.mediaInfo?.customData?.optJSONObject("playback")?.let { describeStream(it) }
                }
                val activeId = if (currentPlaybackId.isNotBlank()) currentPlaybackId else media.playbackId
                if (client.mediaInfo?.customData?.optString("itemId") == activeId && (client.isPlaying || client.isPaused)) {
                    castStartup?.cancel(); castStartup = null; castStartPosition = null
                    position = client.approximateStreamPosition
                    reportStarted()
                }
                if (client.playerState == MediaStatus.PLAYER_STATE_IDLE && client.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                    val next = if (autoplay) episodes.next(currentPlaybackId) else null
                    val session = castCtx?.sessionManager?.currentCastSession
                    if (next != null && session?.isConnected == true) {
                        client.unregisterCallback(remoteCallback); remote = null
                        media = next; currentPlaybackId = next.playbackId; position = 0; wantsPlay = true
                        cast(session, forceReload = true)
                    }
                    return
                }
                if (client.isPlaying || client.isPaused || client.isLoadingNextItem) openCastController()
            }
        }
    }
    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, id: String) { cast(session) }
        override fun onSessionResumed(session: CastSession, suspended: Boolean) { cast(session) }
        override fun onSessionEnded(session: CastSession, error: Int) { if (castControllerOpened) finish() else returnToLocal() }
        override fun onSessionSuspended(session: CastSession, reason: Int) { player?.pause(); showMessage("Cast connection interrupted. Reconnect from the Cast button.") }
        override fun onSessionStartFailed(session: CastSession, error: Int) { returnToLocal() }
        override fun onSessionResumeFailed(session: CastSession, error: Int) { returnToLocal() }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {
            castStartup?.cancel(); castStartup = null
            remote?.let {
                position = castStartPosition ?: it.approximateStreamPosition
                if (castStartPosition == null) wantsPlay = it.isPlaying
            }
        }
        override fun onSessionResuming(session: CastSession, id: String) {}
    }

    private fun describeStream(stream: JSONObject) {
        if (stream.optLong("totalDurationTicks") > 0) knownDurationMs = ticksToMs(stream.optLong("totalDurationTicks"))
        playbackMethod = stream.optString("playMethod").ifBlank { if (stream.optBoolean("isTranscoding")) "Transcode" else "DirectPlay" }
        val hls = stream.optString("transport") == "HLS" || Uri.parse(stream.optString("streamUrl")).path.orEmpty().endsWith(".m3u8", ignoreCase = true)
        playbackDetails = listOf(
            "Delivery: $playbackMethod",
            stream.optString("resolution").takeIf { it.isNotBlank() }?.let { "Resolution: $it" },
            "Transport: ${if (hls) "HLS" else "progressive file"}",
            stream.optString("sourceVideoCodec").takeIf { it.isNotBlank() }?.let { "Source video: $it" },
            stream.optString("sourceAudioCodec").takeIf { it.isNotBlank() }?.let { "Source audio: $it" },
            stream.optString("transcodeReasons").takeIf { it.isNotBlank() }?.let { "Server reasons: $it" },
            "HLS describes delivery; it does not by itself mean video re-encoding."
        ).filterNotNull().joinToString("\n")
        updateModeBadge()
    }

    private fun updateModeBadge() {
        modeBadge?.text = when {
            (remote != null || castLoading) && (castCompatible || currentPlaybackId in castRejectedItems) -> "COMPAT · STEREO"
            playbackMethod == "DirectPlay" -> "DIRECT"
            playbackMethod == "DirectStream" -> "REMUX"
            else -> "SERVER STREAM"
        }
        modeBadge?.background = rounded(if (transcode) 0x66E50914.toInt() else 0x44FFFFFF.toInt(), radius = dp(14).toFloat())
    }

    private fun currentMediaPositionMs(): Long {
        val exo = player ?: return position.coerceAtLeast(0L)
        val basePos = exo.currentPosition.coerceAtLeast(0L)
        return if (torrentStreamPath != null) torrentStartOffsetMs + basePos else if (isHlsTranscodeActive) hlsStartOffsetMs + basePos else basePos
    }

    private fun serverMediaUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        val parsed = Uri.parse(rawUrl)
        val path = parsed.encodedPath
        val relative = if (!parsed.scheme.isNullOrBlank() && path != null && path.startsWith("/api/")) {
            buildString {
                append(path)
                parsed.encodedQuery?.takeIf { it.isNotBlank() }?.let { append('?').append(it) }
                parsed.encodedFragment?.takeIf { it.isNotBlank() }?.let { append('#').append(it) }
            }
        } else rawUrl
        return api.absolute(relative)
    }

    private fun seekMediaTo(targetMs: Long) {
        val clamped = targetMs.coerceAtLeast(0L)
        val exo = player ?: return
        torrentStreamPath?.let { path ->
            val uri = Uri.parse(path)
            val next = uri.buildUpon().clearQuery().apply {
                uri.queryParameterNames.filter { it != "start" && it != "startTimeTicks" }.forEach { key ->
                    uri.getQueryParameters(key).forEach { appendQueryParameter(key, it) }
                }
                appendQueryParameter("start", (clamped / 1000.0).toString())
            }.build().toString()
            position = clamped
            launchStreamUrl(next, compatible = clamped > 0L)
            return
        }
        if (isHlsTranscodeActive) {
            val relMs = clamped - hlsStartOffsetMs
            val loadedDur = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            if (relMs < 0L || loadedDur <= 4000L || relMs > loadedDur - 2000L) {
                position = clamped
                wantsPlay = exo.playWhenReady
                startLocal()
                return
            }
            exo.seekTo(relMs)
        } else {
            exo.seekTo(clamped)
        }
    }

    private fun getDeviceSupportedAudioCodecs(): String {
        val codecs = mutableSetOf("aac", "mp3", "opus", "flac", "vorbis")
        try {
            val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    when (type.lowercase()) {
                        "audio/ac3" -> codecs.add("ac3")
                        "audio/eac3", "audio/eac3-joc" -> codecs.add("eac3")
                        "audio/vnd.dts", "audio/dts" -> codecs.add("dts")
                    }
                }
            }
        } catch (_: Exception) {}
        return codecs.joinToString(",")
    }

    private var castingPresentation = false
    private lateinit var playbackRoot: FrameLayout

    private fun setPlaybackPresentation(casting: Boolean) {
        castingPresentation = casting
        if (!isTelevision()) requestedOrientation = if (casting) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (casting) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (::playbackRoot.isInitialized) ViewCompat.requestApplyInsets(playbackRoot)
    }

    private val musicStarted = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopService(android.content.Intent(this, MusicService::class.java))
        androidx.core.content.ContextCompat.registerReceiver(this, musicStarted,
            android.content.IntentFilter("$packageName.MUSIC_STARTED"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        media = runCatching { Media(JSONObject(savedInstanceState?.getString("media") ?: intent.getStringExtra("media") ?: "")) }.getOrElse { finish(); return }
        media.json.optJSONArray("mixQueue")?.let { arr ->
            val list = (0 until arr.length()).map { Media(arr.getJSONObject(it)) }
            if (list.isNotEmpty()) episodes = EpisodeQueue(list)
        }
        isStreamingDownload = intent.getBooleanExtra("isStreamingDownload", false) || media.json.optBoolean("isStreamingDownload")
        knownDurationMs = ticksToMs(media.json.optLong("totalDurationTicks"))
        currentPlaybackId = media.playbackId
        castCompatible = playbackPrefs.getBoolean("cast-compatible", false)
        api = HomeStreamApi(ServerSettings(this))
        refreshInitialResume = savedInstanceState == null && !intent.getBooleanExtra("fromBeginning", false)
        position = savedInstanceState?.getLong("position") ?: if (intent.getBooleanExtra("fromBeginning", false)) 0 else media.resumeMs
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setPlaybackPresentation(castCtx?.sessionManager?.currentCastSession?.isConnected == true)
        val frame = FrameLayout(this).apply { setBackgroundColor(INK) }
        playbackRoot = frame
        playerView = PlayerView(this).apply {
            setShowSubtitleButton(true); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerAutoShow = true; controllerShowTimeoutMs = if (isTelevision()) 6000 else 4000
        }
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.isFocusedByDefault = true
        if (isTelevision()) {
            configureRemoteControls()
            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (playerView.isControllerFullyVisible) playerView.hideController() else finish()
                }
            })
        }
        subtitleAppearance.apply(playerView.subtitleView)
        playerView.findViewById<android.view.ViewGroup>(androidx.media3.ui.R.id.exo_basic_controls)?.let { controls ->
            val subBtn = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)
            val syncBtn = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_syncplay)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                background = subBtn?.background?.constantState?.newDrawable(resources)?.mutate()
                scaleType = ImageView.ScaleType.CENTER
                isFocusable = true
                isClickable = true
                contentDescription = "SyncPlay"
                setOnClickListener { showSyncPlayDialog() }
            }
            syncPlayButton = syncBtn
            val audioBtn = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_audio)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                background = subBtn?.background?.constantState?.newDrawable(resources)?.mutate()
                scaleType = ImageView.ScaleType.CENTER
                isFocusable = true
                isClickable = true
                contentDescription = "Audio"
                setOnClickListener { showAudioMenu() }
            }
            audioButton = audioBtn
            val lp = subBtn?.layoutParams ?: LinearLayout.LayoutParams(dp(40), dp(40))
            val insertIndex = if (subBtn != null) controls.indexOfChild(subBtn).coerceAtLeast(0) else 0
            controls.addView(syncBtn, insertIndex, lp)
            controls.addView(audioBtn, insertIndex + 1, lp)
        }
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.setOnClickListener {
            showSubtitlesMenu()
        }
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
            showPlayerSettings()
        }
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(RED)
            setScrubberColor(Color.WHITE)
            setBufferedColor(0x80FFFFFF.toInt())
            setUnplayedColor(0x33FFFFFF.toInt())
            addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, positionMs: Long) = showTrickplayAt(positionMs)
                override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, positionMs: Long) = showTrickplayAt(positionMs)
                override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, positionMs: Long, canceled: Boolean) {
                    trickplayPreview?.visibility = View.GONE
                }
            })
        }
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        trickplayPreview = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        frame.addView(trickplayPreview, FrameLayout.LayoutParams(dp(160), dp(90), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(86)
        })

        skipBtn = TextView(this).apply {
            visibility = View.GONE
            text = "Skip Intro →"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(Color.WHITE, radius = dp(8).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true; isFocusable = true
        }
        frame.addView(skipBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(20); bottomMargin = dp(88)
        })

        // Netflix-style top control overlay with soft vertical scrim gradient
        overlay = column().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xD9000000.toInt(), 0x90000000.toInt(), 0x00000000)
            )
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Circular Netflix-style back button
        val backBtn = FrameLayout(this).apply {
            background = rounded(0x44000000, radius = dp(20).toFloat())
            isClickable = true
            isFocusable = true
            addView(ImageView(this@PlayerActivity).apply {
                setImageResource(R.drawable.ic_arrow_back)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { finish() }
        }
        bar.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })

        // Title column (shows series name in subtle tone, episode/movie title in bold)
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val seriesTitle = media.json.optString("seriesTitle").ifBlank { media.json.optString("seriesName") }
        if (seriesTitle.isNotBlank() && seriesTitle != media.title) {
            titleCol.addView(TextView(this).apply {
                text = seriesTitle; textSize = 12.5f; setTextColor(0xB3FFFFFF.toInt())
            })
            titleCol.addView(TextView(this).apply {
                text = media.title; textSize = 16.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        } else {
            titleCol.addView(TextView(this).apply {
                text = media.title; textSize = 17.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        titleColumn = titleCol
        bar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        // Stream format toggle badge (Direct vs Transcode)
        if (!media.isDownload) {
            val badge = TextView(this).apply {
                visibility = if (isTelevision()) View.GONE else View.VISIBLE
                text = "DIRECT"
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = rounded(if (transcode) 0x66E50914.toInt() else 0x44000000.toInt(), radius = dp(14).toFloat())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = "Playback mode. Tap to change mode; hold for stream details."
                setOnLongClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity).setTitle("Playback details")
                        .setMessage(playbackDetails).setPositiveButton("Close", null).show()
                    true
                }
                setOnClickListener {
                    if (remote != null) {
                        val client = remote ?: return@setOnClickListener
                        val session = castCtx?.sessionManager?.currentCastSession ?: return@setOnClickListener
                        position = client.approximateStreamPosition; wantsPlay = client.isPlaying
                        castCompatible = !castCompatible
                        Toast.makeText(this@PlayerActivity, if (castCompatible) "Compatible HLS with stereo AAC" else "Automatic receiver-compatible playback", Toast.LENGTH_SHORT).show()
                        playbackPrefs.edit().putBoolean("cast-compatible", castCompatible).apply()
                        client.unregisterCallback(remoteCallback); remote = null
                        negotiation?.cancel(); castLoading = false
                        cast(session, forceReload = true)
                        return@setOnClickListener
                    }
                    player?.let { position = currentMediaPositionMs(); wantsPlay = it.playWhenReady }
                    transcode = !transcode
                    updateModeBadge()
                    showMessage(if (transcode) "Switching to compatible HLS transcode…" else "Switching to direct play…")
                    startLocal()
                }
            }
            modeBadge = badge
            bar.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }

        // Sleek circular Cast button
        if (!media.isDownload) {
            val castBtn = castButton(this)
            if (castBtn != null) {
                val castContainer = FrameLayout(this).apply {
                    background = rounded(0x44000000, radius = dp(20).toFloat())
                    isClickable = true
                    addView(castBtn, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }
                bar.addView(castContainer, LinearLayout.LayoutParams(dp(40), dp(40)))
            }
        }
        overlay.addView(bar)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_prev)?.apply {
            contentDescription = "Previous episode"
            setOnClickListener { changeEpisode(-1) }
        }
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_next)?.apply {
            contentDescription = "Next episode"
            setOnClickListener { changeEpisode(1) }
        }
        updateEpisodeNavigation()
        // Icon-only 3-line Episodes / Mix button inside Media3's bottom controls bar, left of settings
        val settingsBtn = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
        val controlsRow = settingsBtn?.parent as? android.view.ViewGroup
        val qBtn = android.widget.ImageButton(this).apply {
            visibility = if (episodes.items.isNotEmpty()) View.VISIBLE else View.GONE
            setImageResource(R.drawable.ic_menu)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = settingsBtn?.background?.constantState?.newDrawable(resources)?.mutate()
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            if (settingsBtn != null) {
                setPadding(settingsBtn.paddingLeft, settingsBtn.paddingTop, settingsBtn.paddingRight, settingsBtn.paddingBottom)
            } else {
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            isClickable = true
            isFocusable = true
            contentDescription = if (media.source == "youtube") "Mix Playlist" else "Episodes"
            setOnClickListener { showEpisodeQueue() }
        }
        queueBtn = qBtn
        if (controlsRow != null && settingsBtn != null) {
            val idx = controlsRow.indexOfChild(settingsBtn).coerceAtLeast(0)
            val lp = settingsBtn.layoutParams ?: android.view.ViewGroup.LayoutParams(dp(48), dp(48))
            controlsRow.addView(qBtn, idx, lp)
        } else {
            frame.addView(qBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(104); bottomMargin = dp(18)
            })
        }

        frame.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visible ->
            val showOverlay = visible == View.VISIBLE || remote != null
            overlay.visibility = if (showOverlay) View.VISIBLE else View.GONE
            if (controlsRow == null) {
                qBtn.visibility = if (showOverlay && episodes.items.isNotEmpty()) View.VISIBLE else View.GONE
            }
            updateEpisodeNavigation()
            if (visible == View.VISIBLE) focusPlaybackControl()
        })
        setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or
                if (castingPresentation) WindowInsetsCompat.Type.systemBars() else 0)
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
    }
    override fun onStart() {
        super.onStart()
        PlaybackSessions.registerVideoHandler(videoRemoteHandler)
        if (castControllerOpened) { finish(); return }
        playbackPolicy = ServerSettings(this).policyRevision
        playbackStarted = true
        runCatching { registerReceiver(noisyReceiver, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
        castCtx?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        val offlinePath = intent.getStringExtra("localPath")?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
        lifecycleScope.launch {
            try {
                if (offlinePath == null && api.refreshProfilePolicy()) { clearCastIdle(this@PlayerActivity); finish(); return@launch }
                val session = if (offlinePath == null) castCtx?.sessionManager?.currentCastSession else null
                if (session?.isConnected == true) cast(session) else {
                    remote?.unregisterCallback(remoteCallback); remote = null; startLocal()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val fallbackLocal = OfflineMediaStore(this@PlayerActivity, ServerSettings(this@PlayerActivity)).localFileFor(media.playbackId)
                if (fallbackLocal != null) startLocal()
                else showMessage(e.message ?: "Could not confirm profile")
            }
        }
        timer = lifecycleScope.launch {
            while (true) {
                delay(10_000)
                if (ServerSettings(this@PlayerActivity).token.isNotBlank()) {
                    try {
                        api.refreshProfilePolicy()
                        if (ServerSettings(this@PlayerActivity).policyRevision != playbackPolicy) {
                            clearCastIdle(this@PlayerActivity); releaseLocal(); finish(); return@launch
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        remote?.pause(); player?.pause()
                        showMessage("Connect to HomeStream to confirm your profile")
                    }
                }
                if (prepared || remote != null) {
                    report(false)
                    if (remote == null) reportVideoSession()
                }
            }
        }
    }
    private fun showUnDownloadedUI(reason: String = "This title has not been downloaded yet.") {
        clearMessages()
        showMessage(reason)
        if (ServerSettings(this).kidsRating.isBlank()) {
            overlay.addView(action("▶  Stream Now") {
                val session = castCtx?.sessionManager?.currentCastSession
                if (session?.isConnected == true) cast(session) else startStreamingTorrentPipeline()
            })
            overlay.addView(action("⬇  Request Download") {
                requestLibraryDownload()
            })
        }
        overlay.addView(action("←  Back to Browse") { finish() })
    }

    private fun startStreamingTorrentPipeline() {
        isStreamingDownload = true
        negotiation?.cancel()
        releaseLocal()
        clearMessages()

        val isTv = intent.getStringExtra("mediaType") == "tv" || media.type == "Series" || media.type == "Episode"
        val sNum = intent.getIntExtra("season", 0).takeIf { it > 0 } ?: media.json.optInt("seasonNumber", 1).coerceAtLeast(1)
        val epNum = intent.getIntExtra("episode", 0).takeIf { it > 0 } ?: media.json.optInt("episodeNumber", 1).coerceAtLeast(1)
        val sTitle = intent.getStringExtra("seriesTitle")?.ifBlank { null } ?: media.json.optString("seriesName").ifBlank { media.title }

        val tmdb = intent.getIntExtra("tmdbId", 0).takeIf { it > 0 } ?: if (media.tmdbId > 0) media.tmdbId else {
            val match = Regex("""^(?:seerr|tmdb)-(?:movie-|tv-|ep-)?(\d+)(?:-\d+-\d+)?$""").find(media.id)
            match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        if (tmdb <= 0) {
            showMessage("This title has no TMDB identifier for torrent streaming.")
            return
        }
        currentPlaybackId = if (isTv) "tmdb-ep-$tmdb-$sNum-$epNum" else "tmdb-movie-$tmdb"
        val totalDurationMs = (media.json.optInt("durationMinutes").takeIf { it > 0 } ?: 120) * 60L * 1000L
        if (knownDurationMs <= 0) knownDurationMs = totalDurationMs
        val timeBar = playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
        timeBar?.setDuration(totalDurationMs)

        showMessage("Connecting to torrent stream…")

        val streamPath = buildString {
            append("stream/torrent?tmdbId=").append(tmdb)
            append("&type=").append(if (isTv) "tv" else "movie")
            if (position > 0L) append("&start=").append(position / 1000.0)
            if (isTv) {
                append("&season=").append(sNum)
                append("&episode=").append(epNum)
                if (sTitle.isNotBlank()) {
                    append("&title=").append(java.net.URLEncoder.encode(sTitle, "UTF-8"))
                }
            }
        }

        launchStreamUrl(streamPath)
    }

    private fun requestLibraryDownload() {
        val isTv = intent.getStringExtra("mediaType") == "tv" || media.type == "Series" || media.type == "Episode"
        val season = intent.getIntExtra("season", 0).takeIf { it > 0 } ?: media.json.optInt("seasonNumber", 1).coerceAtLeast(1)
        val tmdb = intent.getIntExtra("tmdbId", 0).takeIf { it > 0 } ?: media.tmdbId.takeIf { it > 0 }
            ?: Regex("""^(?:seerr|tmdb)-(?:movie-|tv-|ep-)?(\d+)(?:-\d+-\d+)?$""").find(media.id)?.groupValues?.get(1)?.toIntOrNull()
        if (tmdb == null || tmdb <= 0) { showMessage("This title has no TMDB identifier."); return }
        negotiation?.cancel()
        negotiation = lifecycleScope.launch {
            try {
                val body = JSONObject().put("mediaType", if (isTv) "tv" else "movie").put("mediaId", tmdb)
                if (isTv) body.put("seasons", org.json.JSONArray().put(season))
                api.call("request", body = body)
                showUnDownloadedUI("Download requested. Play it from your library after import, or choose Stream Now.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                showUnDownloadedUI("Could not request this download. Try again or choose Stream Now.")
            }
        }
    }

    private fun deviceStreamUri(uri: Uri, compatible: Boolean): Uri {
        if (media.source == "youtube" || uri.path?.let { it.endsWith("/stream/torrent") } != true) return uri
        val replaced = setOf("receiver", "videoCapabilities", "videoCodecs", "audioCodecs", "native", "transcode", "forceTranscode")
        return uri.buildUpon().clearQuery().apply {
            for (key in uri.queryParameterNames.filter { it !in replaced }) {
                uri.getQueryParameters(key).forEach { appendQueryParameter(key, it) }
            }
            appendQueryParameter("receiver", if (isTelevision()) "native-tv" else "native-android")
            appendQueryParameter("videoCapabilities", deviceVideoProfiles().toString())
            appendQueryParameter("audioCodecs", getDeviceSupportedAudioCodecs())
            if (compatible) appendQueryParameter("forceTranscode", "true")
        }.build()
    }

    private fun observeVideoDecoder(exo: ExoPlayer) {
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                android.util.Log.i("HomeStream", "Video decoder initialized: $decoderName")
            }
        })
    }

    private fun launchStreamUrl(streamPath: String, compatible: Boolean = transcode) {
        val torrentUri = Uri.parse(streamPath)
        torrentStreamPath = streamPath.takeIf { torrentUri.path?.endsWith("/stream/torrent") == true }
        torrentStartOffsetMs = if (torrentStreamPath != null) ((torrentUri.getQueryParameter("start")?.toDoubleOrNull() ?: 0.0) * 1000).toLong() else 0L
        isHlsTranscodeActive = false
        hlsStartOffsetMs = 0L
        val isLocalFile = streamPath.startsWith("file://") || (streamPath.startsWith("/") && !streamPath.startsWith("/api/"))
        val playUri = when {
            isLocalFile -> Uri.parse(
                if (streamPath.startsWith("/")) Uri.fromFile(java.io.File(streamPath)).toString() else streamPath
            )
            streamPath.startsWith("http://") || streamPath.startsWith("https://") -> deviceStreamUri(Uri.parse(streamPath), compatible)
            else -> {
                val fullPath = if (streamPath.startsWith("/api/")) streamPath
                               else "/api/mobile/v1/${streamPath.removePrefix("/")}"
                deviceStreamUri(Uri.parse(api.absolute(fullPath)), compatible)
            }
        }

        clearMessages()
        if (!isLocalFile) showMessage("Buffering download stream…")
        describeStream(JSONObject().put("streamUrl", playUri.toString())
            .put("playMethod", if (isLocalFile || !compatible || media.source == "youtube") "DirectPlay" else "Transcode"))
        var bufferRetries = 0
        val item = MediaItem.Builder()
            .setUri(playUri)
            .apply { if (!isLocalFile && compatible && media.source != "youtube") setMimeType(MimeTypes.VIDEO_MP4) }
            .build()
        releaseLocal()

        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(api.mediaClient())
            .setUserAgent("HomeStream-Android/1.0")
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .build().also { exo ->
            observeVideoDecoder(exo)
            playerView.player = if (torrentStreamPath == null) exo else object : androidx.media3.common.ForwardingPlayer(exo) {
                override fun getCurrentTimeline(): androidx.media3.common.Timeline {
                    val base = super.getCurrentTimeline()
                    if (knownDurationMs <= 0L || base.isEmpty) return base
                    return object : androidx.media3.exoplayer.source.ForwardingTimeline(base) {
                        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
                            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                            window.durationUs = knownDurationMs * 1000L
                            window.isDynamic = false; window.isSeekable = true; window.liveConfiguration = null
                            return window
                        }
                        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                            super.getPeriod(periodIndex, period, setIds)
                            period.durationUs = knownDurationMs * 1000L
                            return period
                        }
                    }
                }
                override fun getContentDuration(): Long = duration
                override fun isCurrentMediaItemDynamic(): Boolean = false
                override fun isCurrentMediaItemLive(): Boolean = false
                override fun getDuration(): Long = knownDurationMs.takeIf { it > 0L } ?: super.getDuration()
                override fun getCurrentPosition(): Long = currentMediaPositionMs()
                override fun getContentPosition(): Long = currentMediaPositionMs()
                override fun getBufferedPosition(): Long = torrentStartOffsetMs + exo.bufferedPosition.coerceAtLeast(0L)
                override fun isCurrentMediaItemSeekable(): Boolean = true
                override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                    .addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD).build()
                override fun isCommandAvailable(command: Int): Boolean = command in setOf(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD
                ) || super.isCommandAvailable(command)
                override fun seekToDefaultPosition() = seekMediaTo(0L)
                override fun seekToDefaultPosition(mediaItemIndex: Int) = seekMediaTo(0L)
                override fun seekTo(positionMs: Long) = seekMediaTo(positionMs)
                override fun seekTo(mediaItemIndex: Int, positionMs: Long) = seekMediaTo(positionMs)
                override fun seekBack() = seekMediaTo((currentMediaPositionMs() - seekBackIncrement).coerceAtLeast(0L))
                override fun seekForward() = seekMediaTo((currentMediaPositionMs() + seekForwardIncrement).coerceAtMost(duration))
            }
            observeEpisodeNavigation(exo)
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        prepared = true
                        clearMessages()
                        reportStarted()
                    }
                    if (state == Player.STATE_ENDED) {
                        position = currentMediaPositionMs()
                        report(true)
                    }
                }
                override fun onTracksChanged(tracks: Tracks) {
                    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (!compatible && audio.isNotEmpty() && audio.none { it.isSupported }) {
                        transcode = true
                        launchStreamUrl(streamPath, compatible = true)
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("HomeStream", "Stream download playback error: ${error.errorCodeName}")
                    if (!compatible && error.errorCode in setOf(
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                    )) {
                        transcode = true
                        showMessage("Preparing compatible download stream…")
                        launchStreamUrl(streamPath, compatible = true)
                        return
                    }
                    val status = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                    val retryable = status == 425 || (isStreamingDownload && status == 503)
                    val maxRetries = 25
                    if (!retryable || bufferRetries++ >= maxRetries) {
                        clearMessages()
                        showMessage("Download playback failed (${status ?: error.errorCodeName}).")
                        overlay.addView(action("Retry") { launchStreamUrl(streamPath, compatible) })
                        overlay.addView(action("Request Download") { requestLibraryDownload() })
                        overlay.addView(action("←  Back to Browse") { finish() })
                        return
                    }
                    clearMessages()
                    showMessage("Buffering torrent download (waiting for initial pieces, attempt $bufferRetries/$maxRetries)…")
                    retryJob?.cancel()
                    retryJob = lifecycleScope.launch {
                        delay(2500)
                        if (playbackStarted && player === exo && !isFinishing && !isDestroyed) {
                            exo.seekTo(exo.currentPosition)
                            exo.prepare()
                            exo.playWhenReady = true
                        }
                    }
                }
            })
            exo.setMediaItem(item)
            if (position > 0) exo.seekTo(position)
            exo.prepare()
            exo.playWhenReady = wantsPlay
        }
        playerView.showController()
        focusPlaybackControl()
    }

    private suspend fun resolvePlaybackId(): String {
        if (media.isDownload) return media.playbackId
        if (media.type in listOf("Series", "Season") && media.playableEpisodeId.isNotBlank()) return media.playableEpisodeId
        if (media.type !in listOf("Series", "Season") && !media.playbackId.startsWith("seerr-") && !media.playbackId.startsWith("tmdb-")) return media.playbackId
        val item = api.call("media/${Uri.encode(media.id)}").optJSONObject("item") ?: return media.playbackId
        item.optString("playableEpisodeId").takeIf { it.isNotBlank() }?.let { return it }
        if (media.type !in listOf("Series", "Season")) item.optString("jellyfinId").takeIf { it.isNotBlank() }?.let { return it }
        val seasons = item.optJSONArray("seasons") ?: return media.playbackId
        for (i in 0 until seasons.length()) {
            val episodes = seasons.getJSONObject(i).optJSONArray("episodes") ?: continue
            for (j in 0 until episodes.length()) {
                val episode = Media(episodes.getJSONObject(j))
                if (episode.isAvailableForPlayback && !episode.playbackId.startsWith("seerr-") && !episode.playbackId.startsWith("tmdb-")) return episode.playbackId
            }
        }
        return media.playbackId
    }

    private fun startLocal() {
        torrentStreamPath = null
        torrentStartOffsetMs = 0L
        val castSession = castCtx?.sessionManager?.currentCastSession
        if (castSession?.isConnected == true) {
            cast(castSession)
            return
        }
        setPlaybackPresentation(false)
        negotiation?.cancel(); clearMessages(); showMessage("Preparing playback…")
        val offlinePath = intent.getStringExtra("localPath")?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
            ?: OfflineMediaStore(this, ServerSettings(this)).localFileFor(media.playbackId)?.absolutePath
        if (offlinePath != null) {
            clearMessages()
            launchStreamUrl(Uri.fromFile(java.io.File(offlinePath)).toString())
            return
        }
        // Stream Now must not reuse a qBittorrent download URL supplied by browse metadata.
        if (media.source != "youtube" && (isStreamingDownload || media.json.optBoolean("isDownloading"))) {
            startStreamingTorrentPipeline()
            return
        }
        if (media.source == "youtube") {
            isStreamingDownload = true
            launchStreamUrl(media.downloadUrl)
            return
        }
        val hasResumeEpisode = media.type in listOf("Series", "Season") && media.playableEpisodeId.isNotBlank()
        if (isStreamingDownload || (!media.isAvailableForPlayback && !hasResumeEpisode)) {
            startStreamingTorrentPipeline()
            return
        }
        negotiation = lifecycleScope.launch {
            try {
                val targetId = resolvePlaybackId()

                if (targetId.startsWith("seerr-") || targetId.startsWith("tmdb-") || media.isVirtual) {
                    startStreamingTorrentPipeline()
                    return@launch
                }

                updateModeBadge()
                val audioCodecs = getDeviceSupportedAudioCodecs()
                val detectedVideoCapabilities = deviceVideoProfiles()
                val detectedVideoCodecs = (0 until detectedVideoCapabilities.length()).joinToString(",") {
                    detectedVideoCapabilities.getJSONObject(it).getString("codec")
                }
                suspend fun fetchPlaybackStream(startMs: Long): JSONObject = api.call(
                    "playback",
                    mapOf(
                        "itemId" to targetId,
                        "forceTranscode" to transcode.toString(),
                        "videoCodecs" to detectedVideoCodecs,
                        "audioCodecs" to audioCodecs
                    ) + (if (startMs > 0L) mapOf("startTimeTicks" to msToTicks(startMs).toString()) else emptyMap())
                      + (selectedVersionId?.let { mapOf("versionId" to it) } ?: emptyMap())
                      + (selectedAudioIndex?.let { mapOf("audioIndex" to it.toString()) } ?: emptyMap())
                      + (burnSubtitleIndex?.let { mapOf("burnSubtitleIndex" to it.toString()) } ?: emptyMap())
                      + when (qualityMode) {
                          "1080p" -> mapOf("maxWidth" to "1920", "maxHeight" to "1080")
                          "720p" -> mapOf("maxWidth" to "1280", "maxHeight" to "720")
                          "original" -> mapOf("maxWidth" to "3840", "maxHeight" to "2160")
                          else -> emptyMap()
                      }
                      + mapOf("videoCapabilities" to detectedVideoCapabilities.toString())
                      + if (isTelevision()) mapOf("receiver" to "native-tv") else emptyMap()
                )
                var stream = fetchPlaybackStream(position)
                val autoVersion = stream.optString("selectedVersionId").takeIf { it.isNotBlank() }
                if (selectedVersionId == null && autoVersion != null) {
                    selectedVersionId = autoVersion
                }
                applyInitialResume(stream)
                var url = stream.optString("streamUrl")
                if (url.isBlank()) {
                    showUnDownloadedUI(stream.optString("error", "This title has not been downloaded yet."))
                    return@launch
                }
                var isTranscodingStream = stream.optString("transport") == "HLS" || Uri.parse(url).path.orEmpty().endsWith(".m3u8", ignoreCase = true)
                if (!media.isDownload && isTranscodingStream && position > 0L && stream.optLong("startTimeTicks", 0L) == 0L) {
                    stream = fetchPlaybackStream(position)
                    url = stream.optString("streamUrl")
                    if (url.isBlank()) {
                        showUnDownloadedUI(stream.optString("error", "This title has not been downloaded yet."))
                        return@launch
                    }
                    isTranscodingStream = stream.optString("transport") == "HLS" || Uri.parse(url).path.orEmpty().endsWith(".m3u8", ignoreCase = true)
                }
                describeStream(stream)
                parseNativePlayerExtras(stream)
                currentPlaybackId = targetId
                // Resolve the resumed episode and its queue before playback can end.
                loadEpisodeContextNow(targetId)
                isHlsTranscodeActive = isTranscodingStream
                hlsStartOffsetMs = if (isTranscodingStream) ticksToMs(stream.optLong("startTimeTicks", 0L)) else 0L
                if (isTranscodingStream && !transcode) {
                    transcode = true
                    updateModeBadge()
                }

                val rawUri = serverMediaUrl(url)
                val playUri = Uri.parse(rawUri)

                val tracks = if (media.isDownload) emptyList() else subtitleItems(targetId, includeTextAsBurnIn = isTranscodingStream)
                val item = MediaItem.Builder().setUri(playUri).setMediaId(targetId)
                    .setSubtitleConfigurations(if (isTranscodingStream) emptyList() else tracks).apply {
                        if (isTranscodingStream) setMimeType(MimeTypes.APPLICATION_M3U8)
                    }.build()
                releaseLocal()

                val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(api.mediaClient())
                    .setUserAgent("HomeStream-Android/1.0")
                val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this@PlayerActivity)
                    .setDataSourceFactory(httpFactory)
                    .experimentalParseSubtitlesDuringExtraction(false)

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()

                player = ExoPlayer.Builder(this@PlayerActivity)
                    .setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(this@PlayerActivity).setEnableDecoderFallback(true))
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                    .build().also { exo ->
                    observeVideoDecoder(exo)
                    playerView.player = object : androidx.media3.common.ForwardingPlayer(exo) {
                        override fun getCurrentTimeline(): androidx.media3.common.Timeline {
                            val base = super.getCurrentTimeline()
                            if (!isTranscodingStream || knownDurationMs <= 0L || base.isEmpty) return base
                            return object : androidx.media3.exoplayer.source.ForwardingTimeline(base) {
                                override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
                                    super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                                    window.durationUs = knownDurationMs * 1000L
                                    window.isDynamic = false
                                    window.isSeekable = true
                                    window.liveConfiguration = null
                                    window.defaultPositionUs = 0L
                                    return window
                                }
                                override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                                    super.getPeriod(periodIndex, period, setIds)
                                    period.durationUs = knownDurationMs * 1000L
                                    return period
                                }
                            }
                        }
                        override fun getDuration(): Long = knownDurationMs.takeIf { it > 0 } ?: super.getDuration()
                        override fun getContentDuration(): Long = knownDurationMs.takeIf { it > 0 } ?: super.getContentDuration()
                        override fun getCurrentPosition(): Long = currentMediaPositionMs()
                        override fun getContentPosition(): Long = currentMediaPositionMs()
                        override fun getBufferedPosition(): Long = (if (isTranscodingStream) hlsStartOffsetMs else 0L) + super.getBufferedPosition().coerceAtLeast(0L)
                        override fun getContentBufferedPosition(): Long = (if (isTranscodingStream) hlsStartOffsetMs else 0L) + super.getContentBufferedPosition().coerceAtLeast(0L)
                        override fun isCurrentMediaItemDynamic(): Boolean = if (isTranscodingStream) false else super.isCurrentMediaItemDynamic()
                        override fun isCurrentMediaItemLive(): Boolean = if (isTranscodingStream) false else super.isCurrentMediaItemLive()
                        override fun isCurrentMediaItemSeekable(): Boolean = if (isTranscodingStream && knownDurationMs > 0L) true else super.isCurrentMediaItemSeekable()
                        override fun seekTo(positionMs: Long) = seekMediaTo(positionMs)
                        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                            if (mediaItemIndex == currentMediaItemIndex) seekMediaTo(positionMs)
                            else super.seekTo(mediaItemIndex, positionMs)
                        }
                        override fun seekBack() = seekMediaTo(currentMediaPositionMs() - seekBackIncrement)
                        override fun seekForward() {
                            val maxDur = duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
                            seekMediaTo((currentMediaPositionMs() + seekForwardIncrement).coerceAtMost(maxDur))
                        }
                    }
                    observeEpisodeNavigation(exo)
                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                prepared = true; clearMessages(); reportStarted()
                                extrasTickerJob?.cancel()
                                extrasTickerJob = lifecycleScope.launch {
                                    var tick = 0
                                    while (player === exo && !isFinishing && !isDestroyed) {
                                        val sec = currentMediaPositionMs() / 1000.0
                                        val seg = skipSegments.firstOrNull { sec >= it.startSec && sec < it.endSec - 1.0 }
                                        if (seg != null) {
                                            skipBtn?.text = when (seg.type) {
                                                "credits" -> "Skip Credits →"
                                                "recap" -> "Skip Recap →"
                                                else -> "Skip Intro →"
                                            }
                                            skipBtn?.setOnClickListener { seekMediaTo((seg.endSec * 1000).toLong()) }
                                            val wasHidden = skipBtn?.visibility != View.VISIBLE
                                            skipBtn?.visibility = View.VISIBLE
                                            if (wasHidden && isTelevision() && !playerView.isControllerFullyVisible) {
                                                skipBtn?.requestFocus()
                                            }
                                        } else {
                                            skipBtn?.visibility = View.GONE
                                        }
                                        val roomCode = syncRoomCode
                                        if (roomCode != null && tick % 2 == 0) {
                                            runCatching {
                                                val r = api.call("syncplay", mapOf("code" to roomCode)).optJSONObject("room")
                                                val activeItemId = currentPlaybackId.ifBlank { media.playbackId }
                                                val roomItemId = r?.optString("itemId").orEmpty()
                                                if (r != null && roomItemId.isNotBlank() && roomItemId == activeItemId) {
                                                    applyingSyncPlay = true
                                                    try {
                                                        val targetMs = r.optLong("positionTicks", 0L) / 10_000L
                                                        if (kotlin.math.abs(currentMediaPositionMs() - targetMs) > 2500L) seekMediaTo(targetMs)
                                                        val paused = r.optBoolean("isPaused", false)
                                                        if (paused && exo.isPlaying) exo.pause() else if (!paused && !exo.isPlaying) exo.play()
                                                    } finally {
                                                        applyingSyncPlay = false
                                                    }
                                                }
                                            }
                                        }
                                        tick++
                                        delay(1000)
                                    }
                                }
                            }
                            if (state == Player.STATE_ENDED) {
                                val totalDur = knownDurationMs.takeIf { it > 0 }
                                    ?: ((if (isTranscodingStream) hlsStartOffsetMs else 0L) + exo.duration.coerceAtLeast(0))
                                position = totalDur; report(true)
                                if (autoplay) changeEpisode(1, automatic = true)
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            val code = syncRoomCode
                            val activeItemId = currentPlaybackId.ifBlank { media.playbackId }
                            if (code != null && !applyingSyncPlay && prepared) {
                                lifecycleScope.launch {
                                    runCatching {
                                        val payload = JSONObject()
                                            .put("action", "sync")
                                            .put("code", code)
                                            .put("itemId", activeItemId)
                                            .put("isPaused", !isPlaying)
                                            .put("positionTicks", msToTicks(currentMediaPositionMs()))
                                        api.call("syncplay", body = payload)
                                    }
                                }
                            }
                        }
                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int
                        ) {
                            val code = syncRoomCode
                            val activeItemId = currentPlaybackId.ifBlank { media.playbackId }
                            if (code != null && !applyingSyncPlay && prepared && reason == Player.DISCONTINUITY_REASON_SEEK) {
                                lifecycleScope.launch {
                                    runCatching {
                                        val absMs = (if (isTranscodingStream) hlsStartOffsetMs else 0L) + newPosition.positionMs.coerceAtLeast(0L)
                                        val payload = JSONObject()
                                            .put("action", "sync")
                                            .put("code", code)
                                            .put("itemId", activeItemId)
                                            .put("isPaused", !exo.isPlaying)
                                            .put("positionTicks", msToTicks(absMs))
                                        api.call("syncplay", body = payload)
                                    }
                                }
                            }
                        }
                        override fun onTracksChanged(tracks: Tracks) {
                            // Detect if media file has audio tracks, but none are supported by hardware/software decoders
                            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                            if (audioGroups.isNotEmpty()) {
                                val anySupported = audioGroups.any { it.isSupported }
                                if (!anySupported && !transcode && !media.isDownload) {
                                    position = currentMediaPositionMs()
                                    wantsPlay = exo.playWhenReady
                                    android.util.Log.w("HomeStream", "Audio format not supported by device decoder. Triggering server transcode fallback.")
                                    transcode = true
                                    updateModeBadge()
                                    showMessage("Audio format not supported by device. Switching to server transcode…")
                                    startLocal()
                                }
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            android.util.Log.e("HomeStream", "Playback error encountered: ${error.errorCodeName} (${error.errorCode})")
                            position = currentMediaPositionMs()
                            if (isStreamingDownload) {
                                retryJob?.cancel()
                                retryJob = lifecycleScope.launch {
                                    delay(2500)
                                    if (playbackStarted && player === exo && !isFinishing && !isDestroyed) {
                                        exo.seekTo(exo.currentPosition)
                                        exo.prepare()
                                        exo.playWhenReady = true
                                    }
                                }
                                return
                            }
                            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                                showUnDownloadedUI("Media file has not been downloaded or is not available on HomeStream.")
                                return
                            }
                            if (!transcode && !media.isDownload) {
                                transcode = true
                                updateModeBadge()
                                showMessage("Playback issue (${error.errorCodeName}). Switching to compatible transcoding…")
                                startLocal()
                                return
                            }
                            clearMessages()
                            showMessage("Playback failed (${error.errorCodeName}). Check that this device can reach HomeStream.")
                            if (media.source == "youtube") {
                                showMessage("In-progress downloads may buffer or be unseekable. Wait for more data and retry.")
                                overlay.addView(action("Retry") { startLocal() })
                            } else overlay.addView(action("Retry with compatible HLS") { transcode = true; updateModeBadge(); startLocal() })
                            overlay.addView(action("←  Back to Browse") { finish() })
                        }
                    })
                    exo.setMediaItem(item)
                    if (!isTranscodingStream && position > 0L) {
                        exo.seekTo(position)
                    }
                    exo.prepare()
                    exo.playWhenReady = wantsPlay
                }
                playerView.showController(); focusPlaybackControl()
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                clearMessages()
                val msg = e.message ?: "Playback could not start."
                if (msg.contains("not been downloaded", ignoreCase = true) ||
                    msg.contains("not available", ignoreCase = true) ||
                    msg.contains("404") || msg.contains("400")) {
                    showUnDownloadedUI(msg)
                } else {
                    showMessage(msg)
                    overlay.addView(action("Retry") { startLocal() })
                    overlay.addView(action("←  Back to Browse") { finish() })
                }
            }
        }
    }
    private suspend fun subtitleItems(targetId: String = "", includeTextAsBurnIn: Boolean = false): List<MediaItem.SubtitleConfiguration> = try {
        val queryId = targetId.ifBlank { if (currentPlaybackId.isNotBlank()) currentPlaybackId else media.playbackId }
        val query = buildMap {
            put("itemId", queryId)
            selectedVersionId?.let { put("versionId", it) }
        }
        api.call("subtitles", query).optJSONArray("subtitles")?.let { tracks ->
            val burnIns = mutableListOf<Pair<Int, String>>()
            val textConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
            for (i in 0 until tracks.length()) {
                val track = tracks.getJSONObject(i)
                val idx = track.optInt("index", -1)
                if (track.optBoolean("burnIn")) {
                    if (idx >= 0) burnIns.add(idx to track.optString("title", "Track $idx"))
                    continue
                }
                if (includeTextAsBurnIn && idx in 0..999) {
                    burnIns.add(idx to track.optString("title", "Track $idx"))
                }
                val src = track.optString("src")
                if (src.isBlank()) continue
                // Never mark external WebVTT as DEFAULT so ExoPlayer never blocks video readiness on subtitle HTTP extraction.
                val flags = if (track.optBoolean("isForced")) C.SELECTION_FLAG_FORCED else 0
                textConfigs.add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(serverMediaUrl(src)))
                        .setMimeType(MimeTypes.TEXT_VTT).setLanguage(track.optString("language"))
                        .setSelectionFlags(flags).setLabel(track.optString("title")).build()
                )
            }
            burnInSubtitles = burnIns
            textConfigs
        }.orEmpty()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
    }
    private fun cast(session: CastSession, forceReload: Boolean = false) {
        setPlaybackPresentation(true)
        if (castLoading) return
        val deviceKey = "cast-profile-${session.castDevice?.deviceId.orEmpty()}"
        val stored = playbackPrefs.getString(deviceKey, null)
        val selected = castProfiles.firstOrNull { it.key == stored }
        // A generic device name does not expose codecs. Probe the original
        // stream, then retry a conservative profile on a receiver decode error.
        receiverProfile = if (castNeedsSafeProfile) castProfiles[2] else selected ?: automaticCastProfile(session.castDevice?.modelName.orEmpty(), castCompatible)
        val client = session.remoteMediaClient ?: return
        val receiverMedia = client.mediaInfo?.customData?.optJSONObject("media")?.let { Media(it) }
        if (!forceReload && receiverMedia != null && receiverMedia.json.optString("seriesId").isNotBlank() &&
            receiverMedia.json.optString("seriesId") == media.json.optString("seriesId") &&
            (media.type == "Series" || receiverMedia.playbackId == media.playbackId)) {
            media = receiverMedia; currentPlaybackId = media.playbackId; updateEpisodeControls(); loadEpisodeContext()
            client.mediaInfo?.customData?.optJSONObject("playback")?.let { describeStream(it) }
        }
        if (!forceReload && client.mediaInfo?.customData?.optString("itemId") == currentPlaybackId &&
            (client.isPlaying || client.isPaused || client.isBuffering)) {
            remote = client; client.registerCallback(remoteCallback)
            position = client.approximateStreamPosition
            releaseLocal(); clearMessages(); showCastControls(); return
        }
        if (!forceReload) {
            position = if (player != null) currentMediaPositionMs() else position
            wantsPlay = player?.playWhenReady ?: wantsPlay
        }
        player?.pause(); negotiation?.cancel(); castLoading = true
        castStartup?.cancel(); castStartup = null; castStartPosition = null
        val generation = ++castGeneration
        negotiation = lifecycleScope.launch {
            try {
                val targetId = resolvePlaybackId()
                currentPlaybackId = targetId
                loadEpisodeContextNow(targetId)
                val current = episodes.items.firstOrNull { it.playbackId == targetId } ?: media
                if (current.type == "Episode") { media = current; updateEpisodeControls() }
                val reportedCapabilities = if (BuildConfig.CAST_RECEIVER_APP_ID.isBlank() || castCompatible || targetId in castRejectedItems || selected != null) null
                    else requestCastVideoCapabilities(session)
                castVideoCapabilities = reportedCapabilities?.video
                castAudioCodecs = reportedCapabilities?.audioCodecs ?: "aac,mp3"
                val info = castMediaInfo(current, targetId)
                castStartPosition = position
                castStartup = lifecycleScope.launch {
                    delay(30_000)
                    if (generation == castGeneration) recoverCast(session)
                }
                val tracks = subtitleIds(info)
                val load = MediaLoadRequestData.Builder()
                    .setMediaInfo(info)
                    .setAutoplay(wantsPlay)
                    .setCurrentTime(position.coerceAtLeast(0))
                    .apply { if (tracks.isNotEmpty()) setActiveTrackIds(tracks) }
                    .build()
                client.load(load).setResultCallback { result ->
                    if (generation != castGeneration || !playbackStarted) return@setResultCallback
                    castLoading = false
                    if (result.status.isSuccess) {
                        remote = client; client.registerCallback(remoteCallback)
                        releaseLocal(); clearMessages(); showCastControls()
                        remoteCallback.onStatusUpdated()
                    } else recoverCast(session)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == castGeneration) recoverCast(session)
            }
        }
    }

    private fun recoverCast(session: CastSession) {
        castStartup?.cancel(); castStartup = null
        castStartPosition?.let { position = it }
        remote?.unregisterCallback(remoteCallback); remote = null
        castLoading = false
        if (session.isConnected && !castCompatible && castRejectedItems.add(currentPlaybackId)) {
            castNeedsSafeProfile = true
            cast(session, forceReload = true)
            return
        }
        ++castGeneration
        castStartPosition = null
        castControllerOpened = false
        Toast.makeText(this, "Chromecast could not start playback. Returning to this device.", Toast.LENGTH_LONG).show()
        if (session.isConnected) castCtx?.sessionManager?.endCurrentSession(true) else returnToLocal()
    }

    private fun chooseCastProfile(session: CastSession) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Cast device model")
            .setItems(castProfiles.map { it.label }.toTypedArray()) { _, index ->
                val client = remote
                if (client == null) player?.let { position = currentMediaPositionMs(); wantsPlay = it.playWhenReady }
                if (client != null) { position = client.approximateStreamPosition; wantsPlay = client.isPlaying; client.unregisterCallback(remoteCallback); remote = null }
                receiverProfile = castProfiles[index]
                castNeedsSafeProfile = false; castRejectedItems.clear()
                playbackPrefs.edit().putString("cast-profile-${session.castDevice?.deviceId.orEmpty()}", receiverProfile.key).apply()
                negotiation?.cancel(); castLoading = false
                cast(session, forceReload = true)
            }.setNegativeButton("Cancel") { _, _ -> if (remote == null) startLocal() }.show()
    }

    private fun subtitleIds(info: MediaInfo): LongArray {
        val language = playbackPrefs.getString("cast-subtitle-language", "_off")
        if (language == "_off") return longArrayOf()
        return info.mediaTracks.orEmpty().firstOrNull { it.type == MediaTrack.TYPE_TEXT && (it.language ?: "und") == language }
            ?.let { longArrayOf(it.id) } ?: longArrayOf()
    }

    private fun applyInitialResume(stream: JSONObject) {
        if (stream.optLong("totalDurationTicks") > 0) knownDurationMs = ticksToMs(stream.optLong("totalDurationTicks"))
        if (!refreshInitialResume) return
        refreshInitialResume = false
        if (stream.has("resumePositionTicks")) position = ticksToMs(stream.optLong("resumePositionTicks")).coerceAtLeast(0)
    }

    private suspend fun castMediaInfo(item: Media, id: String): MediaInfo {
        val compatible = castCompatible || id in castRejectedItems
        val isTv = intent.getStringExtra("mediaType") == "tv" || item.type == "Series" || item.type == "Episode"
        val sNum = intent.getIntExtra("season", 0).takeIf { it > 0 } ?: item.json.optInt("seasonNumber", 1).coerceAtLeast(1)
        val epNum = intent.getIntExtra("episode", 0).takeIf { it > 0 } ?: item.json.optInt("episodeNumber", 1).coerceAtLeast(1)
        val sTitle = intent.getStringExtra("seriesTitle")?.ifBlank { null } ?: item.title
        val tmdb = intent.getIntExtra("tmdbId", 0).takeIf { it > 0 } ?: if (item.tmdbId > 0) item.tmdbId else {
            val match = Regex("""^(?:seerr|tmdb)-(?:movie-|tv-|ep-)?(\d+)(?:-\d+-\d+)?$""").find(item.id)
            match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        val params = mutableMapOf(
            "itemId" to if (isStreamingDownload && tmdb > 0) "tmdb-${if (isTv) "tv" else "movie"}-$tmdb" else id,
            "forceTranscode" to compatible.toString(),
            "receiver" to "cast",
            "canPlayHevc" to receiverProfile.hevc.toString(),
            "maxWidth" to receiverProfile.width.toString(),
            "maxHeight" to receiverProfile.height.toString(),
            "audioCodecs" to castAudioCodecs
        )
        if (castVideoCapabilities != null) {
            params.remove("maxWidth")
            params.remove("maxHeight")
            params["videoCapabilities"] = castVideoCapabilities!!
        }
        if (castVideoCapabilities == null) {
            val profiles = org.json.JSONArray().put(org.json.JSONObject().put("codec", "h264")
                .put("width", receiverProfile.width).put("height", receiverProfile.height)
                .put("profiles", org.json.JSONArray(listOf("baseline", "main", "high"))))
            if (receiverProfile.hevc) profiles.put(org.json.JSONObject().put("codec", "hevc")
                .put("width", receiverProfile.width).put("height", receiverProfile.height)
                .put("profiles", org.json.JSONArray(listOf("main", "main 10"))))
            params["videoCapabilities"] = profiles.toString()
        }
        selectedVersionId?.takeIf { it.isNotBlank() }?.let { params["versionId"] = it }
        selectedAudioIndex?.let { params["audioIndex"] = it.toString() }
        burnSubtitleIndex?.let { params["burnSubtitleIndex"] = it.toString() }
        if (tmdb > 0) params["tmdbId"] = tmdb.toString()
        params["type"] = if (isTv) "tv" else "movie"
        if (isTv) {
            params["season"] = sNum.toString()
            params["episode"] = epNum.toString()
            if (sTitle.isNotBlank()) params["title"] = sTitle
        }

        val stream = api.call("playback", params)
        if (id == currentPlaybackId) { applyInitialResume(stream); describeStream(stream); transcode = stream.optBoolean("isTranscoding"); updateModeBadge() }
        val metadata = MediaMetadata(if (item.type == "Episode") MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            val series = item.json.optString("seriesName").ifBlank { item.json.optString("seriesTitle") }
            if (series.isNotBlank()) putString(MediaMetadata.KEY_SERIES_TITLE, series)
            val posterUrl = item.backdrop.ifBlank { item.poster }
            if (posterUrl.isNotBlank()) addImage(WebImage(Uri.parse(api.absolute(posterUrl))))
        }
        val tracks = subtitleItems(id).mapIndexed { index, track -> MediaTrack.Builder((index + 1).toLong(), MediaTrack.TYPE_TEXT)
            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES).setContentId(track.uri.toString()).setContentType("text/vtt")
            .setName(track.label ?: "Subtitle").setLanguage(track.language ?: "und").build() }
        val durationSec = stream.optDouble("durationSeconds").takeIf { it > 0 }
            ?: (item.json.optInt("durationMinutes").takeIf { it > 0 } ?: 120) * 60.0
        return MediaInfo.Builder(serverMediaUrl(stream.getString("streamUrl")))
            .setContentType(stream.optString("contentType").ifBlank { when {
                Uri.parse(stream.getString("streamUrl")).path.orEmpty().endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                Uri.parse(stream.getString("streamUrl")).path.orEmpty().endsWith(".webm", ignoreCase = true) -> "video/webm"
                else -> "video/mp4"
            } })
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(metadata).setMediaTracks(tracks)
            .setCustomData(JSONObject().put("itemId", id)
                .put("streamItemId", if (tmdb > 0 && (isStreamingDownload || id.startsWith("tmdb-") || id.startsWith("seerr-"))) {
                    if (isTv) "tmdb-ep-$tmdb-$sNum-$epNum" else "tmdb-movie-$tmdb"
                } else id).put("loadId", java.util.UUID.randomUUID().toString()).put("media", item.json).put("playback", JSONObject(stream.toString()).apply {
                put("transport", stream.optString("transport").ifBlank { if (Uri.parse(stream.optString("streamUrl")).path.orEmpty().endsWith(".m3u8", ignoreCase = true)) "HLS" else "file" })
                put("durationSeconds", durationSec)
                remove("streamUrl")
            }))
            .apply { if (durationSec > 0) setStreamDuration((durationSec * 1000).toLong()) }.build()
    }

    private suspend fun loadEpisodeContextNow(id: String = currentPlaybackId) {
        if (media.type !in listOf("Episode", "Series", "Season")) return
        if (episodes.index(id) >= 0) return
        try {
            val result = EpisodeQueue(api.call("episodes", mapOf("itemId" to id)).mediaList("items"))
            if (currentPlaybackId != id) return
            episodes = result
            result.items.firstOrNull { it.playbackId == id }?.let { media = it }
            updateEpisodeControls()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    private fun loadEpisodeContext() {
        episodeJob?.cancel()
        episodeJob = lifecycleScope.launch { loadEpisodeContextNow() }
    }

    // Media3 updates these buttons for its single-item timeline. Restore episode
    // availability after those updates while keeping its native transport layout.
    private fun observeEpisodeNavigation(exo: ExoPlayer) {
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playerView.post { updateEpisodeNavigation() }
            }
        })
        updateEpisodeNavigation()
    }

    private fun updateEpisodeNavigation() {
        val neighbors = listOf(
            androidx.media3.ui.R.id.exo_prev to episodes.previous(currentPlaybackId),
            androidx.media3.ui.R.id.exo_next to episodes.next(currentPlaybackId)
        )
        for ((id, episode) in neighbors) {
            playerView.findViewById<View>(id)?.apply {
                isEnabled = episode != null
                alpha = if (isEnabled) 1f else 0.3f
            }
        }
    }

    private fun updateEpisodeControls() {
        updateEpisodeNavigation()
        val hasQueue = episodes.items.isNotEmpty()
        queueBtn?.apply {
            visibility = if (hasQueue) View.VISIBLE else View.GONE
            contentDescription = if (media.source == "youtube") "Mix Playlist" else "Episodes"
        }
        titleColumn?.apply {
            removeAllViews()
            val series = media.json.optString("seriesName").ifBlank { media.json.optString("seriesTitle") }
            if (series.isNotBlank()) addView(label(series, 12f))
            val prefix = if (media.type == "Episode") "S${media.json.optInt("seasonNumber", 1)} E${media.json.optInt("episodeNumber", 1)} · " else ""
            addView(label(prefix + media.title, 16f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
    }

    private fun changeEpisode(direction: Int, automatic: Boolean = false) {
        lifecycleScope.launch {
            loadEpisodeContextNow()
            val next = if (direction > 0) episodes.next(currentPlaybackId) else episodes.previous(currentPlaybackId)
            if (next == null) {
                if (!automatic) Toast.makeText(this@PlayerActivity, "No further available episode.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            selectEpisode(next)
        }
    }

    private fun selectEpisode(item: Media) {
        report(true)
        val client = remote
        val queued = client?.mediaStatus?.queueItems?.firstOrNull { it.media?.customData?.optString("itemId") == item.playbackId }
        if (queued != null) {
            client.queueJumpToItem(queued.itemId, 0, null)
            return
        }
        negotiation?.cancel(); castLoading = false
        selectedVersionId = null
        selectedAudioIndex = null
        burnSubtitleIndex = null
        burnInSubtitles = emptyList()
        media = item; currentPlaybackId = item.playbackId; position = 0; wantsPlay = true
        updateEpisodeControls()
        val session = castCtx?.sessionManager?.currentCastSession
        if (client != null && session?.isConnected == true) {
            client.unregisterCallback(remoteCallback); remote = null
            cast(session, forceReload = true)
        } else { releaseLocal(); startLocal() }
    }

    private fun showEpisodeQueue() {
        if (episodes.items.isEmpty()) { Toast.makeText(this, "No queue available.", Toast.LENGTH_SHORT).show(); return }
        val isYouTube = media.source == "youtube"
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen).apply {
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x66000000))
        }
        val backdrop = FrameLayout(this).apply {
            setOnClickListener { dialog.dismiss() }
        }
        val panelWidth = dp(430).coerceAtMost((resources.displayMetrics.widthPixels * 0.90f).toInt())
        val panelHeight = (resources.displayMetrics.heightPixels * 0.84f).toInt()
        val root = column().apply {
            background = rounded(0xEB141414.toInt(), stroke = 0x33FFFFFF, radius = dp(14).toFloat(), strokeWidth = dp(1))
            elevation = dp(16).toFloat()
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isClickable = true
        }
        backdrop.addView(root, FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.CENTER or Gravity.END).apply {
            marginEnd = dp(16)
        })

        // Netflix-style header bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        header.addView(label(if (isYouTube) "Mix Playlist" else "Episodes", 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val closeBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = rounded(0x28FFFFFF, radius = dp(18).toFloat())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true; isFocusable = true
            contentDescription = "Close"
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        root.addView(header)

        // Season pills for multi-season series
        val seasonsList = if (isYouTube) listOf(1) else episodes.items.map { it.json.optInt("seasonNumber", 1) }.distinct().sorted()
        val currentSeason = if (isYouTube) 1 else media.json.optInt("seasonNumber", seasonsList.firstOrNull() ?: 1)
        var selectedSeason = if (currentSeason in seasonsList) currentSeason else (seasonsList.firstOrNull() ?: 1)

        val seasonPills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(12)) }
        val listHost = column()

        fun renderList() {
            if (!isYouTube && seasonsList.size > 1) {
                seasonPills.removeAllViews()
                seasonsList.forEach { sNum ->
                    val active = sNum == selectedSeason
                    seasonPills.addView(TextView(this).apply {
                        text = "Season $sNum"
                        textSize = 12.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(if (active) Color.BLACK else Color.WHITE)
                        background = rounded(if (active) Color.WHITE else 0x26FFFFFF, radius = dp(16).toFloat())
                        setPadding(dp(14), dp(7), dp(14), dp(7))
                        isClickable = true; isFocusable = true
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                        setOnClickListener { selectedSeason = sNum; renderList() }
                    })
                }
            }

            listHost.removeAllViews()
            val visibleItems = if (isYouTube) episodes.items else episodes.items.filter { it.json.optInt("seasonNumber", 1) == selectedSeason }
            visibleItems.forEachIndexed { idx, ep ->
                val isCurrent = ep.playbackId == currentPlaybackId
                val card = column().apply {
                    focusableCard(if (isCurrent) 0xFF221214.toInt() else NETFLIX_SURFACE)
                    if (isCurrent) {
                        background = rounded(0xFF221214.toInt(), stroke = RED, radius = dp(8).toFloat(), strokeWidth = dp(1))
                    }
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                    setOnClickListener {
                        dialog.dismiss()
                        selectEpisode(ep)
                    }
                }

                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val thumbBox = FrameLayout(this).apply {
                    background = rounded(0xFF222222.toInt(), radius = dp(6).toFloat())
                    clipToOutline = true
                }
                val thumbImg = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val art = ep.poster.ifBlank { ep.backdrop }.ifBlank { media.backdrop }
                    coil.Coil.imageLoader(this@PlayerActivity).enqueue(
                        coil.request.ImageRequest.Builder(this@PlayerActivity)
                            .data(api.absolute(art))
                            .target(this)
                            .error(R.drawable.ic_homestream)
                            .crossfade(true)
                            .build()
                    )
                }
                thumbBox.addView(thumbImg, FrameLayout.LayoutParams(-1, -1))
                thumbBox.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_play_arrow)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (isCurrent) RED else 0x99000000.toInt())
                        setStroke(dp(1), 0xAAFFFFFF.toInt())
                    }
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))

                val total = ep.json.optLong("totalDurationTicks")
                val percent = if (ep.json.optBoolean("played")) 100 else if (total > 0)
                    ((ep.json.optLong("playbackPositionTicks").toDouble() / total) * 100).toInt().coerceIn(0, 100)
                else ep.json.optInt("progressPercent").coerceIn(0, 100)
                if (percent > 0) {
                    thumbBox.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; progress = percent
                        progressTintList = ColorStateList.valueOf(RED)
                        progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
                    }, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
                }
                row.addView(thumbBox, LinearLayout.LayoutParams(dp(120), dp(68)))

                val info = column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
                }
                val prefix = if (isYouTube) "${idx + 1}. " else "${ep.json.optInt("episodeNumber", idx + 1)}. "
                info.addView(TextView(this).apply {
                    text = prefix + ep.title
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(if (isCurrent) Color.WHITE else 0xFFE5E5E5.toInt())
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val subText = if (isYouTube) {
                    ep.json.optString("channelTitle").ifBlank { ep.json.optString("seriesName") }
                } else {
                    val mins = ep.json.optInt("durationMinutes").takeIf { it > 0 }
                    if (mins != null) "${mins}m" else ""
                }
                if (subText.isNotBlank()) {
                    info.addView(TextView(this).apply {
                        text = if (isCurrent) "$subText • Playing" else subText
                        textSize = 11.5f
                        setTextColor(if (isCurrent) RED else MUTED)
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                if (!isYouTube && ep.overview.isNotBlank()) {
                    info.addView(TextView(this).apply {
                        text = ep.overview
                        textSize = 11.5f; setTextColor(MUTED)
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    })
                }
                row.addView(info)
                card.addView(row)
                listHost.addView(card)
            }
        }

        if (!isYouTube && seasonsList.size > 1) {
            root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(seasonPills) })
        }
        root.addView(android.widget.ScrollView(this).apply { addView(listHost) }, LinearLayout.LayoutParams(-1, 0, 1f))
        renderList()
        dialog.setContentView(backdrop)
        dialog.show()
    }

    private fun seekWithRemote(offsetMs: Long) {
        val exo = player ?: return
        if (!exo.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val duration = knownDurationMs.takeIf { it > 0 } ?: exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        seekMediaTo((currentMediaPositionMs() + offsetMs).coerceIn(0L, duration))
    }

    private fun configureRemoteControls() {
        val controls = listOf(androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_prev,
            androidx.media3.ui.R.id.exo_next, androidx.media3.ui.R.id.exo_rew,
            androidx.media3.ui.R.id.exo_ffwd, androidx.media3.ui.R.id.exo_settings,
            androidx.media3.ui.R.id.exo_subtitle)
        val controlViews = controls.mapNotNull { playerView.findViewById<View>(it) } + listOfNotNull(syncPlayButton, audioButton)
        for (view in controlViews) {
            view.apply {
                background = android.graphics.drawable.StateListDrawable().apply {
                    fun highlight(color: Int) = GradientDrawable().apply {
                        setColor(color); cornerRadius = dp(8).toFloat()
                        setStroke(dp(2), Color.WHITE)
                    }
                    addState(intArrayOf(android.R.attr.state_pressed), highlight(0x99E50914.toInt()))
                    addState(intArrayOf(android.R.attr.state_focused), highlight(0x55FFFFFF))
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                }
            }
        }
    }

    private fun focusPlaybackControl() {
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
    }

    private fun parseNativePlayerExtras(stream: JSONObject) {
        val segs = mutableListOf<SkipSeg>()
        stream.optJSONArray("skipSegments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                segs.add(SkipSeg(o.optString("type", "intro"), o.optDouble("startSeconds", 0.0), o.optDouble("endSeconds", 0.0)))
            }
        }
        skipSegments = segs

        val vers = mutableListOf<Pair<String, String>>()
        stream.optJSONArray("versions")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { version ->
                    vers.add(version.optString("id") to version.optString("label", "Version ${i + 1}"))
                }
            }
        }
        nativeVersions = vers
        val audio = stream.optJSONArray("audioTracks")
        nativeAudioTracks = (0 until (audio?.length() ?: 0)).mapNotNull { index ->
            val track = audio?.optJSONObject(index) ?: return@mapNotNull null
            val streamIndex = track.optInt("index", -1)
            if (streamIndex < 0) null else streamIndex to track.optString("title", "Audio ${index + 1}")
        }

        val tpUrl = stream.optString("trickplayUrl")
        trickplayJob?.cancel()
        trickplayCues = emptyList(); trickplaySprites = emptyMap()
        trickplayPreview?.visibility = View.GONE
        if (tpUrl.isNotBlank()) {
            trickplayJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val req = okhttp3.Request.Builder().url(serverMediaUrl(tpUrl)).build()
                    val vttText = api.mediaClient().newCall(req).execute().use { it.body?.string().orEmpty() }
                    val lines = vttText.lines()
                    val cues = mutableListOf<TrickplayCue>()
                    val sheetUrls = linkedSetOf<String>()
                    fun parseTs(ts: String): Double {
                        val p = ts.trim().split(":").mapNotNull { it.toDoubleOrNull() }
                        return if (p.size == 3) p[0] * 3600 + p[1] * 60 + p[2] else if (p.size == 2) p[0] * 60 + p[1] else 0.0
                    }
                    val tsRe = Regex("""^(\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s*-->\s*(\d{2}:\d{2}:\d{2}(?:\.\d+)?)""")
                    val xyRe = Regex("""#xywh=(\d+),(\d+),(\d+),(\d+)""")
                    for (i in lines.indices) {
                        val m = tsRe.find(lines[i]) ?: continue
                        val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                        val xy = xyRe.find(next) ?: continue
                        val sheetUrl = next.substringBefore("#")
                        if (sheetUrl.isNotBlank()) sheetUrls.add(sheetUrl)
                        cues.add(TrickplayCue(parseTs(m.groupValues[1]), parseTs(m.groupValues[2]), xy.groupValues[1].toInt(), xy.groupValues[2].toInt(), xy.groupValues[3].toInt(), xy.groupValues[4].toInt(), sheetUrl))
                    }
                    if (sheetUrls.isNotEmpty() && cues.isNotEmpty()) {
                        val loaded = mutableMapOf<String, android.graphics.Bitmap>()
                        for (sheet in sheetUrls) {
                            val sReq = okhttp3.Request.Builder().url(serverMediaUrl(sheet)).build()
                            val bmp = api.mediaClient().newCall(sReq).execute().use { r ->
                                r.body?.byteStream()?.let { android.graphics.BitmapFactory.decodeStream(it) }
                            }
                            if (bmp != null) loaded[sheet] = bmp
                        }
                        if (loaded.isNotEmpty()) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { trickplayCues = cues; trickplaySprites = loaded }
                        }
                    }
                }
            }
        }
    }

    private fun showTrickplayAt(positionMs: Long) {
        val sec = positionMs / 1000.0
        val cue = trickplayCues.firstOrNull { sec >= it.startSec && sec < it.endSec } ?: return
        val sprite = trickplaySprites[cue.sheetUrl] ?: return
        if (cue.x + cue.w <= sprite.width && cue.y + cue.h <= sprite.height) {
            val crop = android.graphics.Bitmap.createBitmap(sprite, cue.x, cue.y, cue.w, cue.h)
            trickplayPreview?.setImageBitmap(crop)
            trickplayPreview?.visibility = View.VISIBLE
        }
    }

    private fun showAudioMenu() {
        if (nativeAudioTracks.size > 1) {
            val selected = nativeAudioTracks.indexOfFirst { it.first == selectedAudioIndex } + 1
            val labels = listOf("Auto") + nativeAudioTracks.map { it.second }
            showPlayerChoices(playerView, labels, selected) { choice ->
                position = currentMediaPositionMs()
                selectedAudioIndex = nativeAudioTracks.getOrNull(choice - 1)?.first
                startLocal()
            }
            return
        }
        val exo = player ?: return
        val tracks = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
        val names = androidx.media3.ui.DefaultTrackNameProvider(resources)
        val selected = tracks.indexOfFirst { (group, index) -> group.isTrackSelected(index) } + 1
        showPlayerChoices(playerView, listOf("Auto") + tracks.map { (group, index) ->
            names.getTrackName(group.getTrackFormat(index))
        }, selected) { choice ->
            val parameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            if (choice > 0) {
                val (group, index) = tracks[choice - 1]
                parameters.addOverride(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index))
            }
            exo.trackSelectionParameters = parameters.build()
        }
    }

    private fun showSubtitlesMenu() {
        val exo = player
        val textTracks = exo?.currentTracks?.groups
            ?.filter { it.type == C.TRACK_TYPE_TEXT }
            ?.flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
            .orEmpty()
        val names = androidx.media3.ui.DefaultTrackNameProvider(resources)
        val selectedTextIdx = if (burnSubtitleIndex != null) -1 else textTracks.indexOfFirst { (group, index) -> group.isTrackSelected(index) }
        val labels = mutableListOf("Off")
        labels.addAll(textTracks.map { (group, index) -> names.getTrackName(group.getTrackFormat(index)) })
        val burnOptionIndex = if (burnInSubtitles.isNotEmpty()) {
            labels.add("Subtitles (Burn-in)")
            labels.lastIndex
        } else -1
        labels.add("Subtitle appearance")
        val appearanceOptionIndex = labels.lastIndex
        val selectedRow = if (selectedTextIdx >= 0) selectedTextIdx + 1 else if (burnSubtitleIndex == null) 0 else -1

        showPlayerChoices(playerView, labels, selectedRow) { choice ->
            when {
                choice == 0 -> {
                    if (burnSubtitleIndex != null) {
                        position = currentMediaPositionMs()
                        burnSubtitleIndex = null
                        startLocal()
                    } else if (exo != null) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .build()
                    }
                }
                choice in 1..textTracks.size -> {
                    val (group, index) = textTracks[choice - 1]
                    if (burnSubtitleIndex != null) {
                        position = currentMediaPositionMs()
                        burnSubtitleIndex = null
                        startLocal()
                    } else if (exo != null) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .addOverride(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index))
                            .build()
                    }
                }
                choice == burnOptionIndex -> {
                    val selected = burnInSubtitles.indexOfFirst { it.first == burnSubtitleIndex } + 1
                    val burnLabels = listOf("Off") + burnInSubtitles.map { it.second }
                    showPlayerChoices(playerView, burnLabels, selected) { burnChoice ->
                        position = currentMediaPositionMs()
                        burnSubtitleIndex = burnInSubtitles.getOrNull(burnChoice - 1)?.first
                        startLocal()
                    }
                }
                choice == appearanceOptionIndex -> subtitleAppearance.show(this, playerView)
            }
        }
    }

    private fun showPlayerSettings() {
        val options = mutableListOf("Playback speed", "Quality ($qualityMode)")
        if (nativeVersions.size > 1) {
            options.add("Video version")
        }
        if (!media.isDownload) {
            options.add("Playback mode (${if (transcode) "Transcode" else "Direct"})")
            options.add("Playback details")
        }
        options.add("Restart")
        showPlayerChoices(playerView, options) { choice ->
            when (options.getOrNull(choice)) {
                "Playback speed" -> {
                    val exo = player ?: return@showPlayerChoices
                    val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    val selected = speeds.indexOfFirst { kotlin.math.abs(it - exo.playbackParameters.speed) < 0.01f }
                    showPlayerChoices(playerView, speeds.map { "${it}×" }, selected) { index ->
                        exo.setPlaybackSpeed(speeds[index])
                    }
                }
                "Quality ($qualityMode)" -> {
                    val qLabels = listOf("Auto", "Original", "1080p", "720p")
                    val qVals = listOf("auto", "original", "1080p", "720p")
                    val sel = qVals.indexOf(qualityMode).coerceAtLeast(0)
                    showPlayerChoices(playerView, qLabels, sel) { idx ->
                        position = currentMediaPositionMs()
                        qualityMode = qVals[idx]
                        startLocal()
                    }
                }
                "Video version" -> {
                    val labels = nativeVersions.map { it.second }
                    val sel = nativeVersions.indexOfFirst { it.first == selectedVersionId }.coerceAtLeast(0)
                    showPlayerChoices(playerView, labels, sel) { idx ->
                        position = currentMediaPositionMs()
                        selectedVersionId = nativeVersions[idx].first
                        selectedAudioIndex = null
                        startLocal()
                    }
                }
                "Playback mode (Transcode)", "Playback mode (Direct)" -> {
                    player?.let { position = currentMediaPositionMs(); wantsPlay = it.playWhenReady }
                    transcode = !transcode
                    updateModeBadge()
                    showMessage(if (transcode) "Switching to compatible HLS transcode…" else "Switching to direct play…")
                    startLocal()
                }
                "Playback details" -> {
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Playback details")
                        .setMessage(playbackDetails).setPositiveButton("Close", null).show()
                }
                "Restart" -> {
                    seekMediaTo(0L)
                    player?.play()
                }
            }
        }
    }

    private fun showSyncPlayDialog() {
                    val input = android.widget.EditText(this).apply { hint = "Room code (leave empty to create)" }
                    val msg = if (syncRoomCode != null) "Connected to room: $syncRoomCode" else "Create a new room or enter a room code to join."
                    val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("SyncPlay")
                        .setMessage(msg)
                        .setView(input)
                        .setPositiveButton(if (syncRoomCode != null) "Push Sync" else "Create / Join") { _, _ ->
                            val code = input.text.toString().trim().uppercase()
                            lifecycleScope.launch {
                                runCatching {
                                    if (syncRoomCode != null && code.isBlank()) {
                                        val activeItemId = currentPlaybackId.ifBlank { media.playbackId }
                                        val payload = JSONObject()
                                            .put("action", "sync")
                                            .put("code", syncRoomCode)
                                            .put("itemId", activeItemId)
                                            .put("isPaused", player?.isPlaying != true)
                                            .put("positionTicks", msToTicks(currentMediaPositionMs()))
                                        api.call("syncplay", body = payload)
                                        Toast.makeText(this@PlayerActivity, "Synced room $syncRoomCode", Toast.LENGTH_SHORT).show()
                                    } else if (code.isNotBlank()) {
                                        val activeItemId = currentPlaybackId.ifBlank { media.playbackId }
                                        val r = api.call(
                                            "syncplay",
                                            body = JSONObject().put("action", "join").put("code", code).put("itemId", activeItemId)
                                        )
                                        syncRoomCode = r.optJSONObject("room")?.optString("code")
                                        Toast.makeText(this@PlayerActivity, "Joined SyncPlay room $syncRoomCode", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val payload = JSONObject()
                                            .put("action", "create")
                                            .put("itemId", currentPlaybackId)
                                            .put("title", media.title)
                                            .put("isPaused", player?.isPlaying != true)
                                            .put("positionTicks", msToTicks(currentMediaPositionMs()))
                                        val r = api.call("syncplay", body = payload)
                                        syncRoomCode = r.optJSONObject("room")?.optString("code")
                                        Toast.makeText(this@PlayerActivity, "Created SyncPlay room $syncRoomCode", Toast.LENGTH_SHORT).show()
                                    }
                                }.onFailure { Toast.makeText(this@PlayerActivity, it.message ?: "SyncPlay error", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    if (syncRoomCode != null) {
                        builder.setNegativeButton("Leave") { _, _ ->
                            val c = syncRoomCode ?: return@setNegativeButton
                            syncRoomCode = null
                            lifecycleScope.launch { runCatching { api.call("syncplay", body = JSONObject().put("action", "leave").put("code", c)) } }
                        }
                    } else {
                        builder.setNegativeButton("Cancel", null)
                    }
                    builder.show()
    }

    private fun showCastSubtitles() {
        val client = remote
        if (client == null) {
            playerView.showController()
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.performClick()
            return
        }
        val tracks = client.mediaInfo?.mediaTracks.orEmpty().filter { it.type == MediaTrack.TYPE_TEXT }
        val labels = listOf("Off") + tracks.map { it.name ?: it.language ?: "Subtitle" }
        val active = tracks.indexOfFirst { client.mediaStatus?.activeTrackIds?.contains(it.id) == true } + 1
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Subtitles")
            .setSingleChoiceItems(labels.toTypedArray(), active) { dialog, index ->
                playbackPrefs.edit().putString("cast-subtitle-language", if (index == 0) "_off" else tracks[index - 1].language ?: "und").apply()
                client.setActiveMediaTracks(if (index == 0) longArrayOf() else longArrayOf(tracks[index - 1].id))
                val updates = client.mediaStatus?.queueItems.orEmpty().map { queued ->
                    MediaQueueItem.Builder(queued).setActiveTrackIds(queued.media?.let { subtitleIds(it) } ?: longArrayOf()).build()
                }
                if (updates.isNotEmpty()) client.queueUpdateItems(updates.toTypedArray(), null)
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showCastControls() {
        clearMessages()
        openCastController()
        reportStarted()
    }

    private fun openCastController() {
        if (remote == null || castControllerOpened) return
        castControllerOpened = true
        startActivity(android.content.Intent(this, CastControllerActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun returnToLocal() {
        castStartup?.cancel(); castStartup = null; castStartPosition = null
        ++castGeneration
        castLoading = false
        remote?.unregisterCallback(remoteCallback)
        remote = null
        clearMessages()
        startLocal()
    }
    private fun reportStarted() {
        if (reportedStartedId == currentPlaybackId) return
        reportedStartedId = currentPlaybackId
        report(false, started = true)
    }

    private fun report(stopped: Boolean, started: Boolean = false) {
        if (media.source == "youtube") {
            val videoId = media.json.optString("videoId").ifBlank { media.id.removePrefix("yt-archive-").removePrefix("yt-") }
            if (player != null) position = currentMediaPositionMs()
            val snapshot = position
            val durMs = knownDurationMs.takeIf { it > 0 } ?: player?.duration?.takeIf { it > 0 } ?: 0L
            val posSec = (snapshot / 1000).toInt()
            val durSec = (durMs / 1000).toInt()
            val channel = media.json.optString("channelTitle").ifBlank { media.json.optString("channelName") }
            (application as HomeStreamApplication).enqueuePlaybackReport {
                runCatching {
                    api.call("youtube/progress", body = JSONObject().apply {
                        put("videoId", videoId)
                        put("positionSeconds", posSec)
                        put("durationSeconds", durSec)
                        put("completed", durSec > 0 && posSec >= durSec - 15)
                        put("title", media.title)
                        put("channelName", channel)
                        put("channelTitle", channel)
                        put("thumbnailUrl", media.poster)
                    })
                }
            }
            return
        }
        if (remote != null) return
        val client = remote
        val activeId = if (currentPlaybackId.isNotBlank()) currentPlaybackId else media.playbackId
        if (client != null && client.mediaInfo?.customData?.optString("itemId") != activeId) return
        if (client != null) position = client.approximateStreamPosition else if (player != null) position = currentMediaPositionMs()
        val snapshot = position
        val recordedAt = System.currentTimeMillis()
        val paused = client?.isPaused ?: (player?.isPlaying == false)
        val launcherItem = episodes.items.firstOrNull { it.playbackId == activeId } ?: media
        val launcherMedia = Media(JSONObject(launcherItem.json.toString()).put("id", activeId).put("jellyfinId", activeId))
        val launcherDuration = knownDurationMs.takeIf { it > 0 } ?: player?.duration?.takeIf { it > 0 }
            ?: ticksToMs(launcherMedia.json.optLong("totalDurationTicks"))
        val launcherScope = if (isTelevision()) WatchNext.scope(ServerSettings(this)) else ""
        // A final report must survive Activity destruction (Back/rotation).
        (application as HomeStreamApplication).enqueuePlaybackReport {
            if (launcherScope.isNotBlank()) runCatching { WatchNext.record(applicationContext, launcherMedia, snapshot, launcherDuration, launcherScope) }
            runCatching { api.progress(activeId, snapshot, paused, stopped, started, recordedAt) }.onSuccess {
                if (stopped) (application as HomeStreamApplication).playbackProgressSaved.tryEmit(Unit)
            }.onFailure {
                OfflineMediaStore(applicationContext, ServerSettings(applicationContext)).queueOfflineProgress(activeId, snapshot)
            }
        }
    }
    private fun releaseLocal() {
        retryJob?.cancel(); retryJob = null
        playerView.player = null
        player?.release()
        player = null
        prepared = false
        reportedStartedId = ""
    }
    private fun clearMessages() { while (overlay.childCount > 1) overlay.removeViewAt(1) }
    private fun showMessage(text: String) { overlay.visibility = View.VISIBLE; overlay.addView(label(text, 14f)) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("media", media.json.toString()); outState.putLong("position", if (player != null) currentMediaPositionMs() else position); super.onSaveInstanceState(outState) }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
        }
    }
    private val videoRemoteHandler: (String, Long?, Long?) -> Boolean = { type, positionMs, deltaMs ->
        val exo = player
        if (exo == null) false
        else when (type) {
            "play" -> { exo.play(); reportVideoSession(); true }
            "pause" -> { exo.pause(); reportVideoSession(); true }
            "togglePlayPause" -> { if (exo.isPlaying) exo.pause() else exo.play(); reportVideoSession(); true }
            "stop" -> { finish(); true }
            "seekTo" -> if (positionMs != null) { seekMediaTo(positionMs); reportVideoSession(); true } else false
            "seekBy" -> if (deltaMs != null) {
                val target = (currentMediaPositionMs() + deltaMs).coerceAtLeast(0L)
                val maxDur = knownDurationMs.takeIf { it > 0 } ?: exo.duration
                seekMediaTo(if (maxDur > 0) target.coerceAtMost(maxDur) else target)
                reportVideoSession()
                true
            } else false
            "next" -> {
                val next = episodes.next(currentPlaybackId)
                if (next != null) { selectEpisode(next); true } else false
            }
            "previous" -> {
                if (currentMediaPositionMs() > 5000L) {
                    seekMediaTo(0L); reportVideoSession(); true
                } else {
                    val prev = episodes.previous(currentPlaybackId)
                    if (prev != null) { selectEpisode(prev); true } else { seekMediaTo(0L); true }
                }
            }
            else -> false
        }
    }

    private fun reportVideoSession() {
        val exo = player ?: return
        val currentEp = episodes.items.firstOrNull { it.playbackId == currentPlaybackId }
        val epLabel = currentEp?.let { ep ->
            val s = ep.json.optInt("seasonNumber", 1)
            val e = ep.json.optInt("episodeNumber", 1)
            "S$s E$e${if (ep.title.isNotBlank()) " · ${ep.title}" else ""}"
        }.orEmpty()
        val seriesTitle = media.json.optString("seriesName").ifBlank { media.title }
        val art = media.backdrop.ifBlank { media.poster }
        val effectivePosMs = currentMediaPositionMs()
        val effectiveDurMs = knownDurationMs.takeIf { it > 0L } ?: exo.duration.coerceAtLeast(0L)
        PlaybackSessions.reportHeartbeatAsync(
            context = this,
            mediaId = currentPlaybackId.ifBlank { media.id },
            mediaType = "video",
            title = seriesTitle,
            subtitle = epLabel.ifBlank { media.metadata },
            artworkUrl = if (art.isNotBlank()) api.absolute(art) else "",
            isPlaying = exo.isPlaying,
            positionMs = effectivePosMs,
            durationMs = effectiveDurMs,
            canSeek = (effectiveDurMs > 0L),
            canSkipNext = episodes.next(currentPlaybackId) != null,
            canSkipPrev = episodes.previous(currentPlaybackId) != null,
        )
    }

    override fun onStop() {
        PlaybackSessions.clearVideoHandler(videoRemoteHandler)
        PlaybackSessions.reportHeartbeatAsync(this, mediaType = "idle", isStopped = true)
        // Keep episode autoplay and receiver decode fallback alive behind the
        // SDK controller. Session progress itself is owned by the Application.
        if (castControllerOpened && remote != null && !isFinishing) {
            timer?.cancel()
            episodeJob?.cancel()
            runCatching { unregisterReceiver(noisyReceiver) }
            releaseLocal()
            super.onStop()
            return
        }
        playbackStarted = false
        ++castGeneration; castLoading = false
        castStartup?.cancel(); castStartup = null
        episodeJob?.cancel()
        runCatching { unregisterReceiver(noisyReceiver) }
        negotiation?.cancel(); timer?.cancel()
        if (prepared || remote != null) report(remote == null)
        if (remote == null) player?.let { position = currentMediaPositionMs(); wantsPlay = it.playWhenReady }
        releaseLocal(); remote?.unregisterCallback(remoteCallback)
        castCtx?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onStop()
    }
    override fun onDestroy() {
        unregisterReceiver(musicStarted)
        playbackStarted = false
        negotiation?.cancel(); timer?.cancel(); castStartup?.cancel()
        remote?.unregisterCallback(remoteCallback)
        castCtx?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onDestroy()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTelevision()) {
            if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) &&
                (hiddenSeekKey == event.keyCode || !playerView.isControllerFullyVisible)) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    hiddenSeekKey = event.keyCode
                    seekWithRemote(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L)
                } else if (event.action == KeyEvent.ACTION_UP) hiddenSeekKey = null
                return true
            }
            if (event.keyCode == revealingControlsKey) {
                if (event.action == KeyEvent.ACTION_UP) revealingControlsKey = null
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible &&
                !(skipBtn?.visibility == View.VISIBLE && skipBtn?.hasFocus() == true) &&
                event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                revealingControlsKey = event.keyCode
                wantsPlay = false
                player?.pause()
                playerView.showController()
                focusPlaybackControl()
                return true
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) { changeEpisode(1); return true }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { changeEpisode(-1); return true }
        if (playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
