package com.homestream.app

import kotlinx.coroutines.runBlocking
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
}
