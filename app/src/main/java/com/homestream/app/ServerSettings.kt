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
    override var baseUrl: String
        get() = prefs.getString("url", "").orEmpty()
        private set(value) { prefs.edit().putString("url", value).apply() }
    override var profile: String
        get() = prefs.getString("profile", "").orEmpty()
        set(value) { prefs.edit().putString("profile", value).apply() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("homestream-pairing", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("homestream-pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
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
            Base64.encodeToString(encrypted, Base64.NO_WRAP)).putString("url", url.trimEnd('/')).remove("profile").apply()
    }
    fun clear() { prefs.edit().clear().apply() }
}
