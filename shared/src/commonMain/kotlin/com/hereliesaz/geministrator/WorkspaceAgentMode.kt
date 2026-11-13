package com.hereliesaz.geministrator

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the workspace agent (the coding agent that works a linked local repository) may change files. Off by
 * default: it plans, applies its change in an isolated worktree and commits a checkpoint, as it always has. While
 * read-only is on it still reads the repository and plans, and stops before anything is written or committed.
 * The state is shown in the Keeper's room under Local agents.
 */
object WorkspaceAgentMode {
    const val READ_ONLY_KEY: String = "aive.workspace-agent.read-only.v1"

    private val settings: Settings? by lazy { runCatching { Settings() }.getOrNull() }
    private val state: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(runCatching { settings?.getBoolean(READ_ONLY_KEY, false) }.getOrNull() ?: false)
    }

    val readOnly: StateFlow<Boolean> get() = state

    fun setReadOnly(value: Boolean) {
        state.value = value
        runCatching { settings?.putBoolean(READ_ONLY_KEY, value) }
    }
}
