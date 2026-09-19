package com.hereliesaz.aive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hereliesaz.geministrator.RepositoryServiceCatalog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidRepositoryCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(serviceId: String): String? {
        val encrypted = preferences.getString(ciphertextKey(serviceId), null) ?: return null
        val iv = preferences.getString(ivKey(serviceId), null) ?: return null
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
        RepositoryServiceCatalog.entries.forEach { entry ->
            read(entry.id)?.let { put(entry.id, it) }
        }
    }

    fun write(serviceId: String, credential: String) {
        require(RepositoryServiceCatalog.entry(serviceId) != null) { "Unknown repository service $serviceId" }
        val clean = credential.trim()
        require(clean.isNotEmpty()) { "Repository credential is required" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.encodeToByteArray())

        check(
            preferences.edit()
                .putString(ciphertextKey(serviceId), Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(ivKey(serviceId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist repository credential" }
    }

    fun clear(serviceId: String) {
        preferences.edit()
            .remove(ciphertextKey(serviceId))
            .remove(ivKey(serviceId))
            .commit()
    }

    private fun ciphertextKey(serviceId: String) = "$serviceId.ciphertext"
    private fun ivKey(serviceId: String) = "$serviceId.iv"

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
        const val KEY_ALIAS = "haive.repository.credentials"
        const val PREFERENCES_NAME = "haive.repository.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
