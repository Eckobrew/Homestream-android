package com.homestream.app

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import org.json.JSONArray

/** Owns audio independently of browse/activity lifecycles. Media3 owns focus and notification. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicService : MediaSessionService() {
    companion object {
        var activePlayer: ExoPlayer? = null
            private set
        var playbackError: String? = null
            private set
    }
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: ServerSettings
    private var identity = ""
    private fun identity() = "${settings.serverIdentity}|${settings.token}|${settings.profile}|${settings.policyRevision}"

    override fun onCreate() {
        super.onCreate()
        settings = ServerSettings(this)
        if (settings.isKids || settings.token.isBlank()) { stopSelf(); return }
        identity = identity()
        val api = HomeStreamApi(settings)
        val source = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(api.mediaClient()))
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = "Playback unavailable. Check your connection and try again."
                }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) playbackError = null }
                override fun onIsPlayingChanged(isPlaying: Boolean) { reportMusicSession(this@apply) }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { reportMusicSession(this@apply) }
            })
        }
        activePlayer = player
        session = MediaSession.Builder(this, player).setSessionActivity(android.app.PendingIntent.getActivity(this, 0,
            Intent(this, MusicPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)).build()
        session?.let { addSession(it) }
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) reportMusicSession(player)
            }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (identity() != identity || settings.isKids) { stopSelf(); return@launch }
                try {
                    api.refreshProfilePolicy()
                    if (identity() != identity || settings.isKids) { stopSelf(); return@launch }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is HomeStreamApi.ResponseException && e.statusCode in listOf(401, 403)) { stopSelf(); return@launch }
                    // Fail closed until the server can confirm the active profile policy.
                    player.pause()
                    playbackError = "Reconnect to confirm your profile before continuing playback."
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        val player = activePlayer ?: return result
        if (identity() != identity || settings.isKids) { stopSelf(); return START_NOT_STICKY }
        intent?.getStringExtra("tracks")?.let { raw ->
            val api = HomeStreamApi(settings)
            val tracks = runCatching { JSONArray(raw) }.getOrNull() ?: return START_NOT_STICKY
            val items = (0 until tracks.length()).mapNotNull { index ->
                val track = tracks.optJSONObject(index) ?: return@mapNotNull null
                val rawStream = track.optString("streamUrl").ifBlank {
                    val tid = track.optString("trackId").ifBlank { track.optString("id") }
                    if (tid.isNotBlank()) "/api/mobile/v1/music/stream?trackId=$tid" else ""
                }
                val url = api.absolute(rawStream)
                val path = android.net.Uri.parse(url).path.orEmpty()
                if (!api.isServerUrl(url) || (!path.endsWith("/api/mobile/v1/music/stream") && !path.endsWith("/api/v1/music/stream"))) return@mapNotNull null
                MediaItem.Builder().setMediaId(track.optString("id")).setUri(url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(track.optString("title"))
                        .setArtist(track.optString("artist")).setAlbumTitle(track.optString("album"))
                        .apply { val cover = track.optString("coverUrl"); if (cover.isNotBlank() && cover != "null") setArtworkUri(android.net.Uri.parse(api.absolute(cover))) }.build()).build()
            }
            if (items.isEmpty()) { stopSelf(); return START_NOT_STICKY }
            sendBroadcast(Intent("$packageName.MUSIC_STARTED").setPackage(packageName))
            playbackError = null
            player.setMediaItems(items, intent.getIntExtra("index", 0).coerceIn(items.indices), 0)
            player.prepare(); player.play()
        }
        when (intent?.action) {
            "toggle" -> if (player.playWhenReady) player.pause() else { player.prepare(); player.play() }
            "next" -> player.seekToNextMediaItem()
            "previous" -> player.seekToPreviousMediaItem()
            "seek" -> player.seekTo(intent.getLongExtra("position", 0).coerceAtLeast(0))
        }
        return START_NOT_STICKY
    }

    private fun reportMusicSession(player: ExoPlayer) {
        val current = player.currentMediaItem ?: return
        val meta = current.mediaMetadata
        val title = meta.title?.toString().orEmpty()
        if (title.isBlank()) return
        val subtitle = listOfNotNull(meta.artist?.toString()?.takeIf { it.isNotBlank() }, meta.albumTitle?.toString()?.takeIf { it.isNotBlank() }).joinToString(" · ")
        PlaybackSessions.reportHeartbeatAsync(
            context = this,
            mediaId = current.mediaId,
            mediaType = "music",
            title = title,
            subtitle = subtitle,
            artworkUrl = meta.artworkUri?.toString().orEmpty(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0L),
            canSeek = player.duration > 0L,
            canSkipNext = player.hasNextMediaItem(),
            canSkipPrev = player.hasPreviousMediaItem(),
        )
    }

    override fun onDestroy() {
        PlaybackSessions.reportHeartbeatAsync(this, mediaType = "idle", isStopped = true)
        scope.cancel()
        session?.run { player.release(); release() }
        session = null; activePlayer = null; playbackError = null
        super.onDestroy()
    }
}
