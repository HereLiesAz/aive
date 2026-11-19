package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.DesktopCliAgents
import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.ProviderEnablement
import com.hereliesaz.geministrator.providers.cli.CliAgentSpecs
import kotlinx.coroutines.runBlocking

/** A line read from the attached terminal, or null when there is none (no console: a pipe, a service, an agent's shell). */
internal fun interface LocalTerminal {
    fun readLine(prompt: String): String?
}

/**
 * `aive agents install|enable|disable|test <id>` and `aive sources enable|disable <id>`.
 *
 * These run inside this process and are never sent over the control API, MCP or any HTTP route: a token-holder or a
 * prompt-injected agent must not be able to install, switch on or run a vendor CLI. So they need an attached interactive
 * terminal (checked before anything else is touched) and either `--yes` or a typed confirmation of the id. No key is ever
 * taken as an argument; the only flags are `--yes` and `--json`. They edit the stores the desktop app uses
 * ([com.hereliesaz.geministrator.CliAgentSettings], [ProviderEnablement]).
 */
internal class LocalManagement(
    private val terminal: LocalTerminal?,
    private val agents: () -> DesktopCliAgents = { DesktopCliAgents() },
    private val sourceIds: () -> Set<String> = { ProviderCatalog.entries.map { it.id }.toSet() },
    private val setSource: (String, Boolean) -> Unit = ProviderEnablement::set,
) {
    private class Usage(message: String) : RuntimeException(message)

    /** Null when [args] is not one of the local commands, so the caller falls through to the control-API commands. */
    fun run(args: List<String>, output: Appendable, errors: Appendable): Int? {
        val group = args.firstOrNull()
        val verb = args.getOrNull(1)
        val verbs = when (group) {
            "agents" -> AGENT_VERBS
            "sources" -> SOURCE_VERBS
            else -> return null
        }
        if (verb == null || verb !in verbs) return null
        return try {
            execute(group, verb, args.drop(2), output)
        } catch (e: Usage) {
            errors.append("aive: ").append(e.message).append('\n')
            2
        }
    }

    private fun execute(group: String, verb: String, tail: List<String>, output: Appendable): Int {
        var yes = false
        val positionals = mutableListOf<String>()
        tail.forEach { token ->
            when {
                token == "--yes" -> yes = true
                token == "--json" -> Unit
                token.startsWith("-") -> throw Usage("Unknown option $token. Only --yes is accepted; keys are never given as arguments.")
                else -> positionals += token
            }
        }
        if (positionals.size != 1) throw Usage("Usage: aive $group $verb <id> [--yes]")
        val id = positionals.single()
        val known = if (group == "agents") CliAgentSpecs.all.map { it.id }.toSet() else sourceIds()
        if (id !in known) throw Usage("Unknown ${if (group == "agents") "agent" else "source"}: $id")
        if (terminal == null) {
            throw Usage("'aive $group $verb' needs an attached interactive terminal; it is deliberately not available to scripts, services or agents. Nothing was changed.")
        }
        if (!yes) {
            val answer = terminal.readLine("$group $verb $id: type the id to confirm: ")?.trim()
            if (answer != id) throw Usage("Not confirmed; nothing was changed")
        }
        return when (group) {
            "agents" -> agent(verb, id, output)
            else -> {
                setSource(id, verb == "enable")
                output.append("Source $id ${if (verb == "enable") "enabled" else "disabled"}\n")
                0
            }
        }
    }

    private fun agent(verb: String, id: String, output: Appendable): Int {
        val host = agents()
        return when (verb) {
            "enable", "disable" -> {
                host.setEnabled(id, verb == "enable")
                output.append("Agent $id ${if (verb == "enable") "enabled" else "disabled"}\n")
                0
            }
            else -> {
                val result = runBlocking { if (verb == "install") host.install(id) else host.test(id) }
                output.append(if (result.ok) "OK: " else "FAILED: ").append(result.summary).append('\n')
                if (result.ok) 0 else 1
            }
        }
    }

    companion object {
        val AGENT_VERBS = setOf("install", "enable", "disable", "test")
        val SOURCE_VERBS = setOf("enable", "disable")
    }
}
