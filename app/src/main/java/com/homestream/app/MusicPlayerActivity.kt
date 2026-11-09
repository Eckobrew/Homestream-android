package com.homestream.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** HomeStream controls over the service-owned Media3 player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlayerActivity : AppCompatActivity() {
    private var updates: Job? = null
    private var refresh: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ServerSettings(this).isKids) { finish(); return }
        val television = isTelevision()
        val wide = television || resources.configuration.screenWidthDp >= 620
        val gutter = dp(if (television) 48 else 20)
        val root = column().apply { setBackgroundColor(INK) }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(gutter, dp(6), gutter, dp(6))
        }
        top.addView(control("Back", R.drawable.ic_arrow_back) { finish() })
        top.addView(label("HOMESTREAM", 20f, true).apply {
            setTextColor(RED); letterSpacing = 0.08f; setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)

        val body = column().apply { setPadding(gutter, dp(12), gutter, dp(28)) }
        val viewport = ScrollView(this).apply { clipToPadding = false; addView(body) }
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        val featured = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFF141414.toInt(), radius = dp(12).toFloat())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        body.addView(featured, LinearLayout.LayoutParams(-1, -2))
        val coverSize = dp(if (wide) 240 else (resources.configuration.screenWidthDp - 84).coerceIn(120, 280))
        val cover = LibraryArtwork(this)
        featured.addView(cover, LinearLayout.LayoutParams(coverSize, coverSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            if (wide) marginEnd = dp(24) else bottomMargin = dp(16)
        })
        val panel = column()
        featured.addView(panel, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        val SPOTIFY_GREEN = 0xFF1DB954.toInt()
        val SPOTIFY_GREEN_LIGHT = 0xFF1ED760.toInt()

        panel.addView(label("MUSIC", 11f, true).apply { setTextColor(SPOTIFY_GREEN); letterSpacing = 0.12f })
        val title = label("Nothing playing", if (wide) 28f else 24f, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        }
        panel.addView(title)
        val subtitle = label("Choose an album or track to begin listening", 14f).apply { setTextColor(MUTED); setPadding(0, dp(4), 0, dp(12)) }
        panel.addView(subtitle)

        val timeline = SeekBar(this).apply {
            max = 1000
            contentDescription = "Playback position"
            progressTintList = ColorStateList.valueOf(SPOTIFY_GREEN)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF333333.toInt())
            thumbTintList = ColorStateList.valueOf(SPOTIFY_GREEN)
        }
        panel.addView(timeline, LinearLayout.LayoutParams(-1, dp(38)))
        val times = LinearLayout(this).apply { setPadding(dp(6), 0, dp(6), 0) }
        val elapsed = label("0:00", 12f).apply { setTextColor(MUTED) }
        val duration = label("0:00", 12f).apply { setTextColor(MUTED) }
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(duration)
        panel.addView(times)
        var tracking = false
        fun seek(progress: Int) {
            MusicService.activePlayer?.let { player ->
                if (player.isCurrentMediaItemSeekable && player.duration > 0) player.seekTo(player.duration * progress / timeline.max)
            }
        }
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, user: Boolean) {
                if (user && !tracking) seek(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { tracking = true }
            override fun onStopTrackingTouch(bar: SeekBar) { tracking = false; seek(bar.progress) }
        })
        val transport = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) }
        val shuffle = control("Shuffle", R.drawable.ic_shuffle) {
            MusicService.activePlayer?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        val previous = control("Previous track", R.drawable.ic_skip_previous) { MusicService.activePlayer?.seekToPrevious() }
        val play = control("Play", R.drawable.ic_play_arrow, primary = true) { togglePlayback() }
        val next = control("Next track", R.drawable.ic_skip_next) { MusicService.activePlayer?.seekToNextMediaItem() }
        val repeat = control("Repeat", R.drawable.ic_repeat) {
            MusicService.activePlayer?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        transport.addView(shuffle)
        transport.addView(previous)
        transport.addView(play)
        transport.addView(next)
        transport.addView(repeat)
        panel.addView(transport)

        val secondaryActions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) }
        val speed = control("Playback speed", R.drawable.ic_speed) { playbackSpeed() }
        val stop = control("Stop", R.drawable.ic_stop) { stopService(Intent(this, MusicService::class.java)); finish() }
        secondaryActions.addView(speed)
        secondaryActions.addView(stop)
        panel.addView(secondaryActions)
        val queueHeading = label("Play queue", 20f, true).apply { setPadding(0, dp(20), 0, dp(10)) }
        val queue = column()
        body.addView(queueHeading); body.addView(queue)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        var artwork: String? = null
        var queueItems = emptyList<Pair<String, String>>()
        var currentIndex = -1
        refresh = {
            val player = MusicService.activePlayer
            val metadata = player?.currentMediaItem?.mediaMetadata
            val url = metadata?.artworkUri?.toString()
            if (url != artwork) { artwork = url; cover.show(url) }
            title.text = metadata?.title ?: "Nothing playing"
            subtitle.text = MusicService.playbackError ?: if (metadata == null) "Choose a song from Music" else
                listOfNotNull(metadata.artist, metadata.albumTitle).filter { it.isNotBlank() }.joinToString(" • ")
            val length = player?.duration?.coerceAtLeast(0) ?: 0L
            val position = player?.currentPosition ?: 0L
            elapsed.text = formatTime(if (tracking) length * timeline.progress / timeline.max else position)
            duration.text = formatTime(length)
            timeline.isEnabled = player?.isCurrentMediaItemSeekable == true && length > 0
            if (!tracking) timeline.progress = if (length > 0) (position * timeline.max / length).toInt().coerceIn(0, timeline.max) else 0
            val playing = player?.playWhenReady == true && player.playbackState !in listOf(Player.STATE_IDLE, Player.STATE_ENDED)
            play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            play.contentDescription = if (playing) "Pause" else "Play"
            val available = player?.currentMediaItem != null
            listOf(play, speed, stop, previous, next, shuffle, repeat).forEach { view ->
                view.isEnabled = available && (view !== next || player?.hasNextMediaItem() == true)
                view.alpha = if (view.isEnabled) 1f else .4f
            }

            val shuffleEnabled = player?.shuffleModeEnabled == true
            shuffle.setColorFilter(if (shuffleEnabled) SPOTIFY_GREEN else Color.WHITE)
            shuffle.alpha = if (shuffleEnabled) 1f else 0.55f

            val repeatMode = player?.repeatMode ?: Player.REPEAT_MODE_OFF
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> {
                    repeat.setImageResource(R.drawable.ic_repeat_one)
                    repeat.setColorFilter(SPOTIFY_GREEN)
                    repeat.alpha = 1f
                }
                Player.REPEAT_MODE_ALL -> {
                    repeat.setImageResource(R.drawable.ic_repeat)
                    repeat.setColorFilter(SPOTIFY_GREEN)
                    repeat.alpha = 1f
                }
                else -> {
                    repeat.setImageResource(R.drawable.ic_repeat)
                    repeat.setColorFilter(Color.WHITE)
                    repeat.alpha = 0.55f
                }
            }
            val items = player?.let { p -> (0 until p.mediaItemCount).map { i ->
                p.getMediaItemAt(i).let { it.mediaId to it.mediaMetadata.title.toString() }
            } }.orEmpty()
            val index = player?.currentMediaItemIndex ?: -1
            if (items != queueItems || index != currentIndex) {
                val focus = queue.indexOfChild(queue.focusedChild)
                queueItems = items; currentIndex = index
                queue.removeAllViews()
                items.forEachIndexed { i, (_, name) ->
                    queue.addView(musicTrackRow(name, if (i == index) "Now playing" else "", i + 1, i == index) {
                        MusicService.activePlayer?.takeIf { i < it.mediaItemCount }?.apply { seekToDefaultPosition(i); prepare(); play() }
                    })
                }
                if (focus in 0 until queue.childCount) queue.getChildAt(focus).requestFocus()
            }
            queueHeading.visibility = if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
        if (television) play.requestFocus()
    }

    private fun control(label: String, icon: Int, primary: Boolean = false, click: () -> Unit) = ImageButton(this).apply {
        val SPOTIFY_GREEN = 0xFF1DB954.toInt()
        val surface = if (primary) SPOTIFY_GREEN else 0xFF242424.toInt()
        contentDescription = label
        setImageResource(icon); setColorFilter(if (primary) Color.BLACK else Color.WHITE)
        val radius = if (primary) dp(28).toFloat() else dp(12).toFloat()
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(if (primary) 0xFF1ED760.toInt() else 0xFF3E3E3E.toInt(), if (primary) Color.WHITE else SPOTIFY_GREEN, radius))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(SPOTIFY_GREEN, radius = radius))
            addState(intArrayOf(), rounded(surface, radius = radius))
        }
        val size = dp(if (primary) 56 else 44)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6); marginEnd = dp(6) }
        setOnClickListener { click() }
    }

    private fun playbackSpeed() {
        val player = MusicService.activePlayer ?: return
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this).setTitle("Playback speed")
            .setSingleChoiceItems(speeds.map { "${it}×" }.toTypedArray(), speeds.indexOf(player.playbackParameters.speed)) { dialog, index ->
                MusicService.activePlayer?.setPlaybackSpeed(speeds[index]); dialog.dismiss()
            }.setNegativeButton("Back", null).show()
    }

    private fun togglePlayback() {
        MusicService.activePlayer?.apply {
            if (playbackState == Player.STATE_ENDED) { seekToDefaultPosition(); play() }
            else if (playWhenReady && playbackState != Player.STATE_IDLE) pause()
            else { prepare(); play() }
        }
    }

    override fun onStart() {
        super.onStart()
        if (ServerSettings(this).isKids) { finish(); return }
        updates = lifecycleScope.launch { while (isActive) { refresh(); delay(300) } }
    }
    override fun onStop() { updates?.cancel(); super.onStop() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val player = MusicService.activePlayer
            if (player != null) when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { togglePlayback(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.prepare(); player.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { player.seekToNextMediaItem(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { player.seekToPrevious(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekForward(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBack(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60) else "%d:%02d".format(minutes, seconds % 60)
    }
}
