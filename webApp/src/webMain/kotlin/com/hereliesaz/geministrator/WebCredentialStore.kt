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

    fun seal(plaintext: String): Promise<JsString>

    fun open(stored: String): Promise<JsString>

    fun isSealed(stored: String): Boolean
}

/**
 * Provider and repository credentials in localStorage, encrypted with a non-extractable AES-GCM
 * key held in IndexedDB. Plaintext values written by older versions are re-sealed on first read.
 * Browsers without Web Crypto or IndexedDB (for example, insecure http origins) fall back to
 * plaintext, as before.
 */
@OptIn(ExperimentalWasmJsInterop::class)
internal object WebCredentialStore {
    suspend fun read(storageKey: String): String? {
        val stored = window.localStorage.getItem(storageKey)?.takeIf(String::isNotBlank) ?: return null
        if (!AiveCredentialVault.ready) return stored.trim()
        if (!AiveCredentialVault.isSealed(stored)) {
            write(storageKey, stored.trim())
            return stored.trim()
        }
        return runCatching { AiveCredentialVault.open(stored).await<JsString>().toString().trim() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)
    }

    suspend fun write(storageKey: String, value: String) {
        val clean = value.trim()
        val stored = if (AiveCredentialVault.ready) AiveCredentialVault.seal(clean).await<JsString>().toString() else clean
        window.localStorage.setItem(storageKey, stored)
    }

    fun remove(storageKey: String) {
        window.localStorage.removeItem(storageKey)
    }
}
