package com.homestream.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity
import com.google.android.gms.cast.framework.media.CastMediaOptions

class HomeStreamCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(BuildConfig.CAST_RECEIVER_APP_ID.ifBlank { CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID })
        .setCastMediaOptions(CastMediaOptions.Builder()
            .setExpandedControllerActivityClassName(CastControllerActivity::class.java.name).build())
        .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
internal fun clearCastIdle(context: Context) {
    val session = castContext(context)?.sessionManager?.currentCastSession ?: return
    if (BuildConfig.CAST_RECEIVER_APP_ID.isNotBlank()) {
        session.sendMessage("urn:x-cast:uk.eckonet.homestream", org.json.JSONObject().put("type", "idle").put("items", org.json.JSONArray()).toString())
    }
    session.remoteMediaClient?.stop()
}

internal fun sendCustomCastIdle(session: com.google.android.gms.cast.framework.CastSession, items: List<Media>, api: HomeStreamApi): Boolean {
    if (BuildConfig.CAST_RECEIVER_APP_ID.isBlank()) return false
    if (hasCastVideo(session.remoteMediaClient)) return true
    val slides = org.json.JSONArray()
    items.distinctBy { it.id }.take(5).forEach { item ->
        val art = item.backdrop.ifBlank { item.poster }
        if (art.isNotBlank()) slides.put(org.json.JSONObject().put("title", item.title).put("artwork", api.absolute(art)))
    }
    session.sendMessage("urn:x-cast:uk.eckonet.homestream", org.json.JSONObject().put("type", "idle").put("items", slides).toString())
    return true
}

internal fun hasCastVideo(client: com.google.android.gms.cast.framework.media.RemoteMediaClient?): Boolean {
    if (client == null || !client.hasMediaSession()) return false
    val media = client.mediaInfo ?: return false
    val video = media.contentType?.startsWith("video/", ignoreCase = true) == true ||
        media.contentType?.contains("mpegURL", ignoreCase = true) == true ||
        media.metadata?.mediaType in listOf(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE, com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_TV_SHOW)
    return video && client.playerState != com.google.android.gms.cast.MediaStatus.PLAYER_STATE_UNKNOWN
}

private fun Context.readyToCastView(): android.widget.LinearLayout = column().apply {
    gravity = android.view.Gravity.CENTER
    setPadding(dp(24), dp(28), dp(24), dp(28))
    addView(android.widget.ImageView(this@readyToCastView).apply {
        setImageResource(R.drawable.ic_cast)
        imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, android.widget.LinearLayout.LayoutParams(dp(56), dp(56)))
    addView(label("Ready to cast", 24f, true).apply { setPadding(0, dp(16), 0, dp(8)); gravity = android.view.Gravity.CENTER })
    val device = castContext(this@readyToCastView)?.sessionManager?.currentCastSession?.castDevice?.friendlyName ?: "Your TV"
    addView(label(device, 14f).apply { setTextColor(MUTED); gravity = android.view.Gravity.CENTER })
}

class HomeStreamRouteDialog : androidx.mediarouter.app.MediaRouteControllerDialogFragment() {
    override fun onCreateDialog(savedInstanceState: android.os.Bundle?): android.app.Dialog {
        val activity = requireActivity()
        val session = castContext(activity)?.sessionManager?.currentCastSession
        val client = session?.remoteMediaClient
        val video = hasCastVideo(client)
        val form = if (video) activity.column().apply {
            setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(20))
            addView(activity.label(client?.mediaInfo?.metadata?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE).orEmpty().ifBlank { "Now playing" }, 20f, true))
            addView(activity.label(session?.castDevice?.friendlyName ?: "Your TV", 14f))
        } else activity.readyToCastView()
        return androidx.appcompat.app.AlertDialog.Builder(activity).setView(form)
            .setPositiveButton("Remote controls") { _, _ ->
                startActivity(android.content.Intent(activity, CastControllerActivity::class.java))
            }.setNeutralButton("Stop casting") { _, _ -> castContext(activity)?.sessionManager?.endCurrentSession(true) }
            .setNegativeButton("Close", null).create()
    }
}

