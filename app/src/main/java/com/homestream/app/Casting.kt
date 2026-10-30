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
class CastControllerActivity : ExpandedControllerActivity()

fun castContext(context: Context): CastContext? {
    if (context.isTelevision() || GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) return null
    return runCatching { CastContext.getSharedInstance(context) }.getOrNull()
}
fun castButton(context: Context): MediaRouteButton? {
    if (castContext(context) == null) return null
    return MediaRouteButton(context).apply {
        contentDescription = "Cast to a Chromecast device"
        setBackgroundColor(0xFF202020.toInt())
        CastButtonFactory.setUpMediaRouteButton(context, this)
    }
}

/** Use the SDK's XML inflation lifecycle, including when rebuilding browse screens. */
internal fun AppCompatActivity.castMiniControllerView(parent: ViewGroup): View {
    val manager = supportFragmentManager
    if (manager.isStateSaved) return FrameLayout(this).apply { id = R.id.cast_mini }
    manager.findFragmentByTag("cast-mini")?.let { manager.beginTransaction().remove(it).commitNow() }
    return layoutInflater.inflate(R.layout.homestream_cast_mini_controller, parent, false)
}
