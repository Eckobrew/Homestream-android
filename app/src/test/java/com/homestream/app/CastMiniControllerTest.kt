package com.homestream.app

import android.widget.FrameLayout
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.cast.framework.media.widget.MiniControllerFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastMiniControllerTest {
    @Test fun receiverUsesTheExplicitOverrideOrGoogleDefault() {
        val options = HomeStreamCastOptions().getCastOptions(org.robolectric.RuntimeEnvironment.getApplication())
        assertEquals(BuildConfig.CAST_RECEIVER_APP_ID.ifBlank { "CC1AD845" }, options.receiverApplicationId)
    }

    @Test fun castControllerSurvivesActivityRecreation() {
        Robolectric.buildActivity(CastMiniControllerHostActivity::class.java).setup().use { controller ->
            controller.recreate()
            val fragment = controller.get().supportFragmentManager.findFragmentByTag("cast-mini") as MiniControllerFragment
            assertNotNull(fragment.view)
            for (slot in 0 until fragment.buttonSlotCount) fragment.getButtonTypeAt(slot)
        }
    }
    @Test fun castControllerCanBeCreatedOnPhoneLaunch() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_HomeStream)
        controller.setup().use {
            val activity = it.get()
            val parent = FrameLayout(activity)
            activity.setContentView(parent)
            parent.addView(activity.castMiniControllerView(parent))
            val fragment = activity.supportFragmentManager.findFragmentByTag("cast-mini") as MiniControllerFragment
            assertNotNull(fragment.view)
            for (slot in 0 until fragment.buttonSlotCount) fragment.getButtonTypeAt(slot)
            // Browsing rebuilds the shell; inflation must work a second time too.
            parent.removeAllViews()
            parent.addView(activity.castMiniControllerView(parent))
            val replacement = activity.supportFragmentManager.findFragmentByTag("cast-mini") as MiniControllerFragment
            assertNotSame(fragment, replacement)
            assertNotNull(replacement.view)
            for (slot in 0 until replacement.buttonSlotCount) replacement.getButtonTypeAt(slot)
        }
    }
}

class CastMiniControllerHostActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        setTheme(R.style.Theme_HomeStream)
        super.onCreate(state)
        val parent = FrameLayout(this)
        setContentView(parent)
        parent.addView(castMiniControllerView(parent))
    }
}