/** The Google Cast controller is the single full-screen playback remote. */
class CastControllerActivity : ExpandedControllerActivity() {
    private var sdkPane: View? = null
    private var standbyPane: View? = null
    private var idleVolume: android.widget.SeekBar? = null
    private var idleMute: android.widget.Button? = null
    private var idleLevel: android.widget.TextView? = null
    private var registeredClient: com.google.android.gms.cast.framework.media.RemoteMediaClient? = null
    private val clientCallback = object : com.google.android.gms.cast.framework.media.RemoteMediaClient.Callback() {
        override fun onStatusUpdated() { updateUi() }
        override fun onMetadataUpdated() { updateUi() }
    }

    private fun updateUi() {
        val session = castContext(this)?.sessionManager?.currentCastSession
        val client = session?.remoteMediaClient
        val hasVideo = hasCastVideo(client)
        val standby = !hasVideo
        val sdkTarget = if (standby) View.GONE else View.VISIBLE
        if (sdkPane?.visibility != sdkTarget) sdkPane?.visibility = sdkTarget
        val standbyTarget = if (standby) View.VISIBLE else View.GONE
        if (standbyPane?.visibility != standbyTarget) standbyPane?.visibility = standbyTarget
        if (supportActionBar?.title != "HomeStream") supportActionBar?.title = "HomeStream"
        val deviceName = session?.castDevice?.friendlyName
        if (supportActionBar?.subtitle != deviceName) supportActionBar?.subtitle = deviceName
        if (standby && session?.isConnected == true) runCatching {
            val level = (session.volume * 100).toInt().coerceIn(0, 100)
            if (idleVolume?.isPressed != true && idleVolume?.progress != level) idleVolume?.progress = level
            val levelText = "Volume · $level%"
            if (idleLevel?.text != levelText) idleLevel?.text = levelText
            val muteText = if (session.isMute) "Unmute" else "Mute"
            if (idleMute?.text != muteText) idleMute?.text = muteText
        }
    }

    override fun onStart() {
        super.onStart()
        val client = castContext(this)?.sessionManager?.currentCastSession?.remoteMediaClient
        registeredClient = client
        client?.registerCallback(clientCallback)
        updateUi()
    }

    override fun onStop() {
        registeredClient?.unregisterCallback(clientCallback)
        registeredClient = null
        super.onStop()
    }
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val content = findViewById<View>(android.R.id.content)
        val left = content.paddingLeft
        val top = content.paddingTop
        val right = content.paddingRight
        val bottom = content.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        androidx.core.view.ViewCompat.requestApplyInsets(content)
        (content as? ViewGroup)?.let { container ->
            sdkPane = container.getChildAt(0)
            val pane = readyToCastView().apply {
                setBackgroundColor(INK)
                // Device controls only: the idle receiver has no playable media.
                idleLevel = label("Volume", 14f).apply {
                    setTextColor(MUTED); setPadding(0, dp(40), 0, dp(8))
                }
                addView(idleLevel)
                idleVolume = android.widget.SeekBar(context).apply {
                    max = 100
                    contentDescription = "Cast device volume"
                    progressTintList = android.content.res.ColorStateList.valueOf(RED)
                    thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                        override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                            if (fromUser) idleLevel?.text = "Volume · $value%"
                        }
                        override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                            runCatching {
                                castContext(this@CastControllerActivity)?.sessionManager?.currentCastSession?.setVolume(bar.progress / 100.0)
                            }.onFailure { android.widget.Toast.makeText(context, "Could not change TV volume", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    })
                }
                addView(idleVolume, android.widget.LinearLayout.LayoutParams(-1, dp(48)))
                idleMute = action("Mute") {
                    runCatching {
                        castContext(this@CastControllerActivity)?.sessionManager?.currentCastSession?.let { it.setMute(!it.isMute) }
                    }.onFailure { android.widget.Toast.makeText(context, "Could not change TV volume", android.widget.Toast.LENGTH_SHORT).show() }
                }
                addView(idleMute)
            }
            container.addView(pane, ViewGroup.LayoutParams(-1, -1)); standbyPane = pane
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        supportActionBar?.title = "HomeStream"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.subtitle = castContext(this)?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        menu.add(0, 9999, 0, "Stop Casting").apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            val btn = android.widget.Button(this@CastControllerActivity).apply {
                text = "Stop Casting"
                isAllCaps = false
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.WHITE)
                background = rounded(RED, radius = dp(8).toFloat())
                setPadding(dp(14), dp(4), dp(14), dp(4))
                setOnClickListener {
                    CastContext.getSharedInstance(this@CastControllerActivity).sessionManager.endCurrentSession(true)
                    finish()
                }
            }
            actionView = btn
        }
        return true
    }
}

