package com.homestream.app

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
class MediaHubTest {
    @Before fun clearSettings() {
        RuntimeEnvironment.getApplication().getSharedPreferences("server", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun invoke(activity: MainActivity, method: String, vararg args: Any) {
        MainActivity::class.java.declaredMethods.first { it.name == method && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    }
    private fun waitUntil(condition: () -> Boolean) {
        repeat(250) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for media hub", condition())
    }
    private fun api(activity: MainActivity, server: MockWebServer) {
        val settings = object : ConnectionSettings {
            override val baseUrl = server.url("/").toString()
            override val token = "fixture-token"
            override var profile = ""
        }
        MainActivity::class.java.getDeclaredField("api").apply { isAccessible = true }.set(activity, HomeStreamApi(settings))
    }
    @Test fun musicAndDiscoverReplaceRequestedAndSidebarMusicPrecedesYoutube() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get(); ShadowDialog.getLatestDialog().dismiss()
            val nav = activity.findViewById<View>(R.id.phone_bottom_navigation)
            val labels = descendants(nav).filterIsInstance<TextView>().map { it.text.toString() }
            assertEquals(listOf("Home", "TV Shows", "Movies", "Discover", "Search"), labels)
            val labelsAll = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labelsAll.indexOfLast { it == "Music" } < labelsAll.indexOf("YouTube"))
            assertFalse(labelsAll.contains("Requested"))
        }
    }
    @Test fun musicLibraryPaginatesAndSearchUsesCatalogQuery() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    return MockResponse().setBody(when {
                        url.encodedPath.endsWith("music/album") -> """{"tracks":[{"id":"track","title":"Fixture song","streamUrl":"/api/mobile/v1/music/stream?trackId=track"}]}"""
                        url.encodedPath.endsWith("home") -> "{\"requestSections\":[]}"
                        url.encodedPath.endsWith("music") && url.queryParameter("q") != null -> """{"configured":true,"albums":[{"id":"search","title":"Catalog album","artist":"Artist"}],"total":1}"""
                        url.queryParameter("offset") == "0" -> """{"configured":true,"albums":[{"id":"first","title":"First album","artist":"Artist","inLibrary":true}],"total":2}"""
                        else -> """{"configured":true,"albums":[{"id":"second","title":"Second album","artist":"Artist","inLibrary":true}],"total":2}"""
                    })
                }
            }
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity = controller.get(); ShadowDialog.getLatestDialog().dismiss(); api(activity, server)
                invoke(activity, "loadTab", "music")
                fun texts() = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(texts().containsAll(listOf("Artists", "Albums", "Songs")))
                waitUntil { texts().any { it.startsWith("Second album") } }
                assertTrue(texts().any { it.startsWith("First album") })
                val input = descendants(activity.window.decorView).filterIsInstance<EditText>().first()
                input.setText("Ar"); input.setText("Artist")
                waitUntil { texts().any { it.startsWith("Catalog album") } }
                assertFalse(texts().any { it.startsWith("First album") })
                descendants(activity.window.decorView).filterIsInstance<android.widget.Button>().first { it.text == "Artists" }.performClick()
                waitUntil { "Artist" in texts() && "1 album" in texts() }
                descendants(activity.window.decorView).filterIsInstance<android.widget.Button>().first { it.text == "Songs" }.performClick()
                waitUntil { texts().any { it.startsWith("Fixture song") } }
                invoke(activity, "loadTab", "home")
            }
        }
    }
}
