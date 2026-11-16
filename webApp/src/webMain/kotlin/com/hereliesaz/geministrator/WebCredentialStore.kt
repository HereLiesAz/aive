package com.hereliesaz.geministrator

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.browser.window
import kotlinx.coroutines.await

/** Bindings for `credential-vault.js`. */
@OptIn(ExperimentalWasmJsInterop::class)
private external object AiveCredentialVault : JsAny {
    val ready: Boolean

    fun seal(plaintext: String, storageKey: String): Promise<JsString>

    fun open(stored: String, storageKey: String): Promise<JsString>

    fun isSealed(stored: String): Boolean

    fun isLegacySealed(stored: String): Boolean
}

/**
 * Provider and repository credentials in localStorage, encrypted with a non-extractable AES-GCM
 * key held in IndexedDB and bound to their storage key, so a value copied under another key does
 * not open. Plaintext and unbound (v1) values written by older versions are re-sealed on first read.
 * Browsers without Web Crypto or IndexedDB (for example, insecure http origins) fall back to
 * plaintext, as before.
 */
@OptIn(ExperimentalWasmJsInterop::class)
internal object WebCredentialStore {
    suspend fun read(storageKey: String): String? {
        val stored = window.localStorage.getItem(storageKey)?.takeIf(String::isNotBlank) ?: return null
        if (!AiveCredentialVault.ready) {
            // A sealed value is ciphertext: without Web Crypto it cannot be opened, and it must
            // never be handed on as if it were the key.
            return if (AiveCredentialVault.isSealed(stored)) null else stored.trim()
        }
        if (!AiveCredentialVault.isSealed(stored)) {
            write(storageKey, stored.trim())
            return stored.trim()
        }
        val opened = runCatching { AiveCredentialVault.open(stored, storageKey).await<JsString>().toString().trim() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        if (AiveCredentialVault.isLegacySealed(stored)) write(storageKey, opened)
        return opened
    }

    suspend fun write(storageKey: String, value: String) {
        val clean = value.trim()
        val stored = if (AiveCredentialVault.ready) AiveCredentialVault.seal(clean, storageKey).await<JsString>().toString() else clean
        window.localStorage.setItem(storageKey, stored)
    }

    /**
     * [read] for a key that older builds stored under the legacy `haive.` spelling: a value found only under the
     * legacy key is opened with that key, re-sealed under [storageKey], and the legacy entry is removed.
     */
    suspend fun readMigrating(storageKey: String): String? {
        read(storageKey)?.let { return it }
        val legacyKey = legacyStorageKey(storageKey) ?: return null
        val legacy = read(legacyKey) ?: return null
        write(storageKey, legacy)
        window.localStorage.removeItem(legacyKey)
        return legacy
    }

    /** Removes the value under [storageKey], and under its legacy spelling so a cleared secret cannot return. */
    fun remove(storageKey: String) {
        window.localStorage.removeItem(storageKey)
        legacyStorageKey(storageKey)?.let { window.localStorage.removeItem(it) }
    }

    // Legacy input spelling: keys older builds used began with `haive.`; the current ones begin with `aive.`.
    private fun legacyStorageKey(storageKey: String): String? =
        if (storageKey.startsWith("aive.")) "haive." + storageKey.removePrefix("aive.") else null
}
