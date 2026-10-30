package com.homestream.app

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var prepared = false
    private var transcode = false
    private var castLoading = false
    private var wantsPlay = true
    private var timer: Job? = null
    private var negotiation: Job? = null
    private var remote: RemoteMediaClient? = null
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) player?.pause()
        }
    }
    private val context by lazy { castContext(this) }
    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            remote?.let { client ->
                if (client.mediaInfo?.customData?.optString("itemId") == media.playbackId) {
                    position = client.approximateStreamPosition
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        media = runCatching { Media(JSONObject(intent.getStringExtra("media") ?: "")) }.getOrElse { finish(); return }
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
        overlay = column().apply {
            setPadding(dp(20), dp(20), dp(20), dp(12)); setBackgroundColor(0xBB080808.toInt())
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(action("‹ Back") { finish() })
        bar.addView(label(media.title, 18f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (!media.isDownload) castButton(this)?.let { bar.addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }
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
        context?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        val session = context?.sessionManager?.currentCastSession
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
                val stream = if (media.isDownload) JSONObject().put("streamUrl", media.downloadUrl)
                    else api.call("playback", mapOf("itemId" to media.playbackId, "forceTranscode" to transcode.toString()))
                val url = stream.optString("streamUrl")
                if (url.isBlank()) error("No playable stream returned by Jellyfin.")
                val tracks = if (media.isDownload) emptyList() else subtitleItems()
                val item = MediaItem.Builder().setUri(api.absolute(url)).setMediaId(media.playbackId)
                    .setSubtitleConfigurations(tracks).apply {
                        if (stream.optBoolean("isTranscoding")) setMimeType(MimeTypes.APPLICATION_M3U8)
                    }.build()
                releaseLocal()
                player = ExoPlayer.Builder(this@PlayerActivity).build().also { exo ->
                    playerView.player = exo
                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) { prepared = true; clearMessages() }
                            if (state == Player.STATE_ENDED) { position = exo.duration.coerceAtLeast(0); report(true) }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            position = exo.currentPosition
                            clearMessages()
                            showMessage("Playback failed. Check that this device can reach Jellyfin.")
                            if (media.isDownload) {
                                showMessage("In-progress downloads may buffer or be unseekable. Wait for more data and retry.")
                                overlay.addView(action("Retry") { startLocal() })
                            } else overlay.addView(action("Retry with compatible HLS") { transcode = true; startLocal() })
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
    private suspend fun subtitleItems(): List<MediaItem.SubtitleConfiguration> = try {
        api.call("subtitles", mapOf("itemId" to media.playbackId)).optJSONArray("subtitles")?.let { tracks ->
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
    private fun showCastControls() {
        showMessage("Playing on Chromecast")
        overlay.addView(action("Cast playback controls") { startActivity(android.content.Intent(this, CastControllerActivity::class.java)) })
        overlay.addView(action("Stop casting and play here") { context?.sessionManager?.endCurrentSession(true) })
    }
    private fun returnToLocal() {
        castLoading = false; remote?.unregisterCallback(remoteCallback); remote = null
        startLocal()
    }
    private fun report(stopped: Boolean) {
        if (media.isDownload) return
        val client = remote
        if (client != null && client.mediaInfo?.customData?.optString("itemId") != media.playbackId) return
        if (client != null) position = client.approximateStreamPosition else player?.let { position = it.currentPosition }
        val snapshot = position
        val paused = client?.isPaused ?: (player?.isPlaying == false)
        // A final report must survive Activity destruction (Back/rotation).
        val id = media.playbackId
        (application as HomeStreamApplication).playbackReports.launch {
            runCatching { api.progress(id, snapshot, paused, stopped) }
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
        runCatching { unregisterReceiver(noisyReceiver) }
        negotiation?.cancel(); timer?.cancel()
        if (prepared || remote != null) report(remote == null)
        if (remote == null) player?.let { position = it.currentPosition; wantsPlay = it.playWhenReady }
        releaseLocal(); remote?.unregisterCallback(remoteCallback)
        context?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onStop()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
