package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.russhwolf.settings.Settings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Durable storage for user-entered presentation state that is not yet part of workflow truth.
 *
 * This intentionally uses a stable namespace and synchronous Settings writes so drafts survive
 * navigation, activity/process recreation, normal application restarts, and application upgrades.
 * Do not rename the namespace as part of a product/branding rename.
 */
internal class DurableUiStateStore(
    private val settings: Settings = Settings(),
    private val rootKey: String = ROOT_KEY,
) {
    fun getString(key: String): String? = settings.getStringOrNull(scoped(key))

    fun putString(key: String, value: String) {
        settings.putString(scoped(key), value)
    }

    fun remove(key: String) {
        settings.remove(scoped(key))
    }

    fun getBoolean(key: String): Boolean? =
        settings.getStringOrNull(scoped(key))?.toBooleanStrictOrNull()

    fun putBoolean(key: String, value: Boolean) {
        settings.putString(scoped(key), value.toString())
    }

    private fun scoped(key: String): String = "$rootKey.$key"

    companion object {
        const val ROOT_KEY: String = "aive.ui-state.v1"
    }
}

internal object DurableUiState {
    val store: DurableUiStateStore = DurableUiStateStore()
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        allowStructuredMapKeys = true
    }
}

private class DurableMutableState<T>(
    private val delegate: MutableState<T>,
    private val onWrite: (T) -> Unit,
) : MutableState<T> by delegate {
    override var value: T
        get() = delegate.value
        set(value) {
            delegate.value = value
            onWrite(value)
        }
}

@Composable
internal fun rememberDurableStringState(
    key: String,
    initialValue: String = "",
): MutableState<String> = remember(key) {
    val value = DurableUiState.store.getString(key) ?: initialValue
    DurableMutableState(mutableStateOf(value)) { DurableUiState.store.putString(key, it) }
}

@Composable
internal fun rememberDurableBooleanState(
    key: String,
    initialValue: Boolean = false,
): MutableState<Boolean> = remember(key) {
    val value = DurableUiState.store.getBoolean(key) ?: initialValue
    DurableMutableState(mutableStateOf(value)) { DurableUiState.store.putBoolean(key, it) }
}

@Composable
internal fun <T> rememberDurableJsonState(
    key: String,
    serializer: KSerializer<T>,
    initialValue: T,
): MutableState<T> = remember(key) {
    val restored = DurableUiState.store.getString(key)
        ?.let { encoded ->
            runCatching { DurableUiState.json.decodeFromString(serializer, encoded) }.getOrNull()
        }
        ?: initialValue
    DurableMutableState(mutableStateOf(restored)) { value ->
        DurableUiState.store.putString(
            key,
            DurableUiState.json.encodeToString(serializer, value),
        )
    }
}
