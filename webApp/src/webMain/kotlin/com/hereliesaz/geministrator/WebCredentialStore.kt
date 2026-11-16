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
}

/**
 * Provider and repository credentials in localStorage, encrypted with a non-extractable AES-GCM
 * key held in IndexedDB and bound to their storage key, so a value copied under another key does
 * not open. Browsers without Web Crypto or IndexedDB (for example, insecure http origins) fall back to
 * plaintext.
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
        if (!AiveCredentialVault.isSealed(stored)) return stored.trim()
        return runCatching { AiveCredentialVault.open(stored, storageKey).await<JsString>().toString().trim() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)
    }

    suspend fun write(storageKey: String, value: String) {
        val clean = value.trim()
        val stored = if (AiveCredentialVault.ready) AiveCredentialVault.seal(clean, storageKey).await<JsString>().toString() else clean
        window.localStorage.setItem(storageKey, stored)
    }

    /** Removes the value under [storageKey]. */
    fun remove(storageKey: String) {
        window.localStorage.removeItem(storageKey)
    }
}
