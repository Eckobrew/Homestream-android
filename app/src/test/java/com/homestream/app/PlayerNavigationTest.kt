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

    @Test fun playerOverlayDefaultsToPlayPause() {
        val media = JSONObject().put("id", "movie").put("title", "Movie").put("type", "Movie")
        val intent = Intent(RuntimeEnvironment.getApplication(), PlayerActivity::class.java).putExtra("media", media.toString())
        Robolectric.buildActivity(PlayerActivity::class.java, intent).create().visible().use { controller ->
            val activity = controller.get()
            val playPause = activity.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)
            assertTrue(playPause.isFocusedByDefault)
            // The control is disabled before a player is attached.
            val playerView = PlayerActivity::class.java.getDeclaredField("playerView").apply { isAccessible = true }.get(activity) as androidx.media3.ui.PlayerView
            playerView.showController()
            playPause.isEnabled = true
            playPause.isFocusableInTouchMode = true
            PlayerActivity::class.java.getDeclaredMethod("focusPlaybackControl").apply { isAccessible = true }.invoke(activity)
            assertTrue(playPause.hasFocus())
        }
    }

    @Test fun tvRemoteCenterPausesAndRevealsControlsAndBackDismissesThem() {
        val app = RuntimeEnvironment.getApplication()
        val uiMode = app.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager
        shadowOf(uiMode).setCurrentModeType(android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
        val media = JSONObject().put("id", "movie").put("title", "Movie").put("type", "Movie")
        val intent = Intent(app, PlayerActivity::class.java).putExtra("media", media.toString())
        Robolectric.buildActivity(PlayerActivity::class.java, intent).create().visible().use { controller ->
            val activity = controller.get()
            val view = PlayerActivity::class.java.getDeclaredField("playerView").apply { isAccessible = true }
                .get(activity) as androidx.media3.ui.PlayerView
            view.setControllerAnimationEnabled(false)
            view.hideController()
            assertTrue(activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER)))
            assertTrue(view.isControllerFullyVisible)
            assertFalse(PlayerActivity::class.java.getDeclaredField("wantsPlay").apply { isAccessible = true }.getBoolean(activity))
            assertTrue(activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER)))
            (activity.lifecycle as androidx.lifecycle.LifecycleRegistry).handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(view.isControllerFullyVisible)
            assertFalse(activity.isFinishing)
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(activity.isFinishing)
        }
    }

    @Test fun tvRemoteSeeksTenSecondsWithoutOpeningOverlay() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager)
            .setCurrentModeType(android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
        val intent = Intent(app, PlayerActivity::class.java).putExtra("media",
            JSONObject().put("id", "movie").put("type", "Movie").toString())
        Robolectric.buildActivity(PlayerActivity::class.java, intent).create().visible().use { controller ->
            val activity = controller.get()
            val view = PlayerActivity::class.java.getDeclaredField("playerView").apply { isAccessible = true }
                .get(activity) as androidx.media3.ui.PlayerView
            var playbackPosition = 5000L
            var playing = true
            val exo = java.lang.reflect.Proxy.newProxyInstance(
                androidx.media3.exoplayer.ExoPlayer::class.java.classLoader,
                arrayOf(androidx.media3.exoplayer.ExoPlayer::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "getCurrentPosition" -> playbackPosition
                    "getDuration" -> 20000L
                    "isCommandAvailable" -> true
                    "seekTo" -> { playbackPosition = args!!.last() as Long; null }
                    "pause" -> { playing = false; null }
                    else -> null
                }
            } as androidx.media3.exoplayer.ExoPlayer
            try {
                PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.set(activity, exo)
                view.setControllerAnimationEnabled(false)
                view.hideController()
                fun press(key: Int) {
                    activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
                    activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
                }
                press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                assertEquals(15000L, exo.currentPosition)
                press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                assertEquals(20000L, exo.currentPosition)
                press(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                assertEquals(10000L, exo.currentPosition)
                playbackPosition = 15000L
                press(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                assertEquals(5000L, exo.currentPosition)
                press(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                assertEquals(0L, exo.currentPosition)
                assertFalse(view.isControllerFullyVisible)
                playing = true
                view.hideController()
                press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                assertFalse(playing)
                assertTrue(view.isControllerFullyVisible)
            } finally {
                view.player = null
                PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.set(activity, null)
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
