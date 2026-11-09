package com.homestream.app

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HomeStreamApiTest {
    private val token = "test-pairing-token-with-thirty-two-characters"
    private fun settings(url: String) = object : ConnectionSettings {
        override val baseUrl = url.trimEnd('/')
        override val token = this@HomeStreamApiTest.token
        override var profile = "profile & one"
    }
    @Test fun musicUrlsUseNativeRoutesAndCurrentProfile() {
        val api = HomeStreamApi(settings("https://home.example"))
        val url = api.absolute("/api/v1/music/stream?trackId=opaque").toHttpUrl()
        assertEquals("/api/mobile/v1/music/stream", url.encodedPath)
        assertEquals("opaque", url.queryParameter("trackId"))
        assertEquals("profile & one", url.queryParameter("userId"))
        assertEquals("https://other.example/api/v1/music/artwork", api.absolute("https://other.example/api/v1/music/artwork"))
    }

    @Test fun musicRangeRequestsRetainAuthorizationAcrossSeeks() {
        MockWebServer().use { server ->
            val api = HomeStreamApi(settings(server.url("/").toString()))
            val client = api.mediaClient()
            for (range in listOf("bytes=0-3", "bytes=4-7")) {
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", if (range == "bytes=0-3") "bytes 0-3/8" else "bytes 4-7/8").setBody("data"))
                client.newCall(okhttp3.Request.Builder().url(api.absolute("/api/mobile/v1/music/stream?trackId=opaque")).header("Range", range).build()).execute().use {
                    assertEquals(206, it.code)
                    assertEquals("data", it.body!!.string())
                }
                val request = server.takeRequest()
                assertEquals("Bearer $token", request.getHeader("Authorization"))
                assertEquals(range, request.getHeader("Range"))
                assertEquals("profile & one", request.requestUrl!!.queryParameter("userId"))
            }
        }
    }

    @Test fun adminTestPostsUnsavedInputsWithoutChangingCaller() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":true}"))
            val payload = JSONObject().put("integration", "slskd").put("action", "test").put("url", "http://music:5030").put("secret", "")
            HomeStreamApi(settings(server.url("/").toString())).call("integrations", body = payload)
            val request = server.takeRequest()
            assertFalse(request.path!!.contains("secret"))
            val sent = JSONObject(request.body.readUtf8())
            assertEquals("test", sent.getString("action"))
            assertEquals("", sent.getString("secret"))
            assertFalse(payload.has("userId"))
        }
    }

    @Test fun profileRequestsOmitEmptyPinsAndPreserveSuppliedPins() = runBlocking {
        MockWebServer().use { server ->
            val api = HomeStreamApi(settings(server.url("/").toString()))
            for (path in listOf("profiles", "profiles/select", "profiles/users")) {
                for (pin in listOf("", "1234")) {
                    server.enqueue(MockResponse().setBody("""{"success":true}"""))
                    val original = JSONObject().put("parentPin", pin).put("profileId", "test-profile")
                    api.call(path, body = original)
                    val sent = JSONObject(server.takeRequest().body.readUtf8())
                    assertEquals("test-profile", sent.getString("profileId"))
                    if (pin.isEmpty()) assertFalse(sent.has("parentPin"))
                    else assertEquals(pin, sent.getString("parentPin"))
                    assertEquals("Request serialization must not mutate the caller's body", pin, original.getString("parentPin"))
                }
            }
        }
    }

    @Test fun requestsUseVersionedPathAndSelectedProfile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"apiVersion\":1}"))
            val api = HomeStreamApi(settings(server.url("/homestream").toString()))
            assertEquals(1, api.call("capabilities").getInt("apiVersion"))
            val request = server.takeRequest()
            assertEquals("/homestream/api/mobile/v1/capabilities", request.requestUrl!!.encodedPath)
            assertEquals("profile & one", request.requestUrl!!.queryParameter("userId"))
            assertEquals("Bearer $token", request.getHeader("Authorization"))
        }
    }
    @Test fun progressUsesTicksAndIncludesProfile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":true}"))
            HomeStreamApi(settings(server.url("/").toString())).progress("episode", 123456L, true, true)
            val request = server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertEquals("POST", request.method)
            assertEquals(1234560000L, body.getLong("positionTicks"))
            assertEquals("profile & one", body.getString("userId"))
            assertTrue(body.getBoolean("isStopped"))
        }
    }
    @Test fun pairingCredentialsAreNeverForwardedByRedirects() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/capture")))
                val error = runCatching { HomeStreamApi(settings(server.url("/").toString())).call("home") }.exceptionOrNull()
                assertTrue(error is IOException)
                assertEquals(0, other.requestCount)
            }
        }
    }
    @Test fun rejectedPairingProducesActionableError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"Invalid token\"}"))
            val error = runCatching { HomeStreamApi(settings(server.url("/").toString())).call("home") }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("Settings"))
            assertFalse(error.message!!.contains(token))
        }
    }
    @Test fun credentialsRequireTheExactServerOrigin() {
        val api = HomeStreamApi(settings("https://home.example:8443"))
        assertTrue(api.isServerUrl("/api/mobile/v1/stream"))
        assertTrue(api.isServerUrl("https://home.example:8443/api/stream"))
        assertFalse(api.isServerUrl("https://jellyfin.example/api/stream/video"))
        assertFalse(api.isServerUrl("http://home.example:8443/api/stream"))
        assertFalse(api.isServerUrl("https://home.example:9443/api/stream"))
    }
    @Test fun mediaRedirectsDoNotForwardHouseholdCredentials() {
        MockWebServer().use { server ->
            MockWebServer().use { jellyfin ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", jellyfin.url("/video")))
                jellyfin.enqueue(MockResponse().setBody("video"))
                val api = HomeStreamApi(settings(server.url("/").toString()))
                api.mediaClient().newCall(okhttp3.Request.Builder().url(server.url("/stream")).build()).execute().use {
                    assertEquals("video", it.body!!.string())
                }
                assertEquals("Bearer $token", server.takeRequest().getHeader("Authorization"))
                assertNull(jellyfin.takeRequest().getHeader("Authorization"))
            }
        }
    }
    @Test fun legacyDownloadUrlsUseTheAuthenticatedMobileEndpoint() {
        val api = HomeStreamApi(settings("https://home.example"))
        assertEquals("https://home.example/api/mobile/v1/stream/download?hash=abc", api.absolute("/api/stream/download?hash=abc"))
        assertEquals("https://other.example/api/stream/download?hash=abc", api.absolute("https://other.example/api/stream/download?hash=abc"))
    }
}
