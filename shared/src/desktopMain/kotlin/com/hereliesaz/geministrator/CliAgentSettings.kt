package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.net.ChildProcessEnvironment
import com.hereliesaz.geministrator.providers.cli.CliAgentSpec
import java.util.prefs.Preferences

/**
 * Which CLIs the keeper let run on this computer, and whether each may be given its API-key variables. Off by default.
 *
 * Public because the desktop app and the `aive` command line (`cliApp`) edit the same store: `java.util.prefs`, one node per user, so a
 * switch made in either shows in the other. It is only ever written from a process the keeper is sitting at; no HTTP route or tool reaches it.
 */
class CliAgentSettings(private val node: () -> Preferences = { Preferences.userRoot().node(PREFERENCES_NODE) }) {
    fun enabled(id: String): Boolean = node().getBoolean("$id.enabled", false)
    fun setEnabled(id: String, value: Boolean) = write("$id.enabled", value)
    fun passKeys(id: String): Boolean = node().getBoolean("$id.pass-keys", false)
    fun setPassKeys(id: String, value: Boolean) = write("$id.pass-keys", value)

    /** Whether a checkout that tracks this CLI's own project settings may be run (off: such a checkout is refused). */
    fun allowProjectConfig(id: String): Boolean = node().getBoolean("$id.allow-project-config", false)
    fun setAllowProjectConfig(id: String, value: Boolean) = write("$id.allow-project-config", value)

    private fun write(key: String, value: Boolean) {
        val n = node()
        n.putBoolean(key, value)
        runCatching { n.flush() }
    }

    private companion object {
        const val PREFERENCES_NODE = "com/hereliesaz/geministrator/cli-agents"
    }
}

/** The environment a CLI gets: the basics, how it finds its own login, a proxy and certificates if set; no vendor key unless the keeper chose. */
fun cliEnvironmentFor(spec: CliAgentSpec, settings: CliAgentSettings, inherited: Map<String, String>): Map<String, String> {
    val allowed = ChildProcessEnvironment.BASE + spec.authEnvironment + if (settings.passKeys(spec.id)) spec.optionalKeyEnvironment else emptyList()
    val kept = inherited.filterKeys { key -> allowed.any { it.equals(key, ignoreCase = true) } }.toMutableMap()
    sanitizeGitEnvironment(kept)
    kept["NO_COLOR"] = "1"
    kept["TERM"] = "dumb"
    return kept
}
