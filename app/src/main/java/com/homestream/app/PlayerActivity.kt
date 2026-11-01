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
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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
    private var castControllerOpened = false
    private var knownDurationMs = 0L
    private var position = 0L
    private var refreshInitialResume = true
    private var currentPlaybackId = ""
    private var playbackPolicy = ""
    private var prepared = false
    private var reportedStartedId = ""
    private var transcode = false
    private var playbackMethod = "DirectPlay"
    private var playbackDetails = "Preparing playback"
    private var modeBadge: TextView? = null
    private var isStreamingDownload = false
    private var downloadPollingJob: Job? = null
    private var castGeneration = 0
    private var castLoading = false
    private var castCompatible = false
    private var castNeedsSafeProfile = false
    private val castRejectedItems = mutableSetOf<String>()
    private var receiverProfile = castProfiles[2]
    private var wantsPlay = true
    private var timer: Job? = null
    private var negotiation: Job? = null
    private var retryJob: Job? = null
    private var playbackStarted = false
    private var remote: RemoteMediaClient? = null
    private var episodes = EpisodeQueue()
    private var episodeJob: Job? = null
    private var queueJob: Job? = null
    private var titleColumn: LinearLayout? = null
    private var nextButton: android.widget.Button? = null
    private var previousButton: android.widget.Button? = null
    private var autoplayButton: android.widget.Button? = null
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
                val received = client.mediaInfo?.customData?.optJSONObject("media")?.let { Media(it) }
                if (received != null && received.playbackId != currentPlaybackId) {
                    media = received; currentPlaybackId = received.playbackId; updateEpisodeControls()
                    client.mediaInfo?.customData?.optJSONObject("playback")?.let { describeStream(it) }
                }
                val activeId = if (currentPlaybackId.isNotBlank()) currentPlaybackId else media.playbackId
                if (client.mediaInfo?.customData?.optString("itemId") == activeId) {
                    position = client.approximateStreamPosition
                    reportStarted()
                }
                val isActivelyPlaying = client.isPlaying || client.isLoadingNextItem
                if (client.playerState == MediaStatus.PLAYER_STATE_IDLE && client.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    val session = castCtx?.sessionManager?.currentCastSession
                    if (!castCompatible && currentPlaybackId !in castRejectedItems && session?.isConnected == true) {
                        client.unregisterCallback(remoteCallback); remote = null
                        castRejectedItems.add(currentPlaybackId); castNeedsSafeProfile = true; castLoading = false
                        cast(session, forceReload = true)
                    } else showMessage("The receiver could not decode this stream. Try another title or play locally.")
                    return
                }
                val isIdleOrEnded = client.playerState == MediaStatus.PLAYER_STATE_IDLE ||
                        client.idleReason == MediaStatus.IDLE_REASON_FINISHED

                if (isIdleOrEnded || isActivelyPlaying) openCastController()
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
        override fun onSessionEnding(session: CastSession) { remote?.let { position = it.approximateStreamPosition; wantsPlay = it.isPlaying } }
        override fun onSessionResuming(session: CastSession, id: String) {}
    }

    private fun describeStream(stream: JSONObject) {
        if (stream.optLong("totalDurationTicks") > 0) knownDurationMs = ticksToMs(stream.optLong("totalDurationTicks"))
        playbackMethod = stream.optString("playMethod").ifBlank { if (stream.optBoolean("isTranscoding")) "Transcode" else "DirectPlay" }
        val hls = stream.optString("transport") == "HLS" || Uri.parse(stream.optString("streamUrl")).path.orEmpty().endsWith(".m3u8", ignoreCase = true)
        playbackDetails = listOf(
            "Delivery: $playbackMethod",
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        media = runCatching { Media(JSONObject(savedInstanceState?.getString("media") ?: intent.getStringExtra("media") ?: "")) }.getOrElse { finish(); return }
        isStreamingDownload = intent.getBooleanExtra("isStreamingDownload", false) || media.json.optBoolean("isStreamingDownload")
        knownDurationMs = ticksToMs(media.json.optLong("totalDurationTicks"))
        currentPlaybackId = media.playbackId
        castCompatible = playbackPrefs.getBoolean("cast-compatible", false)
        api = HomeStreamApi(ServerSettings(this))
        refreshInitialResume = savedInstanceState == null && !intent.getBooleanExtra("fromBeginning", false)
        position = savedInstanceState?.getLong("position") ?: if (intent.getBooleanExtra("fromBeginning", false)) 0 else media.resumeMs
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setPlaybackPresentation(castCtx?.sessionManager?.currentCastSession?.isConnected == true && !media.isDownload)
        val frame = FrameLayout(this).apply { setBackgroundColor(INK) }
        playbackRoot = frame
        playerView = PlayerView(this).apply {
            setShowSubtitleButton(true); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerAutoShow = true; controllerShowTimeoutMs = if (isTelevision()) 6000 else 4000
        }
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(RED)
            setScrubberColor(Color.WHITE)
            setBufferedColor(0x80FFFFFF.toInt())
            setUnplayedColor(0x33FFFFFF.toInt())
        }
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))

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
                    player?.let { position = it.currentPosition; wantsPlay = it.playWhenReady }
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
        previousButton = action("Previous") { changeEpisode(-1) }.apply { contentDescription = "Previous episode"; visibility = View.GONE }
        nextButton = action("Next") { changeEpisode(1) }.apply { contentDescription = "Next episode"; visibility = View.GONE }
        autoplayButton = action(if (autoplay) "Autoplay on" else "Autoplay off") {
            autoplay = !autoplay
            autoplayButton?.text = if (autoplay) "Autoplay on" else "Autoplay off"
            val session = castCtx?.sessionManager?.currentCastSession
            if (remote != null && session?.isConnected == true) {
                position = remote?.approximateStreamPosition ?: position
                wantsPlay = remote?.isPlaying == true
                remote?.unregisterCallback(remoteCallback); remote = null
                cast(session, forceReload = true)
            }
        }.apply { contentDescription = "Toggle next episode autoplay"; visibility = View.GONE }
        val episodeBar = LinearLayout(this).apply {
            gravity = Gravity.END
            addView(previousButton); addView(nextButton); addView(autoplayButton)
            addView(action("Episodes") { showEpisodeQueue() }.apply { contentDescription = "Episode queue"; visibility = if (media.type in listOf("Episode", "Series")) View.VISIBLE else View.GONE })
            addView(action("Subtitles") { showCastSubtitles() }.apply { visibility = if (castCtx != null) View.VISIBLE else View.GONE })
        }
        overlay.addView(bar)
        if (!isTelevision()) overlay.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(episodeBar)
        })
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visible ->
            overlay.visibility = if (visible == View.VISIBLE || remote != null) View.VISIBLE else View.GONE
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
        if (castControllerOpened) { finish(); return }
        playbackPolicy = ServerSettings(this).policyRevision
        playbackStarted = true
        runCatching { registerReceiver(noisyReceiver, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
        castCtx?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        lifecycleScope.launch {
            try {
                if (api.refreshProfilePolicy()) { clearCastIdle(this@PlayerActivity); finish(); return@launch }
                val session = castCtx?.sessionManager?.currentCastSession
                if (session?.isConnected == true) cast(session) else {
                    remote?.unregisterCallback(remoteCallback); remote = null; startLocal()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                showMessage(e.message ?: "Could not confirm profile")
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
                if (prepared || remote != null) report(false)
            }
        }
    }
    private fun showUnDownloadedUI(reason: String = "This title has not been downloaded yet.") {
        clearMessages()
        showMessage(reason)
        if (ServerSettings(this).kidsRating.isBlank()) overlay.addView(action("▶  Stream with qBittorrent") {
            startStreamingDownloadPipeline()
        })
        overlay.addView(action("←  Back to Browse") { finish() })
    }

    private fun startStreamingDownloadPipeline() {
        isStreamingDownload = true
        downloadPollingJob?.cancel()
        negotiation?.cancel()
        releaseLocal()
        clearMessages()

        val isTv = intent.getStringExtra("mediaType") == "tv" || media.type == "Series" || media.type == "Episode"
        val sNum = intent.getIntExtra("season", 0).takeIf { it > 0 } ?: media.json.optInt("seasonNumber", 1).coerceAtLeast(1)
        val epNum = intent.getIntExtra("episode", 0).takeIf { it > 0 } ?: media.json.optInt("episodeNumber", 1).coerceAtLeast(1)
        val sTitle = intent.getStringExtra("seriesTitle")?.ifBlank { null } ?: media.title

        val tmdb = intent.getIntExtra("tmdbId", 0).takeIf { it > 0 } ?: if (media.tmdbId > 0) media.tmdbId else {
            val strId = media.id.replace("^seerr-(?:movie-|tv-)?".toRegex(), "")
            strId.toIntOrNull() ?: 0
        }

        val totalDurationMs = (media.json.optInt("durationMinutes").takeIf { it > 0 } ?: 120) * 60L * 1000L
        val timeBar = playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
        timeBar?.setDuration(totalDurationMs)

        downloadPollingJob = lifecycleScope.launch {
            if (tmdb > 0) {
                try {
                    val body = JSONObject()
                        .put("mediaType", if (isTv) "tv" else "movie")
                        .put("mediaId", tmdb)
                    if (isTv) body.put("seasons", org.json.JSONArray().put(sNum))
                    api.call("request", body = body)
                } catch (e: Exception) {
                    android.util.Log.w("HomeStream", "Download request trigger: ${e.message}")
                }
            }

            var attempts = 0
            val cleanMediaTitle = sTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
            var matchedHash: String? = null

            while (isActive) {
                try {
                    val res = api.call("downloads")
                    val items = res.optJSONArray("items")
                    var matchedTorrent: JSONObject? = null

                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val t = items.getJSONObject(i)
                            val tHash = t.optString("hash")
                            if (matchedHash != null && tHash.equals(matchedHash, ignoreCase = true)) {
                                matchedTorrent = t
                                break
                            }
                            val tName = t.optString("name").lowercase().replace(Regex("[^a-z0-9]"), "")
                            if (cleanMediaTitle.isNotBlank() && tName.isNotBlank() && (tName.contains(cleanMediaTitle) || cleanMediaTitle.contains(tName))) {
                                matchedTorrent = t
                                matchedHash = tHash
                                break
                            }
                        }
                    }

                    if (matchedTorrent != null) {
                        val hash = matchedTorrent.optString("hash")
                        val progress = matchedTorrent.optDouble("progressPercent", 0.0) / 100.0
                        val downloadedBytes = matchedTorrent.optLong("downloaded", 0L)

                        // Directly display buffering progress along the seek bar
                        val bufferedMs = (progress * totalDurationMs).toLong()
                        timeBar?.setBufferedPosition(bufferedMs)

                        val minBufferBytes = 10L * 1024L * 1024L
                        if (downloadedBytes >= minBufferBytes || progress >= 0.01 || progress >= 0.99) {
                            val streamPath = buildString {
                                append("stream/download?hash=").append(hash)
                                if (isTv) {
                                    append("&season=").append(sNum)
                                    append("&episode=").append(epNum)
                                }
                            }
                            launchStreamUrl(streamPath)
                            return@launch
                        }
                    } else {
                        attempts++
                        if (attempts > 30) {
                            clearMessages()
                            showMessage("Download queued in Sonarr/Radarr.\nqBittorrent has not started the torrent yet.")
                            overlay.addView(action("Keep Waiting") { startStreamingDownloadPipeline() })
                            overlay.addView(action("←  Back to Browse") { finish() })
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.w("HomeStream", "Error polling downloads: ${e.message}")
                }
                delay(2000)
            }
        }
    }

    private fun launchStreamUrl(streamPath: String, compatible: Boolean = transcode) {
        val fullPath = if (streamPath.startsWith("/api/")) streamPath
                       else "/api/mobile/v1/${streamPath.removePrefix("/")}"
        val playUri = Uri.parse(api.absolute(fullPath)).buildUpon()
            .appendQueryParameter(if (compatible) "transcode" else "native", "1").build()

        val item = MediaItem.Builder()
            .setUri(playUri)
            .apply { if (compatible) setMimeType(MimeTypes.VIDEO_MP4) }
            .build()
        releaseLocal()

        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(api.mediaClient())
            .setUserAgent("HomeStream-Android/1.0")
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        prepared = true
                        clearMessages()
                    }
                    if (state == Player.STATE_ENDED) {
                        position = exo.duration.coerceAtLeast(0)
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
            exo.prepare()
            exo.playWhenReady = wantsPlay
        }
        playerView.requestFocus()
        playerView.showController()
    }

    private suspend fun resolvePlaybackId(): String {
        if (media.isDownload || (media.type != "Series" && !media.playbackId.startsWith("seerr-"))) return media.playbackId
        val item = api.call("media/${Uri.encode(media.id)}").optJSONObject("item") ?: return media.playbackId
        item.optString("playableEpisodeId").takeIf { it.isNotBlank() }?.let { return it }
        if (media.type != "Series") item.optString("jellyfinId").takeIf { it.isNotBlank() }?.let { return it }
        val seasons = item.optJSONArray("seasons") ?: return media.playbackId
        for (i in 0 until seasons.length()) {
            val episodes = seasons.getJSONObject(i).optJSONArray("episodes") ?: continue
            for (j in 0 until episodes.length()) {
                val episode = Media(episodes.getJSONObject(j))
                if (episode.isAvailableForPlayback && !episode.playbackId.startsWith("seerr-")) return episode.playbackId
            }
        }
        return media.playbackId
    }

    private fun startLocal() {
        setPlaybackPresentation(false)
        negotiation?.cancel(); clearMessages(); showMessage("Preparing playback…")
        if (media.isDownload) {
            isStreamingDownload = true
            launchStreamUrl(media.downloadUrl)
            return
        }
        if (isStreamingDownload || !media.isAvailableForPlayback) {
            startStreamingDownloadPipeline()
            return
        }
        negotiation = lifecycleScope.launch {
            try {
                val targetId = resolvePlaybackId()

                if (targetId.startsWith("seerr-") || media.isVirtual) {
                    startStreamingDownloadPipeline()
                    return@launch
                }

                updateModeBadge()
                val audioCodecs = getDeviceSupportedAudioCodecs()
                val stream = if (media.isDownload) JSONObject().put("streamUrl", media.downloadUrl)
                    else api.call("playback", mapOf(
                        "itemId" to targetId,
                        "forceTranscode" to transcode.toString(),
                        "videoCodecs" to runCatching {
                            videoCodecsForMimeTypes(MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                                .filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() })
                        }.getOrDefault("h264"),
                        "audioCodecs" to audioCodecs
                    ) + if (isTelevision()) mapOf("receiver" to "native-tv", "videoCapabilities" to televisionVideoProfiles().toString()) else emptyMap())
                applyInitialResume(stream)
                describeStream(stream)
                val url = stream.optString("streamUrl")
                if (url.isBlank()) {
                    showUnDownloadedUI(stream.optString("error", "This title has not been downloaded yet."))
                    return@launch
                }
                currentPlaybackId = targetId
                loadEpisodeContext()
                val isTranscodingStream = Uri.parse(url).path.orEmpty().endsWith(".m3u8", ignoreCase = true)
                if (isTranscodingStream && !transcode) {
                    transcode = true
                    updateModeBadge()
                }

                val rawUri = api.absolute(url)
                val playUri = Uri.parse(rawUri)

                val tracks = if (media.isDownload) emptyList() else subtitleItems(targetId)
                val item = MediaItem.Builder().setUri(playUri).setMediaId(targetId)
                    .setSubtitleConfigurations(tracks).apply {
                        if (isTranscodingStream) setMimeType(MimeTypes.APPLICATION_M3U8)
                    }.build()
                releaseLocal()

                val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(api.mediaClient())
                    .setUserAgent("HomeStream-Android/1.0")
                val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this@PlayerActivity)
                    .setDataSourceFactory(httpFactory)

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()

                player = ExoPlayer.Builder(this@PlayerActivity)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                    .build().also { exo ->
                    playerView.player = object : androidx.media3.common.ForwardingPlayer(exo) {
                        override fun getDuration(): Long = knownDurationMs.takeIf { it > 0 } ?: super.getDuration()
                        override fun getContentDuration(): Long = knownDurationMs.takeIf { it > 0 } ?: super.getContentDuration()
                    }
                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) { prepared = true; clearMessages(); reportStarted() }
                            if (state == Player.STATE_ENDED) {
                                position = exo.duration.coerceAtLeast(0); report(true)
                                if (autoplay) changeEpisode(1, automatic = true)
                            }
                        }
                        override fun onTracksChanged(tracks: Tracks) {
                            // Detect if media file has audio tracks, but none are supported by hardware/software decoders
                            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                            if (audioGroups.isNotEmpty()) {
                                val anySupported = audioGroups.any { it.isSupported }
                                if (!anySupported && !transcode && !media.isDownload) {
                                    position = exo.currentPosition
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
                            position = exo.currentPosition
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
                                showUnDownloadedUI("Media file has not been downloaded or is not available on Jellyfin.")
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
                            showMessage("Playback failed (${error.errorCodeName}). Check that this device can reach Jellyfin.")
                            if (media.isDownload) {
                                showMessage("In-progress downloads may buffer or be unseekable. Wait for more data and retry.")
                                overlay.addView(action("Retry") { startLocal() })
                            } else overlay.addView(action("Retry with compatible HLS") { transcode = true; updateModeBadge(); startLocal() })
                            overlay.addView(action("←  Back to Browse") { finish() })
                        }
                    })
                    exo.setMediaItem(item); exo.seekTo(position); exo.prepare(); exo.playWhenReady = wantsPlay
                }
                playerView.requestFocus(); playerView.showController()
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
    private suspend fun subtitleItems(targetId: String = ""): List<MediaItem.SubtitleConfiguration> = try {
        val queryId = targetId.ifBlank { if (currentPlaybackId.isNotBlank()) currentPlaybackId else media.playbackId }
        api.call("subtitles", mapOf("itemId" to queryId)).optJSONArray("subtitles")?.let { tracks ->
            (0 until tracks.length()).map { i ->
                val track = tracks.getJSONObject(i)
                // Use Jellyfin's absolute subtitle URL: the player/receiver has no pairing headers.
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(api.absolute(track.getString("src"))))
                    .setMimeType(MimeTypes.TEXT_VTT).setLanguage(track.optString("language"))
                    .setLabel(track.optString("title")).build()
            }
        }.orEmpty()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
    }
    private fun cast(session: CastSession, forceReload: Boolean = false) {
        if (media.isDownload || isStreamingDownload) {
            Toast.makeText(this, "Live download streams play locally on device. Cast after import into Jellyfin.", Toast.LENGTH_LONG).show()
            startLocal(); return
        }
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
        if (!forceReload && client.mediaInfo?.customData?.optString("itemId") == currentPlaybackId) {
            remote = client; client.registerCallback(remoteCallback)
            position = client.approximateStreamPosition
            releaseLocal(); clearMessages(); showCastControls(); return
        }
        if (!forceReload) {
            position = player?.currentPosition ?: position
            wantsPlay = player?.playWhenReady ?: wantsPlay
        }
        player?.pause(); negotiation?.cancel(); queueJob?.cancel(); castLoading = true
        val generation = ++castGeneration
        negotiation = lifecycleScope.launch {
            try {
                val targetId = resolvePlaybackId()
                currentPlaybackId = targetId
                loadEpisodeContextNow(targetId)
                val current = episodes.items.firstOrNull { it.playbackId == targetId } ?: media
                if (current.type == "Episode") { media = current; updateEpisodeControls() }
                val info = castMediaInfo(current, targetId)
                val upcoming = if (autoplay) episodes.upcoming(targetId).take(50) else emptyList()
                val firstNext = upcoming.firstOrNull()?.let { next ->
                    runCatching { castQueueItem(castMediaInfo(next, next.playbackId), true) }.getOrNull()
                }
                val queue = listOf(castQueueItem(info, wantsPlay, position)) + listOfNotNull(firstNext)
                client.queueLoad(queue.toTypedArray(), 0, MediaStatus.REPEAT_MODE_REPEAT_OFF, position, null)
                    .setResultCallback { result ->
                        if (generation != castGeneration || !playbackStarted) return@setResultCallback
                        castLoading = false
                        if (result.status.isSuccess) {
                            remote = client; client.registerCallback(remoteCallback)
                            releaseLocal(); clearMessages(); showCastControls()
                            if (upcoming.isNotEmpty() && firstNext == null) Toast.makeText(this@PlayerActivity, "Next episode could not be queued. Retry from Episodes.", Toast.LENGTH_LONG).show()
                            queueJob?.cancel()
                            queueJob = lifecycleScope.launch {
                                if (firstNext == null) return@launch
                                for (next in upcoming.drop(1)) {
                                    if (remote !== client) break
                                    try {
                                        val queued = castQueueItem(castMediaInfo(next, next.playbackId), true)
                                        val success = suspendCancellableCoroutine<Boolean> { continuation ->
                                            client.queueAppendItem(queued, null).setResultCallback { appended ->
                                                if (continuation.isActive) continuation.resume(appended.status.isSuccess)
                                            }
                                        }
                                        if (!success) break
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        break
                                    }
                                }
                            }
                        } else if (!castCompatible && currentPlaybackId !in castRejectedItems) {
                            castRejectedItems.add(currentPlaybackId); castNeedsSafeProfile = true
                            cast(session, forceReload = true)
                        } else { Toast.makeText(this@PlayerActivity, "Chromecast could not load this stream. Check receiver access to Jellyfin.", Toast.LENGTH_LONG).show(); returnToLocal() }
                    }
            } catch (_: kotlinx.coroutines.CancellationException) { castLoading = false
            } catch (e: Exception) { castLoading = false; showMessage(e.message ?: "Cast failed"); returnToLocal() }
        }
    }
    private fun chooseCastProfile(session: CastSession) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Cast device model")
            .setItems(castProfiles.map { it.label }.toTypedArray()) { _, index ->
                val client = remote
                if (client == null) player?.let { position = it.currentPosition; wantsPlay = it.playWhenReady }
                if (client != null) { position = client.approximateStreamPosition; wantsPlay = client.isPlaying; client.unregisterCallback(remoteCallback); remote = null }
                receiverProfile = castProfiles[index]
                castNeedsSafeProfile = false; castRejectedItems.clear()
                playbackPrefs.edit().putString("cast-profile-${session.castDevice?.deviceId.orEmpty()}", receiverProfile.key).apply()
                negotiation?.cancel(); queueJob?.cancel(); castLoading = false
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

    private fun castQueueItem(info: MediaInfo, play: Boolean, startMs: Long = 0) = MediaQueueItem.Builder(info)
        .setStartTime(startMs.coerceAtLeast(0) / 1000.0).setAutoplay(play).setPreloadTime(10.0).setActiveTrackIds(subtitleIds(info)).build()

    private suspend fun castMediaInfo(item: Media, id: String): MediaInfo {
        val compatible = castCompatible || id in castRejectedItems
        val stream = api.call("playback", mapOf("itemId" to id, "forceTranscode" to compatible.toString(),
            "receiver" to "cast", "videoCodecs" to if (receiverProfile.hevc) "h264,hevc,vp8,vp9,av1" else "h264,vp8",
            "maxWidth" to receiverProfile.width.toString(), "maxHeight" to receiverProfile.height.toString(), "audioCodecs" to "aac,mp3,opus,vorbis"))
        if (id == currentPlaybackId) { applyInitialResume(stream); describeStream(stream); transcode = stream.optBoolean("isTranscoding"); updateModeBadge() }
        val metadata = MediaMetadata(if (item.type == "Episode") MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            if (item.json.optString("seriesName").isNotBlank()) putString(MediaMetadata.KEY_SERIES_TITLE, item.json.optString("seriesName"))
            if (item.poster.isNotBlank()) addImage(WebImage(Uri.parse(api.absolute(item.poster))))
        }
        val tracks = subtitleItems(id).mapIndexed { index, track -> MediaTrack.Builder((index + 1).toLong(), MediaTrack.TYPE_TEXT)
            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES).setContentId(track.uri.toString()).setContentType("text/vtt")
            .setName(track.label ?: "Subtitle").setLanguage(track.language ?: "und").build() }
        return MediaInfo.Builder(api.absolute(stream.getString("streamUrl")))
            .setContentType(when {
                Uri.parse(stream.getString("streamUrl")).path.orEmpty().endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                Uri.parse(stream.getString("streamUrl")).path.orEmpty().endsWith(".webm", ignoreCase = true) -> "video/webm"
                else -> "video/mp4"
            })
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(metadata).setMediaTracks(tracks)
            .setCustomData(JSONObject().put("itemId", id).put("media", item.json).put("playback", JSONObject(stream.toString()).apply {
                put("transport", if (Uri.parse(stream.optString("streamUrl")).path.orEmpty().endsWith(".m3u8", ignoreCase = true)) "HLS" else "file")
                remove("streamUrl")
            }))
            .apply { if (stream.optDouble("durationSeconds") > 0) setStreamDuration((stream.getDouble("durationSeconds") * 1000).toLong()) }.build()
    }

    private suspend fun loadEpisodeContextNow(id: String = currentPlaybackId) {
        if (media.type != "Episode" && media.type != "Series") return
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

    private fun updateEpisodeControls() {
        val episodic = episodes.index(currentPlaybackId) >= 0
        nextButton?.visibility = if (episodes.next(currentPlaybackId) != null) View.VISIBLE else View.GONE
        previousButton?.visibility = if (episodes.previous(currentPlaybackId) != null) View.VISIBLE else View.GONE
        autoplayButton?.visibility = if (episodic) View.VISIBLE else View.GONE
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
        queueJob?.cancel(); negotiation?.cancel(); castLoading = false
        media = item; currentPlaybackId = item.playbackId; position = 0; wantsPlay = true
        updateEpisodeControls()
        val session = castCtx?.sessionManager?.currentCastSession
        if (client != null && session?.isConnected == true) {
            client.unregisterCallback(remoteCallback); remote = null
            cast(session, forceReload = true)
        } else { releaseLocal(); startLocal() }
    }

    private fun showEpisodeQueue() {
        if (episodes.items.isEmpty()) { Toast.makeText(this, "No episode queue available.", Toast.LENGTH_SHORT).show(); return }
        val labels = episodes.items.map { item ->
            val active = if (item.playbackId == currentPlaybackId) "▶ " else ""
            "$active S${item.json.optInt("seasonNumber", 1)} E${item.json.optInt("episodeNumber", 1)} · ${item.title}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Episodes")
            .setItems(labels) { _, index -> selectEpisode(episodes.items[index]) }.setNegativeButton("Close", null).show()
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
        if (castControllerOpened) return
        castControllerOpened = true
        startActivity(android.content.Intent(this, CastControllerActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun returnToLocal() {
        queueJob?.cancel()
        castLoading = false
        remote?.unregisterCallback(remoteCallback)
        remote = null
        clearMessages()
        startLocal()
    }
    private fun reportStarted() {
        if (reportedStartedId == currentPlaybackId || media.isDownload || isStreamingDownload) return
        reportedStartedId = currentPlaybackId
        report(false, started = true)
    }

    private fun report(stopped: Boolean, started: Boolean = false) {
        if (media.isDownload || isStreamingDownload || remote != null) return
        val client = remote
        val activeId = if (currentPlaybackId.isNotBlank()) currentPlaybackId else media.playbackId
        if (client != null && client.mediaInfo?.customData?.optString("itemId") != activeId) return
        if (client != null) position = client.approximateStreamPosition else player?.let { position = it.currentPosition }
        val snapshot = position
        val paused = client?.isPaused ?: (player?.isPlaying == false)
        val launcherItem = episodes.items.firstOrNull { it.playbackId == activeId } ?: media
        val launcherMedia = Media(JSONObject(launcherItem.json.toString()).put("id", activeId).put("jellyfinId", activeId))
        val launcherDuration = knownDurationMs.takeIf { it > 0 } ?: player?.duration?.takeIf { it > 0 }
            ?: ticksToMs(launcherMedia.json.optLong("totalDurationTicks"))
        val launcherScope = if (isTelevision()) WatchNext.scope(ServerSettings(this)) else ""
        // A final report must survive Activity destruction (Back/rotation).
        (application as HomeStreamApplication).enqueuePlaybackReport {
            if (launcherScope.isNotBlank()) runCatching { WatchNext.record(applicationContext, launcherMedia, snapshot, launcherDuration, launcherScope) }
            runCatching { api.progress(activeId, snapshot, paused, stopped, started) }
        }
    }
    private fun releaseLocal() {
        retryJob?.cancel(); retryJob = null
        downloadPollingJob?.cancel()
        playerView.player = null
        player?.release()
        player = null
        prepared = false
        reportedStartedId = ""
    }
    private fun clearMessages() { while (overlay.childCount > 2) overlay.removeViewAt(2) }
    private fun showMessage(text: String) { overlay.visibility = View.VISIBLE; overlay.addView(label(text, 14f)) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("media", media.json.toString()); outState.putLong("position", player?.currentPosition ?: position); super.onSaveInstanceState(outState) }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
        }
    }
    override fun onStop() {
        // Keep queue preparation and receiver decode fallback alive behind the
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
        episodeJob?.cancel()
        downloadPollingJob?.cancel()
        runCatching { unregisterReceiver(noisyReceiver) }
        negotiation?.cancel(); timer?.cancel()
        if (prepared || remote != null) report(remote == null)
        if (remote == null) player?.let { position = it.currentPosition; wantsPlay = it.playWhenReady }
        releaseLocal(); remote?.unregisterCallback(remoteCallback)
        castCtx?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onStop()
    }
    override fun onDestroy() {
        playbackStarted = false
        negotiation?.cancel(); queueJob?.cancel(); timer?.cancel()
        remote?.unregisterCallback(remoteCallback)
        castCtx?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onDestroy()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) { changeEpisode(1); return true }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { changeEpisode(-1); return true }
        if (playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
