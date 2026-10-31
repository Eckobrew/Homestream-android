package com.homestream.app

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
class BrowseRegressionTest {
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
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for UI", condition())
    }
    private fun api(activity: MainActivity, server: MockWebServer) {
        val settings = object : ConnectionSettings {
            override val baseUrl = server.url("/").toString()
            override val token = "test-token"
            override var profile = ""
        }
        MainActivity::class.java.getDeclaredField("api").apply { isAccessible = true }.set(activity, HomeStreamApi(settings))
    }
    @Test fun repeatedSearchReplacesRowsAndClearingRemovesResults() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[{"id":"one","title":"First title","type":"Movie","status":"available"}]}"""))
            server.enqueue(MockResponse().setBody("""{"results":[{"id":"two","title":"Second title","type":"Movie","status":"available"}]}"""))
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                ShadowDialog.getLatestDialog().dismiss()
                api(activity, server)
                invoke(activity, "openSearch")
                val input = descendants(activity.window.decorView).filterIsInstance<EditText>().first()
                input.setText("First")
                waitUntil { descendants(activity.window.decorView).any { it.contentDescription?.toString()?.startsWith("First title,") == true } }
                input.setText("Second")
                waitUntil { descendants(activity.window.decorView).any { it.contentDescription?.toString()?.startsWith("Second title,") == true } }
                assertFalse(descendants(activity.window.decorView).any { it.contentDescription?.toString()?.startsWith("First title,") == true })
                input.setText("")
                assertFalse(descendants(activity.window.decorView).any { it.contentDescription?.toString()?.startsWith("Second title,") == true })
            }
        }
    }
    @Test fun slowHomeResponseDoesNotReplaceSearch() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"sections\":[]}").setBodyDelay(300, TimeUnit.MILLISECONDS))
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                ShadowDialog.getLatestDialog().dismiss()
                api(activity, server)
                invoke(activity, "loadTab", "home")
                invoke(activity, "openSearch")
                repeat(60) { shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS); Thread.sleep(10) }
                assertTrue(descendants(activity.window.decorView).filterIsInstance<EditText>().any { it.hint == "Search movies, series, requests…" })
            }
        }
    }
    @Test fun catalogRecyclesLargeLibrariesAndFitsNarrowPhones() {
        MockWebServer().use { server ->
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                ShadowDialog.getLatestDialog().dismiss()
                api(activity, server)
                MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }.set(activity, "catalog-movies")
                invoke(activity, "shell")
                val items = List(1000) { Media(JSONObject().put("id", "$it").put("title", "Movie $it")) }
                invoke(activity, "renderCatalog", "movies", "All", items)
                val root = activity.findViewById<View>(android.R.id.content)
                repeat(3) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 360, 800)
                    shadowOf(Looper.getMainLooper()).idle()
                }
                val grid = descendants(root).filterIsInstance<RecyclerView>().single()
                assertEquals(1000, grid.adapter!!.itemCount)
                assertTrue(grid.childCount > 0)
                assertTrue(grid.childCount < 50)
                assertEquals(2, (grid.layoutManager as GridLayoutManager).spanCount)
                for (i in 0 until grid.childCount) assertTrue(grid.getChildAt(i).right <= grid.width)
            }
        }
    }
}
