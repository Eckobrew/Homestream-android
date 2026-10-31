package com.homestream.app

import android.content.Intent
import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerNavigationTest {
    @Test fun showingCastStatusDoesNotLaunchAnotherActivity() {
        val media = JSONObject().put("id", "movie").put("title", "Movie").put("type", "Movie")
        val intent = Intent(RuntimeEnvironment.getApplication(), PlayerActivity::class.java).putExtra("media", media.toString())
        Robolectric.buildActivity(PlayerActivity::class.java, intent).create().use { controller ->
            val activity = controller.get()
            PlayerActivity::class.java.getDeclaredMethod("showCastControls").apply { isAccessible = true }.invoke(activity)
            assertNull("Returning from Cast controls must not launch them again", shadowOf(activity).nextStartedActivity)
        }
    }
    @Test fun recreationRestoresTheAdvancedEpisodeAndItsPosition() {
        val initial = JSONObject().put("id", "episode-one").put("title", "First").put("type", "Episode")
        val advanced = JSONObject().put("id", "episode-two").put("title", "Second").put("type", "Episode")
        val intent = Intent(RuntimeEnvironment.getApplication(), PlayerActivity::class.java).putExtra("media", initial.toString())
        val state = Bundle().apply { putString("media", advanced.toString()); putLong("position", 42000) }
        Robolectric.buildActivity(PlayerActivity::class.java, intent).create(state).use { controller ->
            val activity = controller.get()
            val restored = PlayerActivity::class.java.getDeclaredField("media").apply { isAccessible = true }.get(activity) as Media
            assertEquals("episode-two", restored.id)
            assertEquals(42000L, PlayerActivity::class.java.getDeclaredField("position").apply { isAccessible = true }.getLong(activity))
        }
    }
}
