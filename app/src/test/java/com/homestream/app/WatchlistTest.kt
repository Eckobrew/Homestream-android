package com.homestream.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchlistTest {
    @Test fun savedTitlesStayWithTheirServerAndProfile() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("watchlists", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = object : ConnectionSettings {
            override var baseUrl = "https://first.example"
            override val token = "unused"
            override var profile = "first"
        }
        val list = Watchlist(app, settings)
        val item = Media(JSONObject("""{"id":"movie","title":"Saved title"}"""))
        assertTrue(list.toggle(item))
        assertTrue(list.contains(item))
        settings.profile = "second"
        assertTrue(list.items().isEmpty())
        settings.profile = "first"
        settings.baseUrl = "https://another.example"
        assertTrue(list.items().isEmpty())
        settings.baseUrl = "https://first.example"
        assertEquals("movie", list.items().single().id)
        assertFalse(list.toggle(item))
        assertTrue(list.items().isEmpty())
    }
}
