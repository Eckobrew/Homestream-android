package com.homestream.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Uses the same Media3 controller, timeline and settings panels as the video player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlayerActivity : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private var updates: Job? = null
    private var artwork: coil.request.Disposable? = null
    private var artworkUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this).apply { setBackgroundColor(INK) }
        playerView = PlayerView(this).apply {
            useController = true; useArtwork = true
            controllerAutoShow = true; controllerShowTimeoutMs = 0
            setShowNextButton(true); setShowPreviousButton(true)
            setShowSubtitleButton(false); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            defaultArtwork = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MusicPlayerActivity, R.drawable.nav_new)
        }
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val header = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(0xB3080808.toInt()) }
        val actions = LinearLayout(this)
        actions.addView(action("Back") { finish() })
        actions.addView(action("Queue") {
            val player = MusicService.activePlayer ?: return@action
            showPlayerChoices(playerView, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.title.toString() }, player.currentMediaItemIndex) {
                player.seekToDefaultPosition(it); player.prepare(); player.play()
            }
        })
        actions.addView(action("Stop") { stopService(Intent(this, MusicService::class.java)); finish() })
        header.addView(actions)
        val title = label("Music", 22f, true); header.addView(title)
        val subtitle = label("", 14f); header.addView(subtitle)
        frame.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
            val player = MusicService.activePlayer ?: return@setOnClickListener
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            showPlayerChoices(playerView, speeds.map { "${it}×" }, speeds.indexOf(player.playbackParameters.speed)) { player.setPlaybackSpeed(speeds[it]) }
        }
        refreshLabels = {
            val player = MusicService.activePlayer
            if (playerView.player !== player) playerView.player = player
            title.text = player?.currentMediaItem?.mediaMetadata?.title ?: "Nothing playing"
            subtitle.text = MusicService.playbackError ?: listOfNotNull(player?.currentMediaItem?.mediaMetadata?.artist, player?.currentMediaItem?.mediaMetadata?.albumTitle).joinToString(" • ")
            val url = player?.currentMediaItem?.mediaMetadata?.artworkUri?.toString()
            if (url != artworkUrl) {
                artworkUrl = url; artwork?.dispose()
                playerView.defaultArtwork = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.nav_new)
                if (url != null) artwork = imageLoader.enqueue(ImageRequest.Builder(this).data(url).target { playerView.defaultArtwork = it }.build())
            }
        }
    }
    private var refreshLabels: () -> Unit = {}
    override fun onStart() {
        super.onStart()
        if (ServerSettings(this).isKids) { finish(); return }
        updates = lifecycleScope.launch { while (isActive) { refreshLabels(); delay(300) } }
        playerView.showController()
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
    }
    override fun onStop() {
        updates?.cancel(); playerView.player = null
        super.onStop()
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    override fun onDestroy() { artwork?.dispose(); super.onDestroy() }
}
