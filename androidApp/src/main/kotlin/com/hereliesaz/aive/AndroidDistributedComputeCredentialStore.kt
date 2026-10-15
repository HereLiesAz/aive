package com.hereliesaz.aive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One Keystore-encrypted secret in its own preferences file. Defaults hold the distributed-compute
 * relay token; [forRemoteRelayPairing] holds The Aive's remote-relay pairing code.
 */
internal class AndroidDistributedComputeCredentialStore(
    context: Context,
    private val keyAlias: String = KEY_ALIAS,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    fun readToken(): String? {
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
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

    fun writeToken(token: String) {
        val clean = token.trim()
        require(clean.isNotEmpty()) { "Relay token is required" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.encodeToByteArray())
        check(
            preferences.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist relay token" }
    }

    fun clear() {
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        fun forRemoteRelayPairing(context: Context) = AndroidDistributedComputeCredentialStore(
            context,
            keyAlias = "aive.remote-relay.pairing",
            preferencesName = REMOTE_RELAY_PREFERENCES_NAME,
        )

        /** Excluded from backup like the other credential files. */
        const val REMOTE_RELAY_PREFERENCES_NAME = "aive.remote-relay.credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "haive.distributed-compute.relay-token"
        private const val PREFERENCES_NAME = "haive.distributed-compute.credentials"
        private const val KEY_CIPHERTEXT = "relay-token.ciphertext"
        private const val KEY_IV = "relay-token.iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
