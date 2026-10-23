package com.hereliesaz.aive.remote

import android.content.Context
import com.hereliesaz.aive.AndroidDistributedComputeCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the settings screen shows of Remote access. */
internal class RemoteAccessSnapshot(val enabled: Boolean, val pairingCode: String?)

/**
 * Remote access's settings and secrets. Whether it is on and which relay to use are plain
 * preferences (excluded from backup); the pairing, host token included, is one JSON string in the
 * Keystore-encrypted store, which is excluded from backup too. Off by default: nothing starts
 * [AiveRelayHostService] until [enable] succeeds.
 */
internal class RemoteRelayStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secrets = AndroidDistributedComputeCredentialStore.forRemoteRelayPairing(context)

    /** On only while a pairing exists too, so a restored preference alone never starts anything. */
    val enabled: Boolean get() = preferences.getBoolean(KEY_ENABLED, false) && pairing() != null

    var relayUrl: String
        get() = preferences.getString(KEY_RELAY_URL, null)?.takeIf(String::isNotBlank) ?: RelayProtocol.DEFAULT_RELAY_URL
        set(value) {
            preferences.edit().putString(KEY_RELAY_URL, value.trim()).apply()
        }

    fun pairing(): RemoteRelayPairing? = secrets.readToken()?.let { RemoteRelayPairing.fromStorage(it) }

    fun snapshot(): RemoteAccessSnapshot {
        val pairing = pairing()
        val on = preferences.getBoolean(KEY_ENABLED, false) && pairing != null
        return RemoteAccessSnapshot(on, if (on) pairing?.code else null)
    }

    /**
     * Turns Remote access on with a fresh pairing on the saved relay URL, or the existing one when
     * it is for the same relay. Throws [IllegalArgumentException] when the relay URL is unusable.
     */
    fun enable(): RemoteRelayPairing {
        val relay = normalizeRelayUrl(relayUrl)
        val pairing = pairing()?.takeIf { it.relay == relay } ?: RemoteRelayPairing.generate(relay)
        secrets.writeToken(pairing.toStorage())
        check(preferences.edit().putBoolean(KEY_ENABLED, true).commit()) { "Could not save the Remote access setting" }
        return pairing
    }

    /** A new room, tokens and key on the same relay; returns the pairing it replaced, if any. */
    fun reset(): Pair<RemoteRelayPairing?, RemoteRelayPairing> {
        val old = pairing()
        val fresh = RemoteRelayPairing.generate(normalizeRelayUrl(relayUrl))
        secrets.writeToken(fresh.toStorage())
        return old to fresh
    }

    /**
     * Restores a pairing from a setup file without turning Remote access on; [enable] then reuses
     * it because it is for the same relay. Throws [IllegalArgumentException] when it is unreadable.
     */
    fun restore(relayUrl: String?, storedPairing: String) {
        val pairing = requireNotNull(RemoteRelayPairing.fromStorage(storedPairing)) { "Remote access pairing is unreadable" }
        relayUrl?.takeIf(String::isNotBlank)?.let { this.relayUrl = it }
        secrets.writeToken(pairing.toStorage())
    }

    /** Turns Remote access off and forgets the pairing; returns it so its room can be deleted. */
    fun disable(): RemoteRelayPairing? {
        val old = pairing()
        preferences.edit().putBoolean(KEY_ENABLED, false).commit()
        secrets.clear()
        return old
    }

    private companion object {
        const val PREFERENCES_NAME = "aive.remote-relay.settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_RELAY_URL = "relay-url"
    }
}

/** Process-wide status for the settings screen, written by [AiveRelayHostService]. */
internal object AiveRelayHostState {
    private val statusFlow = MutableStateFlow<String?>(null)
    private val revisionFlow = MutableStateFlow(0)

    /** One line on the link's state, or null while it is not running. */
    val status: StateFlow<String?> = statusFlow.asStateFlow()

    /** Bumped when the settings changed outside the settings screen (the notification's Stop). */
    val revision: StateFlow<Int> = revisionFlow.asStateFlow()

    fun setStatus(text: String?) {
        statusFlow.value = text
    }

    fun settingsChanged() {
        revisionFlow.value += 1
    }
}

/**
 * Deletes rooms on the relay. Runs on a process-wide scope so the request outlives the screen
 * that asked for it; a failure is ignored because the room expires on its own and, without the
 * host token that was just forgotten, nobody can use it.
 */
internal object RemoteRelayAdmin {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun deleteRoom(pairing: RemoteRelayPairing?) {
        pairing ?: return
        scope.launch {
            val client = HttpClient(CIO)
            try {
                withTimeoutOrNull(DELETE_TIMEOUT_MILLIS) {
                    client.delete("${pairing.relay}/v1/rooms/${pairing.room}") {
                        header(HttpHeaders.Authorization, "Bearer ${pairing.hostToken}")
                    }
                }
            } catch (_: Exception) {
                // Best effort.
            } finally {
                client.close()
            }
        }
    }

    private const val DELETE_TIMEOUT_MILLIS = 15_000L
}
