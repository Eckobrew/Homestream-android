package com.homestream.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Pairing token is encrypted with a device-bound Android Keystore AES key. */
interface ConnectionSettings {
    val baseUrl: String
    val token: String
    var profile: String
}

class ServerSettings(context: Context, private val keyProvider: (() -> SecretKey)? = null) : ConnectionSettings {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    var localUrl: String
        get() = prefs.getString("localUrl", "").orEmpty().ifBlank {
            if (authType == "device" || authType == "local") prefs.getString("url", "").orEmpty() else ""
        }
        set(value) { prefs.edit().putString("localUrl", value.trim().trimEnd('/')).apply() }

    var externalUrl: String
        get() = prefs.getString("externalUrl", "").orEmpty().ifBlank {
            if (authType == "jellyfin" || authType == "remote") prefs.getString("url", "").orEmpty() else ""
        }
        set(value) { prefs.edit().putString("externalUrl", value.trim().trimEnd('/')).apply() }

    var authType: String
        get() = prefs.getString("authType", "device").orEmpty()
        set(value) { prefs.edit().putString("authType", value).apply() }

    var serverName: String
        get() = prefs.getString("serverName", "HomeStream Server").orEmpty()
        set(value) { prefs.edit().putString("serverName", value).apply() }

    val effectiveUrl: String
        get() {
            val local = localUrl
            val ext = externalUrl
            val legacy = prefs.getString("url", "").orEmpty()
            val active = prefs.getString("activeUrl", "").orEmpty()
            return when {
                active.isNotBlank() && (active == local || active == ext) -> active
                local.isNotBlank() -> local
                ext.isNotBlank() -> ext
                else -> legacy
            }
        }

    override var baseUrl: String
        get() = effectiveUrl
        set(value) { prefs.edit().putString("url", value.trim().trimEnd('/')).apply() }

    override var profile: String
        get() = prefs.getString("profile", "").orEmpty()
        set(value) { if (profile != value) appContext.stopService(android.content.Intent(appContext, MusicService::class.java)); prefs.edit().putString("profile", value).apply(); WatchNext.refresh(appContext) }

    var policyRevision: String
        get() = prefs.getString("policyRevision", "").orEmpty()
        private set(value) { prefs.edit().putString("policyRevision", value).apply() }
    var kidsRating: String
        get() = prefs.getString("kidsRating", "").orEmpty()
        private set(value) { prefs.edit().putString("kidsRating", value).apply() }
    var profileName: String
        get() = prefs.getString("profileName", "Nathan").orEmpty().ifBlank { "Nathan" }
        private set(value) { prefs.edit().putString("profileName", value).apply() }

    val isKids: Boolean get() = prefs.getBoolean("isKids", false) || kidsRating.isNotBlank()

    fun applyProfilePolicy(profile: org.json.JSONObject?, revision: String) {
        val id = profile?.optString("id").orEmpty()
        val name = profile?.optString("name").orEmpty()
        val rating = if (profile?.optBoolean("isKids") == true) profile.optString("kidsRating") else ""
        val kids = profile?.optBoolean("isKids") == true
        val changed = this.profile != id || policyRevision != revision || kidsRating != rating || isKids != kids
        prefs.edit().putString("profile", id).putString("profileName", name.ifBlank { "Nathan" })
            .putString("policyRevision", revision).putString("kidsRating", rating).putBoolean("isKids", kids).apply()
        if (changed) { appContext.stopService(android.content.Intent(appContext, MusicService::class.java)); BrowseCache.clear(); WatchNext.refresh(appContext) }
    }

    private fun key(): SecretKey {
        keyProvider?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("homestream-pairing", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("homestream-pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun rememberSuccessfulUrl(url: String) {
        if (url == localUrl || url == externalUrl) prefs.edit().putString("activeUrl", url).apply()
    }

    val canUseProfiles: Boolean get() = authType in listOf("device", "local") && token.startsWith("hst_dev_")

    val serverIdentity: String get() = localUrl.ifBlank { externalUrl.ifBlank { baseUrl } }

    @Volatile private var decryptedToken: Pair<String, String>? = null
    override val token: String get() {
        val stored = prefs.getString("token", "").orEmpty()
        decryptedToken?.takeIf { it.first == stored }?.let { return it.second }
        return runCatching {
            val encoded = stored.split(':')
            if (encoded.size != 2) return@runCatching ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(encoded[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encoded[1], Base64.NO_WRAP)), Charsets.UTF_8)
                .also { decryptedToken = stored to it }
        }.getOrDefault("")
    }

    fun save(url: String, token: String) {
        appContext.stopService(android.content.Intent(appContext, MusicService::class.java))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("token", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("url", url.trimEnd('/'))
            .putString("localUrl", url.trimEnd('/'))
            .remove("externalUrl").remove("activeUrl").remove("keystore_fallback")
            .putString("authType", "device")
            .remove("profile").remove("policyRevision").remove("kidsRating").remove("isKids")
            .apply()
        WatchNext.refresh(appContext)
    }

    fun saveServer(
        local: String,
        external: String,
        token: String,
        type: String = "device",
        name: String = "HomeStream Server"
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        appContext.stopService(android.content.Intent(appContext, MusicService::class.java))
        val active = local.ifBlank { external }.trimEnd('/')
        prefs.edit()
            .putString("token", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("url", active)
            .putString("localUrl", local.trimEnd('/'))
            .putString("externalUrl", external.trimEnd('/'))
            .putString("authType", type)
            .putString("serverName", name)
            .remove("activeUrl").remove("keystore_fallback")
            .remove("profile").remove("policyRevision").remove("kidsRating").remove("isKids")
            .apply()
        WatchNext.refresh(appContext)
    }

    fun updateExternalUrl(external: String) {
        prefs.edit().putString("externalUrl", external.trim().trimEnd('/')).apply()
    }

    fun clear() { appContext.stopService(android.content.Intent(appContext, MusicService::class.java)); prefs.edit().clear().apply(); WatchNext.refresh(appContext) }
}
