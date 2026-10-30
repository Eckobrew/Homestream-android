package com.homestream.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerSettingsTest {
    private lateinit var settings: ServerSettings

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("server", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ServerSettings(app)
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
}
