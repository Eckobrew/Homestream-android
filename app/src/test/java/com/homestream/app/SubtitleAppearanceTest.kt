package com.homestream.app

import android.content.Context
import androidx.media3.ui.SubtitleView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleAppearanceTest {
    @Test fun savedAppearanceOverridesEmbeddedStylesAcrossInstancesAndResetRestoresThem() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("subtitle-appearance", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("customized", true).putInt("size", 2).apply()
        val view = SubtitleView(context)
        SubtitleAppearance(context).apply(view)
        fun field(name: String) = SubtitleView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)
        assertEquals(false, field("applyEmbeddedStyles"))
        assertEquals(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.25f, field("defaultTextSize") as Float, 0.0001f)
        prefs.edit().clear().apply()
        SubtitleAppearance(context).apply(view)
        assertEquals(true, field("applyEmbeddedStyles"))
        assertEquals(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, field("defaultTextSize") as Float, 0.0001f)
    }
}
