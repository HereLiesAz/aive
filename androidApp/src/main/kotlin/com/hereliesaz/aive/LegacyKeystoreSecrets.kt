package com.hereliesaz.aive

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Read-side migration for Keystore-encrypted secrets that older builds saved in a preferences file
 * and a Keystore key named after the app's previous name. Each `<name>.ciphertext` / `<name>.iv`
 * pair is decrypted with the legacy key and handed to the current store to seal under its own key;
 * only then is the legacy entry removed, and once the legacy file is empty the file and key go too.
 */
internal object LegacyKeystoreSecrets {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val CIPHERTEXT_SUFFIX = ".ciphertext"
    private const val IV_SUFFIX = ".iv"

    /**
     * Moves every secret found in the legacy preferences file named [legacyPreferencesName] (sealed with the
     * Keystore key [legacyKeyAlias]) into the current store. [hasCurrent] says whether the current store already
     * holds a secret under that name, in which case the current one wins; [storeCurrent] seals and saves one.
     */
    fun migrate(
        context: Context,
        legacyPreferencesName: String,
        legacyKeyAlias: String,
        hasCurrent: (name: String) -> Boolean,
        storeCurrent: (name: String, plaintext: String) -> Unit,
    ) {
        val app = context.applicationContext
        val legacy = app.getSharedPreferences(legacyPreferencesName, Context.MODE_PRIVATE)
        val names = legacy.all.keys
            .filter { it.endsWith(CIPHERTEXT_SUFFIX) }
            .map { it.removeSuffix(CIPHERTEXT_SUFFIX) }
        var allMoved = true
        names.forEach { name ->
            val moved = runCatching {
                if (!hasCurrent(name)) {
                    val plaintext = decrypt(
                        legacyKeyAlias,
                        legacy.getString(name + IV_SUFFIX, null) ?: return@runCatching false,
                        legacy.getString(name + CIPHERTEXT_SUFFIX, null) ?: return@runCatching false,
                    ) ?: return@runCatching false
                    storeCurrent(name, plaintext)
                }
                true
            }.getOrDefault(false)
            if (moved) {
                legacy.edit().remove(name + CIPHERTEXT_SUFFIX).remove(name + IV_SUFFIX).commit()
            } else {
                allMoved = false
            }
        }
        if (allMoved) {
            app.deleteSharedPreferences(legacyPreferencesName)
            deleteKey(legacyKeyAlias)
        }
    }

    /** Removes a Keystore key by [alias]; a missing key or an unavailable Keystore is not an error. */
    fun deleteKey(alias: String) {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun decrypt(alias: String, iv: String, ciphertext: String): String? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(alias, null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).decodeToString().trim().takeIf(String::isNotEmpty)
    }
}
