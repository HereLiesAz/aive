package com.hereliesaz.haive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hereliesaz.geministrator.ProviderCatalog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidProviderCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(providerId: String): String? {
        val encrypted = preferences.getString(ciphertextKey(providerId), null) ?: return null
        val iv = preferences.getString(ivKey(providerId), null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
                .decodeToString()
                .trim()
                .takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    fun readAll(): Map<String, String> = buildMap {
        ProviderCatalog.entries.forEach { entry ->
            read(entry.id)?.let { put(entry.id, it) }
        }
    }

    fun write(providerId: String, apiKey: String) {
        require(ProviderCatalog.entry(providerId) != null) { "Unknown provider $providerId" }
        val clean = apiKey.trim()
        require(clean.isNotEmpty()) { "API key is required" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.encodeToByteArray())

        preferences.edit()
            .putString(ciphertextKey(providerId), Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey(providerId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun clear(providerId: String) {
        preferences.edit()
            .remove(ciphertextKey(providerId))
            .remove(ivKey(providerId))
            .apply()
    }

    private fun ciphertextKey(providerId: String) = "$providerId.ciphertext"
    private fun ivKey(providerId: String) = "$providerId.iv"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "haive.jules.api-key"
        const val PREFERENCES_NAME = "haive.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
