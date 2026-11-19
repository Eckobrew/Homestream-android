package com.homestream.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TvRemoteActivity : AppCompatActivity() {
    private val bgColor = INK
    private val cardColor = 0xFF181818.toInt()
    private var selectedDeviceId: String = ""
    private var availableTvs: List<NetworkSession> = emptyList()
    private var activeSession: NetworkSession? = null
    private var isScrubbing = false

    private lateinit var deviceBadge: TextView
    private lateinit var artworkView: ImageView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeView: TextView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var stopBtn: Button
    private lateinit var transportCard: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedDeviceId = intent.getStringExtra("deviceId").orEmpty()

        val root = column().apply {
            setBackgroundColor(bgColor)
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(16) + safe.left, dp(14) + safe.top, dp(16) + safe.right, dp(24) + safe.bottom)
            insets
        }

        // Top bar
        val topBar = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val backBtn = ImageButton(this).apply {
            contentDescription = "Back"
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = null
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        topBar.addView(label("TV Remote", 20f, true).apply {
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        deviceBadge = TextView(this).apply {
            text = "Searching..."
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(0xFF242424.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
            setOnClickListener { showTvPicker() }
        }
        topBar.addView(deviceBadge)
        root.addView(topBar)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val body = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Now playing card
        val nowPlayingCard = column().apply {
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x22FFFFFF)
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val mediaRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(0xFF262626.toInt())
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
        }
        mediaRow.addView(artworkView, LinearLayout.LayoutParams(dp(64), dp(64)))

        val metaCol = column().apply { setPadding(dp(14), 0, 0, 0) }
        titleView = label("No active TV playback", 16f, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = label("Connect to an Android TV on your home network", 13f).apply {
            setTextColor(MUTED)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        metaCol.addView(titleView)
        metaCol.addView(subtitleView)
        mediaRow.addView(metaCol, LinearLayout.LayoutParams(0, -2, 1f))
        nowPlayingCard.addView(mediaRow)

        seekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(RED)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(0, dp(12), 0, dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = activeSession?.durationMs ?: 0L
                        val pos = (dur * progress) / 1000L
                        timeView.text = "${formatDuration(pos)} / ${formatDuration(dur)}"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isScrubbing = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isScrubbing = false
                    val dur = activeSession?.durationMs ?: 0L
                    if (dur > 0L && sb != null) {
                        val targetMs = (dur * sb.progress) / 1000L
                        sendRemote("seekTo", positionMs = targetMs)
                    }
                }
            })
        }
        nowPlayingCard.addView(seekBar, LinearLayout.LayoutParams(-1, -2))

        timeView = label("0:00 / 0:00", 12f).apply {
            setTextColor(MUTED)
            gravity = Gravity.END
        }
        nowPlayingCard.addView(timeView, LinearLayout.LayoutParams(-1, -2))
        body.addView(nowPlayingCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        // Transport controls
        transportCard = row().apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        }
        val prevBtn = roundActionButton("-10s / Prev", R.drawable.ic_skip_previous, 48) {
            val s = activeSession
            if (s != null && s.mediaType == "video" && !s.canSkipPrev) {
                sendRemote("seekBy", deltaMs = -10_000L)
            } else {
                sendRemote("previous")
            }
        }
        playPauseBtn = ImageButton(this).apply {
            contentDescription = "Play or pause"
            setImageResource(R.drawable.ic_play_arrow)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(RED)
            }
            setOnClickListener { sendRemote("togglePlayPause") }
        }
        val nextBtn = roundActionButton("+30s / Next", R.drawable.ic_skip_next, 48) {
            val s = activeSession
            if (s != null && s.mediaType == "video" && !s.canSkipNext) {
                sendRemote("seekBy", deltaMs = 30_000L)
            } else {
                sendRemote("next")
            }
        }
        stopBtn = Button(this).apply {
            text = "Stop"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF262626.toInt())
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener { sendRemote("stop") }
        }

        transportCard.addView(prevBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(16) })
        transportCard.addView(playPauseBtn, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(16) })
        transportCard.addView(nextBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(16) })
        transportCard.addView(stopBtn, LinearLayout.LayoutParams(dp(68), dp(40)))
        body.addView(transportCard)

        // Volume controls
        val volRow = row().apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        volRow.addView(pillButton("Vol -") { sendRemote("volumeDown") }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) })
        volRow.addView(pillButton("Mute") { sendRemote("muteToggle") }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) })
        volRow.addView(pillButton("Vol +") { sendRemote("volumeUp") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        body.addView(volRow, LinearLayout.LayoutParams(-1, -2))

        // D-pad card
        val dpadCard = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0x22FFFFFF)
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        dpadCard.addView(dpadButton("▲") { sendRemote("dpadUp") }, LinearLayout.LayoutParams(dp(64), dp(54)).apply { bottomMargin = dp(8) })

        val midRow = row().apply { gravity = Gravity.CENTER }
        midRow.addView(dpadButton("◀") { sendRemote("dpadLeft") }, LinearLayout.LayoutParams(dp(64), dp(54)).apply { marginEnd = dp(10) })
        midRow.addView(Button(this).apply {
            text = "OK"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF333333.toInt())
                setStroke(dp(1), 0x44FFFFFF)
            }
            setOnClickListener { sendRemote("dpadCenter") }
        }, LinearLayout.LayoutParams(dp(70), dp(70)).apply { marginEnd = dp(10) })
        midRow.addView(dpadButton("▶") { sendRemote("dpadRight") }, LinearLayout.LayoutParams(dp(64), dp(54)))
        dpadCard.addView(midRow, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })

        dpadCard.addView(dpadButton("▼") { sendRemote("dpadDown") }, LinearLayout.LayoutParams(dp(64), dp(54)).apply { bottomMargin = dp(16) })

        val navRow = row().apply { gravity = Gravity.CENTER }
        navRow.addView(pillButton("Back") { sendRemote("back") }, LinearLayout.LayoutParams(dp(110), dp(42)).apply { marginEnd = dp(12) })
        navRow.addView(pillButton("TV Home") { sendRemote("home") }, LinearLayout.LayoutParams(dp(110), dp(42)))
        dpadCard.addView(navRow)

        body.addView(dpadCard, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        startPolling()
    }

    private fun startPolling() {
        lifecycleScope.launch {
            while (isActive) {
                val snapshot = PlaybackSessions.fetchSessions(this@TvRemoteActivity)
                if (!snapshot.localNetwork) {
                    availableTvs = emptyList()
                    activeSession = null
                    renderState("Not on home network")
                } else {
                    availableTvs = (snapshot.tvDevices + snapshot.sessions.filter { it.deviceType == "web" })
                        .distinctBy { it.deviceId }
                    if (selectedDeviceId.isBlank() || availableTvs.none { it.deviceId == selectedDeviceId }) {
                        selectedDeviceId = availableTvs.firstOrNull { !it.isIdle }?.deviceId
                            ?: availableTvs.firstOrNull()?.deviceId.orEmpty()
                    }
                    activeSession = availableTvs.firstOrNull { it.deviceId == selectedDeviceId }
                    renderState(null)
                }
                delay(2_000)
            }
        }
    }

    private fun renderState(networkError: String?) {
        val session = activeSession
        if (networkError != null) {
            deviceBadge.text = "Offline"
            titleView.text = networkError
            subtitleView.text = "Connect to your home Wi-Fi to control your Android TV"
            artworkView.setImageDrawable(null)
            seekBar.visibility = View.GONE
            timeView.visibility = View.GONE
            return
        }
        if (session == null) {
            deviceBadge.text = "No TV Found"
            titleView.text = "No Android TV detected"
            subtitleView.text = "Open HomeStream on your Android TV on the same profile"
            artworkView.setImageDrawable(null)
            seekBar.visibility = View.GONE
            timeView.visibility = View.GONE
            return
        }

        deviceBadge.text = if (availableTvs.size > 1) "${session.deviceName} ▾" else session.deviceName
        if (session.isIdle) {
            titleView.text = "Connected to ${session.deviceName}"
            subtitleView.text = "Ready — use the D-pad below to browse or start playback"
            artworkView.setImageDrawable(null)
            seekBar.visibility = View.GONE
            timeView.visibility = View.GONE
            playPauseBtn.setImageResource(R.drawable.ic_play_arrow)
            stopBtn.isEnabled = false
            stopBtn.alpha = 0.5f
        } else {
            titleView.text = session.title
            subtitleView.text = session.subtitle.ifBlank { session.deviceName }
            val api = HomeStreamApi(ServerSettings(this))
            if (session.artworkUrl.isNotBlank()) {
                artworkView.load(api.absolute(session.artworkUrl))
            } else {
                artworkView.setImageDrawable(null)
            }
            playPauseBtn.setImageResource(if (session.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            stopBtn.isEnabled = true
            stopBtn.alpha = 1f

            if (session.durationMs > 0L) {
                seekBar.visibility = View.VISIBLE
                timeView.visibility = View.VISIBLE
                if (!isScrubbing) {
                    val progress = ((session.positionMs * 1000L) / session.durationMs).toInt().coerceIn(0, 1000)
                    seekBar.progress = progress
                    timeView.text = "${formatDuration(session.positionMs)} / ${formatDuration(session.durationMs)}"
                }
            } else {
                seekBar.visibility = View.GONE
                timeView.visibility = View.GONE
            }
        }
    }

    private fun showTvPicker() {
        if (availableTvs.size <= 1) return
        val names = availableTvs.map {
            if (it.isIdle) "${it.deviceName} (Idle)" else "${it.deviceName} · ${it.title}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Android TV")
            .setItems(names) { _, which ->
                selectedDeviceId = availableTvs[which].deviceId
                activeSession = availableTvs[which]
                renderState(null)
            }
            .show()
    }

    private fun sendRemote(type: String, positionMs: Long? = null, deltaMs: Long? = null) {
        val target = selectedDeviceId
        if (target.isBlank()) return
        lifecycleScope.launch {
            PlaybackSessions.sendCommand(this@TvRemoteActivity, target, type, positionMs, deltaMs)
        }
    }

    private fun roundActionButton(desc: String, iconRes: Int, sizeDp: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            contentDescription = desc
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF262626.toInt())
            }
            setOnClickListener { onClick() }
        }

    private fun pillButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(0xFF262626.toInt())
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), 0x22FFFFFF)
        }
        setOnClickListener { onClick() }
    }

    private fun dpadButton(symbol: String, onClick: () -> Unit): Button = Button(this).apply {
        text = symbol
        textSize = 18f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(0xFF262626.toInt())
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0x22FFFFFF)
        }
        setOnClickListener { onClick() }
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