fun castContext(context: Context): CastContext? {
    if (context.isTelevision() || GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) return null
    return runCatching { CastContext.getSharedInstance(context) }.getOrNull()
}
fun castButton(context: Context): MediaRouteButton? {
    if (castContext(context) == null) return null
    return MediaRouteButton(context).apply {
        contentDescription = "Cast to a Chromecast device"
        background = null
        CastButtonFactory.setUpMediaRouteButton(context, this)
        dialogFactory = object : androidx.mediarouter.app.MediaRouteDialogFactory() {
            override fun onCreateControllerDialogFragment(): androidx.mediarouter.app.MediaRouteControllerDialogFragment = HomeStreamRouteDialog()
        }
    }
}

/** Use the SDK's XML inflation lifecycle, including when rebuilding browse screens. */
internal fun AppCompatActivity.castMiniControllerView(parent: ViewGroup): View {
    val manager = supportFragmentManager
    if (manager.isStateSaved) return FrameLayout(this).apply { id = R.id.cast_mini }
    manager.findFragmentByTag("cast-mini")?.let { manager.beginTransaction().remove(it).commitNow() }
    val sdk = layoutInflater.inflate(R.layout.homestream_cast_mini_controller, parent, false)
    val owner = this
    return object : FrameLayout(this) {
        private val ready = column().apply {
            background = rounded(0xFF202020.toInt(), radius = dp(10).toFloat())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val row = android.widget.LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_cast); imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            }, android.widget.LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
            val copy = column().apply {
                addView(label("Ready to cast", 15f, true))
                addView(label("Choose something to watch on your TV", 11.5f).apply { setTextColor(MUTED) })
            }
            row.addView(copy); addView(row)
            isClickable = true; isFocusable = true
            setOnClickListener { HomeStreamRouteDialog().show(owner.supportFragmentManager, "homestream-cast-status") }
        }
        private val poll = object : Runnable {
            override fun run() {
                val session = castContext(context)?.sessionManager?.currentCastSession
                val connected = session?.isConnected == true
                val video = hasCastVideo(session?.remoteMediaClient)
                visibility = if (connected) View.VISIBLE else View.GONE
                sdk.visibility = if (video) View.VISIBLE else View.GONE
                ready.visibility = if (connected && !video) View.VISIBLE else View.GONE
                postDelayed(this, 750)
            }
        }
        private val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) { removeCallbacks(poll); poll.run() }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) removeCallbacks(poll)
        }
        init { addView(sdk); addView(ready, LayoutParams(-1, -2)); visibility = View.GONE }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow(); owner.lifecycle.addObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) poll.run()
        }
        override fun onDetachedFromWindow() {
            removeCallbacks(poll); owner.lifecycle.removeObserver(observer); super.onDetachedFromWindow()
        }
    }
}
