package com.homestream.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {
    @Before fun clearPairing() {
        RuntimeEnvironment.getApplication().getSharedPreferences("server", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun firstLaunchShowsPairingAndValidatesMissingServer() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            val button = dialog.findViewById<Button>(android.R.id.button1)
            assertEquals("Connect", button.text.toString())
            button.performClick()
            val inputs = descendants(dialog.window!!.decorView).filterIsInstance<EditText>()
            assertEquals(2, inputs.size)
            assertNotNull(inputs[0].error)
            assertTrue(dialog.isShowing)
            assertFalse(controller.get().isFinishing)
            assertNotNull(controller.get().findViewById<View>(R.id.phone_header))
            assertNotNull(controller.get().findViewById<View>(R.id.phone_bottom_navigation))
            assertNull(controller.get().findViewById<View>(R.id.tv_navigation_rail))
            dialog.dismiss()
        }
    }
    @Test fun setupOffersExternalServerLoginOption() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            val allButtons = descendants(dialog.window!!.decorView).filterIsInstance<Button>()
            val extButton = allButtons.firstOrNull { it.text.toString().contains("External Server") }
            assertNotNull("Setup dialog must have an External Server option", extButton)
            extButton!!.performClick()
            val jellyfinDialog = ShadowDialog.getLatestDialog()
            assertNotNull(jellyfinDialog)
            assertTrue(jellyfinDialog.isShowing)
            val extInputs = descendants(jellyfinDialog.window!!.decorView).filterIsInstance<EditText>()
            // External URL, Username, Password
            assertTrue(extInputs.size >= 3)
            jellyfinDialog.dismiss()
            dialog.dismiss()
        }
    }
    @Test fun televisionLaunchOffersRemoteFocusableNavigationWithoutTouch() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(manager).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertTrue(controller.get().isTelevision())
            assertNotNull(controller.get().findViewById<View>(R.id.tv_navigation_rail))
            assertNull(controller.get().findViewById<View>(R.id.phone_bottom_navigation))
            val buttons = descendants(controller.get().window.decorView).filterIsInstance<Button>()
            for (title in listOf("Home", "Movies", "TV Shows", "Search", "Profiles", "Settings")) {
                val button = buttons.first { it.contentDescription?.toString() == title }
                assertTrue("$title must be reachable by D-pad", button.isFocusable)
                assertTrue("$title must support remote activation", button.isClickable)
            }
            ShadowDialog.getLatestDialog().dismiss()
        }
    }
}
