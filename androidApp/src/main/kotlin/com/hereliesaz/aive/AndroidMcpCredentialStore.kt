package com.hereliesaz.aive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hereliesaz.geministrator.mcp.McpCredentialVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * MCP server bearer tokens, encrypted with an Android Keystore key like the other connection
 * secrets, in their own preferences file (excluded from backup and device transfer).
 */
internal class AndroidMcpCredentialStore(context: Context) : McpCredentialVault {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun read(serverId: String): String? {
        val encrypted = preferences.getString("$serverId.ciphertext", null) ?: return null
        val iv = preferences.getString("$serverId.iv", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).decodeToString().trim().takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    override suspend fun write(serverId: String, token: String) {
        val clean = token.trim()
        require(clean.isNotEmpty()) { "The token is empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(clean.encodeToByteArray())
        check(
            preferences.edit()
                .putString("$serverId.ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("$serverId.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        ) { "Could not save the MCP server token" }
    }

    override suspend fun clear(serverId: String) {
        preferences.edit().remove("$serverId.ciphertext").remove("$serverId.iv").commit()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** Excluded from backup like the other credential files. */
        const val PREFERENCES_NAME = "aive.mcp.credentials"
        private const val KEY_ALIAS = "aive.mcp.tokens"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
