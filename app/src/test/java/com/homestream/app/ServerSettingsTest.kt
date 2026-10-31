package com.homestream.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerSettingsTest {
    private lateinit var settings: ServerSettings

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("server", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ServerSettings(app) { javax.crypto.spec.SecretKeySpec(ByteArray(16) { 7 }, "AES") }
    }

    @Test
    fun localServerPairingRetainsAuthWhenExternalUrlAdded() {
        // 1. Initial local pairing
        val localUrl = "http://192.168.1.172:3000"
        val deviceToken = "hst_dev_1234567890abcdef"
        settings.saveServer(
            local = localUrl,
            external = "",
            token = deviceToken,
            type = "device",
            name = "HomeStream Local"
        )

        assertEquals(localUrl, settings.localUrl)
        assertEquals("", settings.externalUrl)
        assertEquals(deviceToken, settings.token)
        assertEquals("device", settings.authType)
        assertEquals("HomeStream Local", settings.serverName)
        assertEquals(localUrl, settings.effectiveUrl)

        // 2. User adds an external URL via Settings in sidebar
        val externalUrl = "https://homeflix.eckonet.uk"
        settings.updateExternalUrl(externalUrl)

        assertEquals(localUrl, settings.localUrl)
        assertEquals(externalUrl, settings.externalUrl)
        // Ensure authentication token and device pairing are preserved
        assertEquals(deviceToken, settings.token)
        assertEquals("device", settings.authType)
        assertEquals("HomeStream Local", settings.serverName)
    }

    @Test
    fun externalJellyfinLoginSavesCorrectTypeAndCredentials() {
        val externalUrl = "https://homeflix.eckonet.uk"
        val jfToken = "jellyfin_auth_token_xyz"
        settings.saveServer(
            local = "",
            external = externalUrl,
            token = jfToken,
            type = "jellyfin",
            name = "Nathan"
        )

        assertEquals("", settings.localUrl)
        assertEquals(externalUrl, settings.externalUrl)
        assertEquals(jfToken, settings.token)
        assertEquals("jellyfin", settings.authType)
        assertEquals("Nathan", settings.serverName)
        assertEquals(externalUrl, settings.effectiveUrl)
    }

    @Test
    fun legacyBaseUrlCompatibilityIsPreserved() {
        settings.save("http://homeflix.local:3000", "token123")

        assertEquals("http://homeflix.local:3000", settings.baseUrl)
        assertEquals("http://homeflix.local:3000", settings.effectiveUrl)
        assertEquals("token123", settings.token)
    }

    @Test fun fallbackRemembersReachableEndpointAndPreservesWatchlist() = runBlocking {
        MockWebServer().use { local ->
            MockWebServer().use { external ->
                local.start()
                val localUrl = local.url("/").toString().trimEnd('/')
                local.shutdown()
                external.enqueue(MockResponse().setBody("{\"apiVersion\":1}"))
                external.enqueue(MockResponse().setBody("{\"items\":[]}"))
                settings.saveServer(localUrl, external.url("/").toString().trimEnd('/'), "token123")
                settings.profile = "viewer"
                val list = Watchlist(RuntimeEnvironment.getApplication(), settings)
                val item = Media(JSONObject().put("id", "fallback-test"))
                list.toggle(item)
                val api = HomeStreamApi(settings)
                api.call("capabilities")
                assertEquals(external.url("/art").toString(), api.absolute("/art"))
                assertTrue(list.contains(item))
                api.call("home")
                assertEquals(2, external.requestCount)
            }
        }
    }

    @Test fun rejectedCredentialsDoNotRetryAnotherServer() = runBlocking {
        MockWebServer().use { local ->
            MockWebServer().use { external ->
                local.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"Rejected\"}"))
                settings.saveServer(local.url("/").toString().trimEnd('/'), external.url("/").toString().trimEnd('/'), "token123")
                assertTrue(runCatching { HomeStreamApi(settings).call("home") }.isFailure)
                assertEquals(0, external.requestCount)
            }
        }
    }

    @Test fun keystoreFailureDoesNotPersistAnUnprotectedKeyOrToken() {
        val broken = ServerSettings(RuntimeEnvironment.getApplication()) { throw java.security.KeyStoreException("Unavailable") }
        assertTrue(runCatching { broken.save("https://home.example", "secret") }.isFailure)
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("server", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("keystore_fallback"))
        assertFalse(prefs.contains("token"))
    }

    @Test fun manualPairingClearsPreviousExternalServer() {
        settings.saveServer("", "https://old.example", "token123", "jellyfin")
        settings.save("http://new.local:3000", "newtoken")
        assertEquals("", settings.externalUrl)
        assertEquals("device", settings.authType)
        assertEquals("http://new.local:3000", settings.baseUrl)
    }
}
