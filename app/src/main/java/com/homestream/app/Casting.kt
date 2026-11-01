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
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity

class HomeStreamCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(CastMediaOptions.Builder()
            .setExpandedControllerActivityClassName(CastControllerActivity::class.java.name).build())
        .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
internal fun hasCastVideo(client: com.google.android.gms.cast.framework.media.RemoteMediaClient?): Boolean {
    val media = client?.mediaInfo ?: return false
    val video = media.contentType?.startsWith("video/", ignoreCase = true) == true ||
        media.metadata?.mediaType in listOf(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE, com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_TV_SHOW)
    return video && (client.isPlaying || client.isPaused || client.isBuffering || client.isLoadingNextItem)
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
            .setPositiveButton(if (video) "Playback controls" else "Browse") { _, _ ->
                if (video) startActivity(android.content.Intent(activity, CastControllerActivity::class.java))
            }.setNeutralButton("Stop casting") { _, _ -> castContext(activity)?.sessionManager?.endCurrentSession(true) }
            .setNegativeButton("Close", null).create()
    }
}

class CastControllerActivity : ExpandedControllerActivity() {
    private var sdkPane: View? = null
    private var standbyPane: View? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val session = castContext(this@CastControllerActivity)?.sessionManager?.currentCastSession
            val standby = !hasCastVideo(session?.remoteMediaClient)
            sdkPane?.visibility = if (standby) View.GONE else View.VISIBLE
            standbyPane?.visibility = if (standby) View.VISIBLE else View.GONE
            if (standby) supportActionBar?.hide() else {
                supportActionBar?.show(); supportActionBar?.title = "HomeStream"
                supportActionBar?.subtitle = session?.castDevice?.friendlyName
            }
            handler.postDelayed(this, 750)
        }
    }
    override fun onStart() { super.onStart(); handler.removeCallbacks(refresh); refresh.run() }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
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
                addView(action("Browse HomeStream") { finish() })
                addView(action("Stop casting") {
                    castContext(this@CastControllerActivity)?.sessionManager?.endCurrentSession(true)
                    finish()
                })
            }
            container.addView(pane, ViewGroup.LayoutParams(-1, -1)); standbyPane = pane
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        supportActionBar?.title = "HomeStream"
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
