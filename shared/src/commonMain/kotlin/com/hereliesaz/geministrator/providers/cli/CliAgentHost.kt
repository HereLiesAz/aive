package com.hereliesaz.geministrator.providers.cli

import androidx.compose.runtime.compositionLocalOf

/** What the keeper's computer has, and has been allowed, for one CLI. */
data class CliAgentStatus(
    val spec: CliAgentSpec,
    /** Where its program was found, or null when it is not installed. */
    val path: String?,
    val version: String?,
    val enabled: Boolean,
    /** Whether the CLI is given its own API-key variable (off by default: it uses its login). */
    val passKeys: Boolean,
    val lastTest: CliTestResult? = null,
    /** Whether a repository that tracks this CLI's own project settings may be run (off: it is refused). */
    val allowProjectConfig: Boolean = false,
)

/** What the Test button saw: whether the installed CLI answered a tiny read-only prompt, and how. */
data class CliTestResult(val ok: Boolean, val summary: String, val atEpochMillis: Long, val millis: Long)

/**
 * The desktop's CLI agents, as the Keeper's room sees them (docs/Local-CLI-Agents.md). Null where the app
 * cannot run a program (phone, web): the screen is then not offered.
 */
interface CliAgentHost {
    suspend fun statuses(): List<CliAgentStatus>
    fun setEnabled(id: String, enabled: Boolean)
    fun setPassKeys(id: String, passKeys: Boolean)

    /** Lets a repository that tracks this CLI's project settings (which can make it run commands) be used. Off by default. */
    fun setAllowProjectConfig(id: String, allow: Boolean) = Unit

    /** Runs the installed CLI read-only on a tiny prompt in an empty folder; uses the keeper's own quota. */
    suspend fun test(id: String): CliTestResult
}

val LocalCliAgentHost = compositionLocalOf<CliAgentHost?> { null }
