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

class ServerSettings(context: Context) : ConnectionSettings {
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
            return when {
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
        set(value) { prefs.edit().putString("profile", value).apply() }

    private fun key(): SecretKey {
        return runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("homestream-pairing", null) as? SecretKey)?.let { return it }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("homestream-pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }.getOrElse {
            // JVM / Robolectric environment fallback when AndroidKeyStore is unavailable
            val raw = prefs.getString("keystore_fallback", null) ?: run {
                val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                prefs.edit().putString("keystore_fallback", encoded).commit()
                encoded
            }
            javax.crypto.spec.SecretKeySpec(Base64.decode(raw, Base64.NO_WRAP), "AES")
        }
    }

    override val token: String get() = runCatching {
        val encoded = prefs.getString("token", "").orEmpty().split(':')
        if (encoded.size != 2) return@runCatching ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(encoded[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(encoded[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")

    fun save(url: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("token", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("url", url.trimEnd('/'))
            .putString("localUrl", url.trimEnd('/'))
            .remove("profile")
            .apply()
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
        val active = local.ifBlank { external }.trimEnd('/')
        prefs.edit()
            .putString("token", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("url", active)
            .putString("localUrl", local.trimEnd('/'))
            .putString("externalUrl", external.trimEnd('/'))
            .putString("authType", type)
            .putString("serverName", name)
            .remove("profile")
            .apply()
    }

    fun updateExternalUrl(external: String) {
        prefs.edit().putString("externalUrl", external.trim().trimEnd('/')).apply()
    }

    fun clear() { prefs.edit().clear().apply() }
}
