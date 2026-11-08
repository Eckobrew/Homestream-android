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
    @Test fun nativeNavigationReflectsEpisodeQueueBoundaries() {
        val first = Media(JSONObject().put("id", "first").put("title", "First").put("type", "Episode"))
        val second = Media(JSONObject().put("id", "second").put("title", "Second").put("type", "Episode"))
        val intent = Intent(RuntimeEnvironment.getApplication(), PlayerActivity::class.java)
            .putExtra("media", first.json.toString())
        Robolectric.buildActivity(PlayerActivity::class.java, intent).create().use { controller ->
            val activity = controller.get()
            val previous = activity.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_prev)
            val next = activity.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_next)
            assertFalse(previous.isEnabled)
            assertFalse(next.isEnabled)
            PlayerActivity::class.java.getDeclaredField("episodes").apply { isAccessible = true }
                .set(activity, EpisodeQueue(listOf(first, second)))
            val update = PlayerActivity::class.java.getDeclaredMethod("updateEpisodeControls").apply { isAccessible = true }
            update.invoke(activity)
            assertFalse(previous.isEnabled)
            assertTrue(next.isEnabled)
            PlayerActivity::class.java.getDeclaredField("currentPlaybackId").apply { isAccessible = true }
                .set(activity, second.playbackId)
            update.invoke(activity)
            assertTrue(previous.isEnabled)
            assertFalse(next.isEnabled)
        }
    }

    @Test fun continuingSeasonResolvesItsResumeEpisode() {
        for (type in listOf("Series", "Season")) {
            val media = JSONObject().put("id", "season").put("title", "Season")
                .put("type", type).put("playableEpisodeId", "resumed-episode")
                .put("playbackPositionTicks", 420000000L)
            val intent = Intent(RuntimeEnvironment.getApplication(), PlayerActivity::class.java)
                .putExtra("media", media.toString())
            Robolectric.buildActivity(PlayerActivity::class.java, intent).create().use { controller ->
                val activity = controller.get()
                val resolve = PlayerActivity::class.java.getDeclaredMethod("resolvePlaybackId", kotlin.coroutines.Continuation::class.java)
                    .apply { isAccessible = true }
                val episode = resolve.invoke(activity, object : kotlin.coroutines.Continuation<String> {
                    override val context = kotlin.coroutines.EmptyCoroutineContext
                    override fun resumeWith(result: Result<String>) { result.getOrThrow() }
                })
                assertEquals("resumed-episode", episode)
                assertEquals(42000L, PlayerActivity::class.java.getDeclaredField("position")
                    .apply { isAccessible = true }.getLong(activity))
            }
        }
    }

    @Test fun resumedSeasonLoadsQueueForResolvedEpisode() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"items":[
                {"id":"first","type":"Episode","episodeNumber":1},
                {"id":"resumed","type":"Episode","episodeNumber":2},
                {"id":"next","type":"Episode","episodeNumber":3}
            ]}"""))
            val media = JSONObject().put("id", "season").put("type", "Season")
                .put("playableEpisodeId", "resumed")
            val intent = Intent(RuntimeEnvironment.getApplication(), PlayerActivity::class.java)
                .putExtra("media", media.toString())
            Robolectric.buildActivity(PlayerActivity::class.java, intent).create().use { controller ->
                val activity = controller.get()
                val settings = object : ConnectionSettings {
                    override val baseUrl = server.url("/").toString().trimEnd('/')
                    override val token = "test-pairing-token-with-thirty-two-characters"
                    override var profile = "test"
                }
                PlayerActivity::class.java.getDeclaredField("api").apply { isAccessible = true }
                    .set(activity, HomeStreamApi(settings))
                PlayerActivity::class.java.getDeclaredField("currentPlaybackId").apply { isAccessible = true }
                    .set(activity, "resumed")
                val load = PlayerActivity::class.java.getDeclaredMethod("loadEpisodeContextNow",
                    String::class.java, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    load.invoke(activity, "resumed", continuation)
                }
                assertEquals("resumed", server.takeRequest().requestUrl!!.queryParameter("itemId"))
                val queue = PlayerActivity::class.java.getDeclaredField("episodes").apply { isAccessible = true }
                    .get(activity) as EpisodeQueue
                assertEquals("first", queue.previous("resumed")?.playbackId)
                assertEquals("next", queue.next("resumed")?.playbackId)
            }
        }
    }

    @Test fun showingCastStatusWithoutRemoteDoesNotOpenController() {
        val media = JSONObject().put("id", "movie").put("title", "Movie").put("type", "Movie")
        val intent = Intent(RuntimeEnvironment.getApplication(), PlayerActivity::class.java).putExtra("media", media.toString())
        Robolectric.buildActivity(PlayerActivity::class.java, intent).create().use { controller ->
            val activity = controller.get()
            PlayerActivity::class.java.getDeclaredMethod("showCastControls").apply { isAccessible = true }.invoke(activity)
            assertNull("Cast controls require an active remote player", shadowOf(activity).nextStartedActivity)
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
