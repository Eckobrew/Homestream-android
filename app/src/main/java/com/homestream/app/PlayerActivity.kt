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
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
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
    private var position = 0L
    private var currentPlaybackId = ""
    private var prepared = false
    private var transcode = false
    private var modeBadge: TextView? = null
    private var castLoading = false
    private var wantsPlay = true
    private var timer: Job? = null
    private var negotiation: Job? = null
    private var remote: RemoteMediaClient? = null
    private lateinit var castOverlay: FrameLayout
    private lateinit var castBackdropView: ImageView
    private lateinit var castBadgeView: TextView
    private lateinit var castTitleView: TextView
    private lateinit var castOverviewView: TextView
    private lateinit var playThisBtn: android.widget.Button
    private lateinit var controlsBtn: android.widget.Button
    private lateinit var stopCastBtn: android.widget.Button
    private var currentCycledItem: Media? = null
    private var availableContent = mutableListOf<Media>()
    private var idleCycleJob: Job? = null
    private var idleIndex = 0

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) player?.pause()
        }
    }
    private val castCtx by lazy { castContext(this) }
    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            remote?.let { client ->
                val activeId = if (currentPlaybackId.isNotBlank()) currentPlaybackId else media.playbackId
                if (client.mediaInfo?.customData?.optString("itemId") == activeId) {
                    position = client.approximateStreamPosition
                }
                val isActivelyPlaying = client.isPlaying || client.isLoadingNextItem
                val isIdleOrEnded = client.playerState == MediaStatus.PLAYER_STATE_IDLE ||
                        client.idleReason == MediaStatus.IDLE_REASON_FINISHED

                if (isIdleOrEnded) {
                    startIdleCycle()
                } else if (isActivelyPlaying) {
                    stopIdleCycle()
                    showNowPlayingCastUI()
                }
            }
        }
    }
    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, id: String) { cast(session) }
        override fun onSessionResumed(session: CastSession, suspended: Boolean) { cast(session) }
        override fun onSessionEnded(session: CastSession, error: Int) { returnToLocal() }
        override fun onSessionSuspended(session: CastSession, reason: Int) { player?.pause(); showMessage("Cast connection interrupted. Reconnect from the Cast button.") }
        override fun onSessionStartFailed(session: CastSession, error: Int) { returnToLocal() }
        override fun onSessionResumeFailed(session: CastSession, error: Int) { returnToLocal() }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) { position = remote?.approximateStreamPosition ?: position }
        override fun onSessionResuming(session: CastSession, id: String) {}
    }

    private fun updateModeBadge() {
        modeBadge?.text = if (transcode) "HLS" else "DIRECT"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTelevision()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        media = runCatching { Media(JSONObject(intent.getStringExtra("media") ?: "")) }.getOrElse { finish(); return }
        currentPlaybackId = media.playbackId
        api = HomeStreamApi(ServerSettings(this))
        position = savedInstanceState?.getLong("position") ?: if (intent.getBooleanExtra("fromBeginning", false)) 0 else media.resumeMs
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(INK) }
        playerView = PlayerView(this).apply {
            setShowSubtitleButton(true); setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            controllerAutoShow = true; controllerShowTimeoutMs = if (isTelevision()) 6000 else 4000
        }
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        setupCastOverlay(frame)

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
        bar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        // Stream format toggle badge (Direct vs Transcode)
        if (!media.isDownload) {
            val badge = TextView(this).apply {
                text = if (transcode) "HLS" else "DIRECT"
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = rounded(if (transcode) 0x66E50914.toInt() else 0x44000000.toInt(), radius = dp(14).toFloat())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
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
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visible ->
            overlay.visibility = if (visible == View.VISIBLE || remote != null) View.VISIBLE else View.GONE
        })
        setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
    }
    override fun onStart() {
        super.onStart()
        runCatching { registerReceiver(noisyReceiver, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
        castCtx?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        val session = castCtx?.sessionManager?.currentCastSession
        if (session?.isConnected == true) cast(session) else {
            remote?.unregisterCallback(remoteCallback); remote = null; startLocal()
        }
        timer = lifecycleScope.launch {
            while (true) {
                delay(10_000)
                if (prepared || remote != null) report(false)
            }
        }
    }
    private fun startLocal() {
        negotiation?.cancel(); clearMessages(); showMessage("Preparing playback…")
        negotiation = lifecycleScope.launch {
            try {
                var targetId = media.playbackId
                if (!media.isDownload && (media.type == "Series" || targetId.startsWith("seerr-"))) {
                    try {
                        val details = api.call("media/${android.net.Uri.encode(media.id)}")
                        val itemObj = details.optJSONObject("item")
                        val epId = itemObj?.optString("playableEpisodeId") ?: ""
                        val jfId = itemObj?.optString("jellyfinId") ?: ""
                        if (epId.isNotBlank()) {
                            targetId = epId
                        } else if (jfId.isNotBlank()) {
                            targetId = jfId
                        } else {
                            val seasons = itemObj?.optJSONArray("seasons")
                            if (seasons != null && seasons.length() > 0) {
                                for (sIdx in 0 until seasons.length()) {
                                    val sObj = seasons.getJSONObject(sIdx)
                                    val eps = sObj.optJSONArray("episodes")
                                    if (eps != null && eps.length() > 0) {
                                        for (eIdx in 0 until eps.length()) {
                                            val ep = eps.getJSONObject(eIdx)
                                            val playableEp = ep.optString("jellyfinId").ifBlank { ep.optString("id") }
                                            if (playableEp.isNotBlank() && !playableEp.startsWith("seerr-")) {
                                                targetId = playableEp
                                                break
                                            }
                                        }
                                        if (!targetId.startsWith("seerr-")) break
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (targetId.startsWith("seerr-")) {
                    clearMessages()
                    showMessage("This title is not yet available in your Jellyfin collection.")
                    overlay.addView(action("Request Media") {
                        lifecycleScope.launch {
                            try {
                                showMessage("Requesting…")
                                api.call("request", body = JSONObject().put("mediaId", media.tmdbId).put("mediaType", if (media.type == "Movie") "movie" else "tv"))
                                clearMessages(); showMessage("Requested! It will be downloaded automatically.")
                            } catch (e: Exception) {
                                clearMessages(); showMessage(e.message ?: "Request failed")
                            }
                        }
                    })
                    return@launch
                }

                updateModeBadge()
                val audioCodecs = getDeviceSupportedAudioCodecs()
                val stream = if (media.isDownload) JSONObject().put("streamUrl", media.downloadUrl)
                    else api.call("playback", mapOf(
                        "itemId" to targetId,
                        "forceTranscode" to transcode.toString(),
                        "canPlayHevc" to "true",
                        "audioCodecs" to audioCodecs
                    ))
                val url = stream.optString("streamUrl")
                if (url.isBlank()) error("No playable stream returned by Jellyfin.")
                currentPlaybackId = targetId
                val isTranscodingStream = stream.optBoolean("isTranscoding") || url.contains(".m3u8")
                if (isTranscodingStream && !transcode) {
                    transcode = true
                    updateModeBadge()
                }

                val token = ServerSettings(this@PlayerActivity).token
                val rawUri = api.absolute(url)
                val isHomeStreamInternal = url.startsWith("/api/") || rawUri.contains("/api/stream/")
                val playUri = if (token.isNotBlank() && isHomeStreamInternal && !rawUri.contains("token=")) {
                    Uri.parse(rawUri).buildUpon().appendQueryParameter("token", token).build()
                } else {
                    Uri.parse(rawUri)
                }

                val tracks = if (media.isDownload) emptyList() else subtitleItems(targetId)
                val item = MediaItem.Builder().setUri(playUri).setMediaId(targetId)
                    .setSubtitleConfigurations(tracks).apply {
                        if (isTranscodingStream) setMimeType(MimeTypes.APPLICATION_M3U8)
                    }.build()
                releaseLocal()

                val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent("HomeStream-Android/1.0")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(20_000)
                    .setReadTimeoutMs(60_000)
                    .apply {
                        if (token.isNotBlank() && isHomeStreamInternal) {
                            setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
                        }
                    }
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
                    playerView.player = exo
                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) { prepared = true; clearMessages() }
                            if (state == Player.STATE_ENDED) { position = exo.duration.coerceAtLeast(0); report(true) }
                        }
                        override fun onTracksChanged(tracks: Tracks) {
                            // Detect if media file has audio tracks, but none are supported by hardware/software decoders
                            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                            if (audioGroups.isNotEmpty()) {
                                val anySupported = audioGroups.any { it.isSupported }
                                if (!anySupported && !transcode && !media.isDownload) {
                                    android.util.Log.w("HomeStream", "Audio format not supported by device decoder. Triggering server transcode fallback.")
                                    transcode = true
                                    updateModeBadge()
                                    showMessage("Audio format not supported by device. Switching to server transcode…")
                                    startLocal()
                                }
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            android.util.Log.e("HomeStream", "Playback error encountered: ${error.errorCodeName} (${error.errorCode})", error)
                            position = exo.currentPosition
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
                        }
                    })
                    exo.setMediaItem(item); exo.seekTo(position); exo.prepare(); exo.playWhenReady = wantsPlay
                }
                playerView.requestFocus(); playerView.showController()
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                clearMessages(); showMessage(e.message ?: "Playback could not start.")
                overlay.addView(action("Retry") { startLocal() })
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
    private fun cast(session: CastSession) {
        if (media.isDownload) {
            Toast.makeText(this, "In-progress downloads play locally. Cast after Jellyfin imports the completed title.", Toast.LENGTH_LONG).show()
            startLocal(); return
        }
        if (castLoading) return
        val client = session.remoteMediaClient ?: return
        if (client.mediaInfo?.customData?.optString("itemId") == media.playbackId) {
            remote = client; client.registerCallback(remoteCallback)
            position = client.approximateStreamPosition
            releaseLocal(); clearMessages(); showCastControls(); return
        }
        position = player?.currentPosition ?: position
        wantsPlay = player?.playWhenReady ?: wantsPlay
        player?.pause(); negotiation?.cancel(); castLoading = true
        negotiation = lifecycleScope.launch {
            try {
                // Negotiate again for receiver codecs rather than handing off a phone-specific stream.
                val stream = api.call("playback", mapOf("itemId" to media.playbackId, "forceTranscode" to "true"))
                val metadata = MediaMetadata(if (media.type == "Episode") MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                    putString(MediaMetadata.KEY_TITLE, media.title)
                    val poster = api.absolute(media.poster)
                    if (poster.isNotBlank()) addImage(WebImage(Uri.parse(poster)))
                }
                val tracks = if (media.isDownload) emptyList() else subtitleItems().mapIndexed { i, track -> MediaTrack.Builder((i + 1).toLong(), MediaTrack.TYPE_TEXT)
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES).setContentId(track.uri.toString()).setContentType("text/vtt")
                    .setName(track.label ?: "Subtitle").setLanguage(track.language ?: "und").build() }
                val info = MediaInfo.Builder(api.absolute(stream.getString("streamUrl")))
                    .setContentType(if (stream.optBoolean("isTranscoding")) "application/x-mpegURL" else "video/mp4")
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(metadata).setMediaTracks(tracks)
                    .setCustomData(JSONObject().put("itemId", media.playbackId))
                    .apply { if (stream.optDouble("durationSeconds") > 0) setStreamDuration((stream.getDouble("durationSeconds") * 1000).toLong()) }.build()
                client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setCurrentTime(position).setAutoplay(wantsPlay).build())
                    .setResultCallback { result ->
                        castLoading = false
                        if (result.status.isSuccess) {
                            remote = client; client.registerCallback(remoteCallback)
                            releaseLocal(); clearMessages(); showCastControls()
                        } else { Toast.makeText(this@PlayerActivity, "Chromecast could not load this stream. Check receiver access to Jellyfin.", Toast.LENGTH_LONG).show(); returnToLocal() }
                    }
            } catch (_: kotlinx.coroutines.CancellationException) { castLoading = false
            } catch (e: Exception) { castLoading = false; showMessage(e.message ?: "Cast failed"); returnToLocal() }
        }
    }
    private fun setupCastOverlay(frame: FrameLayout) {
        castOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(INK)
        }
        castBackdropView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        castOverlay.addView(castBackdropView, FrameLayout.LayoutParams(-1, -1))

        castOverlay.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xBB000000.toInt(), 0x44000000.toInt(), 0xEE141414.toInt())
            )
        }, FrameLayout.LayoutParams(-1, -1))

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(24), dp(44), dp(36))
        }

        castBadgeView = TextView(this).apply {
            text = "📺  READY TO CAST"
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(RED, radius = dp(12).toFloat())
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        infoCol.addView(castBadgeView, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })

        castTitleView = TextView(this).apply {
            text = ""
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        infoCol.addView(castTitleView, LinearLayout.LayoutParams(-1, -2))

        castOverviewView = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoCol.addView(castOverviewView, LinearLayout.LayoutParams(dp(520), -2).apply {
            topMargin = dp(6)
            bottomMargin = dp(18)
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        playThisBtn = android.widget.Button(this).apply {
            text = "▶  Play Now"
            isAllCaps = false
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            background = rounded(Color.WHITE, radius = dp(8).toFloat())
            setPadding(dp(22), dp(8), dp(22), dp(8))
            setOnClickListener {
                currentCycledItem?.let { playCycledItem(it) }
            }
        }
        btnRow.addView(playThisBtn, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(12) })

        controlsBtn = android.widget.Button(this).apply {
            text = "Remote Controls"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(0xFF2E2E2E.toInt(), radius = dp(8).toFloat())
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener {
                startActivity(android.content.Intent(this@PlayerActivity, CastControllerActivity::class.java))
            }
        }
        btnRow.addView(controlsBtn, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(12) })

        stopCastBtn = android.widget.Button(this).apply {
            text = "Stop Casting"
            isAllCaps = false
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(RED, radius = dp(8).toFloat())
            setPadding(dp(22), dp(8), dp(22), dp(8))
            setOnClickListener {
                castCtx?.sessionManager?.endCurrentSession(true)
            }
        }
        btnRow.addView(stopCastBtn, LinearLayout.LayoutParams(-2, dp(44)))

        infoCol.addView(btnRow)
        castOverlay.addView(infoCol, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        frame.addView(castOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showCastControls() {
        stopIdleCycle()
        clearMessages()
        showNowPlayingCastUI()
        // The casting controls should just come up, not be a button!
        startActivity(android.content.Intent(this, CastControllerActivity::class.java))
    }

    private fun showNowPlayingCastUI() {
        castOverlay.visibility = View.VISIBLE
        val session = castCtx?.sessionManager?.currentCastSession
        val deviceName = session?.castDevice?.friendlyName ?: "Chromecast"
        castBadgeView.text = "NOW PLAYING ON ${deviceName.uppercase()}"
        castTitleView.text = media.title
        val seriesTitle = media.json.optString("seriesTitle").ifBlank { media.json.optString("seriesName") }
        castOverviewView.text = if (seriesTitle.isNotBlank() && seriesTitle != media.title) seriesTitle else media.overview
        val art = media.backdrop.ifBlank { media.poster }
        if (art.isNotBlank()) {
            castBackdropView.load(api.absolute(art)) { crossfade(true) }
        }
        playThisBtn.visibility = View.GONE
        controlsBtn.visibility = View.VISIBLE
        stopCastBtn.visibility = View.VISIBLE
    }

    private fun startIdleCycle() {
        if (remote == null) return
        if (idleCycleJob?.isActive == true) return

        castOverlay.visibility = View.VISIBLE
        castBadgeView.text = "📺  READY TO CAST"
        playThisBtn.visibility = View.VISIBLE
        controlsBtn.visibility = View.GONE
        stopCastBtn.visibility = View.VISIBLE

        loadAvailableContentIfNeeded()
        idleCycleJob = lifecycleScope.launch {
            while (isActive) {
                if (remote == null) break
                val client = remote
                if (client != null && (client.isPlaying || client.isLoadingNextItem)) break
                if (availableContent.isNotEmpty()) {
                    val item = availableContent[idleIndex % availableContent.size]
                    renderIdleItem(item)
                    idleIndex++
                }
                delay(10_000)
            }
        }
    }

    private fun stopIdleCycle() {
        idleCycleJob?.cancel()
        idleCycleJob = null
    }

    private fun renderIdleItem(item: Media) {
        currentCycledItem = item
        val art = item.backdrop.ifBlank { item.poster }
        if (art.isNotBlank()) {
            castBackdropView.load(api.absolute(art)) { crossfade(true) }
        }
        castTitleView.text = item.title
        castOverviewView.text = item.overview

        // Also sync to Chromecast receiver so the TV screen cycles with "Ready to cast"!
        val client = remote
        if (client != null && !client.isPlaying && !client.isLoadingNextItem && art.isNotBlank()) {
            try {
                val fullUrl = api.absolute(art)
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
                    putString(MediaMetadata.KEY_TITLE, item.title)
                    putString(MediaMetadata.KEY_SUBTITLE, "Ready to cast")
                    addImage(WebImage(Uri.parse(fullUrl)))
                }
                val photoInfo = MediaInfo.Builder(fullUrl)
                    .setContentType("image/jpeg")
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setMetadata(metadata)
                    .build()
                client.load(MediaLoadRequestData.Builder().setMediaInfo(photoInfo).setAutoplay(true).build())
            } catch (_: Exception) {}
        }
    }

    private fun loadAvailableContentIfNeeded() {
        if (availableContent.isNotEmpty()) return
        lifecycleScope.launch {
            try {
                val json = api.call("home")
                val list = mutableListOf<Media>()
                val heroObj = json.optJSONObject("hero")
                if (heroObj != null) list.add(Media(heroObj))
                val sections = json.optJSONArray("sections")
                if (sections != null) {
                    for (i in 0 until sections.length()) {
                        val sec = sections.getJSONObject(i)
                        val items = sec.optJSONArray("items") ?: continue
                        for (j in 0 until items.length()) {
                            val m = Media(items.getJSONObject(j))
                            val backdrop = m.backdrop.ifBlank { m.poster }
                            if (backdrop.isNotBlank() && list.none { it.id == m.id }) {
                                list.add(m)
                            }
                        }
                    }
                }
                if (list.isNotEmpty()) {
                    availableContent.clear()
                    availableContent.addAll(list)
                    if (idleCycleJob?.isActive == true && currentCycledItem == null) {
                        renderIdleItem(availableContent[0])
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun playCycledItem(item: Media) {
        media = item
        currentPlaybackId = item.playbackId
        position = 0L
        val session = castCtx?.sessionManager?.currentCastSession
        if (session?.isConnected == true) {
            stopIdleCycle()
            cast(session)
        } else {
            returnToLocal()
        }
    }

    private fun returnToLocal() {
        stopIdleCycle()
        castLoading = false
        remote?.unregisterCallback(remoteCallback)
        remote = null
        castOverlay.visibility = View.GONE
        clearMessages()
        startLocal()
    }
    private fun report(stopped: Boolean) {
        if (media.isDownload) return
        val client = remote
        val activeId = if (currentPlaybackId.isNotBlank()) currentPlaybackId else media.playbackId
        if (client != null && client.mediaInfo?.customData?.optString("itemId") != activeId) return
        if (client != null) position = client.approximateStreamPosition else player?.let { position = it.currentPosition }
        val snapshot = position
        val paused = client?.isPaused ?: (player?.isPlaying == false)
        // A final report must survive Activity destruction (Back/rotation).
        (application as HomeStreamApplication).playbackReports.launch {
            runCatching { api.progress(activeId, snapshot, paused, stopped) }
        }
    }
    private fun releaseLocal() { playerView.player = null; player?.release(); player = null; prepared = false }
    private fun clearMessages() { while (overlay.childCount > 1) overlay.removeViewAt(1) }
    private fun showMessage(text: String) { overlay.visibility = View.VISIBLE; overlay.addView(label(text, 14f)) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("position", player?.currentPosition ?: position); super.onSaveInstanceState(outState) }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
        }
    }
    override fun onStop() {
        stopIdleCycle()
        runCatching { unregisterReceiver(noisyReceiver) }
        negotiation?.cancel(); timer?.cancel()
        if (prepared || remote != null) report(remote == null)
        if (remote == null) player?.let { position = it.currentPosition; wantsPlay = it.playWhenReady }
        releaseLocal(); remote?.unregisterCallback(remoteCallback)
        castCtx?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onStop()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
