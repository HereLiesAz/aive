package com.hereliesaz.aive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.log.AppLog
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidProviderCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        // Keys saved before the rename live in a file and Keystore key under the old names; move them across.
        LegacyKeystoreSecrets.migrate(
            context = context,
            legacyPreferencesName = LEGACY_PREFERENCES_NAME,
            legacyKeyAlias = LEGACY_KEY_ALIAS,
            hasCurrent = { preferences.contains(ciphertextKey(it)) },
            storeCurrent = { name, plaintext -> seal(name, plaintext) },
        )
    }

    fun read(providerId: String): String? {
        val encrypted = preferences.getString(ciphertextKey(providerId), null) ?: return null
        val iv = preferences.getString(ivKey(providerId), null) ?: return null
        // Reading never mints a key: a new key could not decrypt what the old one sealed, and would hide the loss.
        val key = existingKey()
        if (key == null) {
            keyLost(providerId, "its Keystore key is gone")
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
                .decodeToString()
                .trim()
                .takeIf(String::isNotEmpty)
        } catch (failure: AEADBadTagException) {
            keyLost(providerId, "it can no longer be decrypted")
            null
        } catch (failure: Exception) {
            // Possibly temporary (Keystore busy or locked): keep the saved key and say so.
            AppLog.record("credentials", "The saved key for $providerId could not be read just now: ${failure::class.simpleName}.")
            null
        }
    }

    /** The unreadable copy is removed so the Source shows as not connected, and the keeper is told to reconnect. */
    private fun keyLost(providerId: String, reason: String) {
        clear(providerId)
        AppLog.record("credentials", "The saved key for $providerId was lost ($reason). Reconnect that Source in Settings and enter its key again.")
    }

    fun readAll(): Map<String, String> = buildMap {
        purgeRetired()
        ProviderCatalog.entries.forEach { entry ->
            read(entry.id)?.let { put(entry.id, it) }
        }
    }

    fun write(providerId: String, apiKey: String) {
        require(ProviderCatalog.entry(providerId) != null) { "Unknown provider $providerId" }
        val clean = apiKey.trim()
        require(clean.isNotEmpty()) { "API key is required" }
        seal(providerId, clean)
    }

    private fun seal(providerId: String, clean: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.encodeToByteArray())

        check(
            preferences.edit()
                .putString(ciphertextKey(providerId), Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(ivKey(providerId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist provider credential" }
    }

    fun clear(providerId: String) {
        preferences.edit()
            .remove(ciphertextKey(providerId))
            .remove(ivKey(providerId))
            .commit()
    }

    /** Deletes credentials of providers the app no longer supports, including the old Jules key alias. */
    private fun purgeRetired() {
        ProviderCatalog.RETIRED_PROVIDER_IDS
            .filter { preferences.contains(ciphertextKey(it)) || preferences.contains(ivKey(it)) }
            .forEach(::clear)
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(RETIRED_JULES_KEY_ALIAS)) keyStore.deleteEntry(RETIRED_JULES_KEY_ALIAS)
        }
    }

    private fun ciphertextKey(providerId: String) = "$providerId.ciphertext"
    private fun ivKey(providerId: String) = "$providerId.iv"

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }

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
        const val KEY_ALIAS = "aive.provider.api-key"
        /** Keystore key of the removed Jules-only store (legacy input name: it is only ever deleted). */
        const val RETIRED_JULES_KEY_ALIAS = "haive.jules.api-key"
        const val PREFERENCES_NAME = "aive.credentials"

        // Legacy input names: older builds saved keys under these. Read once to migrate, then retired.
        const val LEGACY_PREFERENCES_NAME = "haive.credentials"
        const val LEGACY_KEY_ALIAS = "haive.provider.api-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
