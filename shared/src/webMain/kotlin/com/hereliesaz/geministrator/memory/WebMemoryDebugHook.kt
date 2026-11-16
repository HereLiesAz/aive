package com.hereliesaz.geministrator.memory

/**
 * Dev-only hook for verifying browser memory banks without a provider (see
 * `docs/architecture/LIVE_RUNTIME_ACCEPTANCE.md`, "Browser memory banks"). Active only on a
 * localhost/127.0.0.1 page whose query string carries `aiveMemoryDebug=<commands>`; otherwise it
 * does nothing. Commands, comma-separated:
 *
 * - `write:<workflow>:<episode>` — after the banks open, commits one episode to that workflow's bank.
 * - `dump` — logs `AIVE_MEMORY_DEBUG <json>`: every known bank's episode ids.
 */
class WebMemoryDebugHook private constructor(private val commands: List<String>) {
    /** Runs `write` then `dump` commands on the opened banks. */
    suspend fun afterBanksOpen(banks: MemoryBanks) {
        commands.filter { it.startsWith("write:") }.forEach { command ->
            val (workflow, episode) = command.removePrefix("write:").split(':', limit = 2)
            val store = banks.store(workflow)
            val snapshot = store.read()
            check(store.commit(snapshot.revision, MemoryStoreMutation(episodesToAdd = listOf(debugEpisode(episode, workflow))))) {
                "debug write to $workflow lost a revision race"
            }
        }
        if ("dump" in commands) {
            val entries = mutableListOf<String>()
            for (workflow in banks.known()) {
                val ids = banks.store(workflow).read().episodes.joinToString(",") { "\"${it.id.value}\"" }
                entries += "\"$workflow\":{\"stem\":\"${memoryBankFileStem(workflow)}\",\"episodes\":[$ids]}"
            }
            val bankJson = entries.joinToString(",")
            println("AIVE_MEMORY_DEBUG {\"banks\":{$bankJson}}")
        }
    }

    private fun debugEpisode(id: String, workflow: String) = MemoryEpisode(
        id = MemoryEpisodeId(id),
        sourceSessionId = "debug-session-$id",
        workflowRunId = workflow,
        userPrompt = "debug episode $id",
        chunks = emptyList(),
        createdAtEpochMillis = 1_700_000_000_000L,
    )

    companion object {
        /** The hook for this page, or null when not on localhost or no `aiveMemoryDebug` is given. */
        fun fromLocation(hostname: String, search: String): WebMemoryDebugHook? {
            if (hostname != "localhost" && hostname != "127.0.0.1") return null
            val value = search.removePrefix("?").split('&')
                .firstOrNull { it.startsWith("aiveMemoryDebug=") }
                ?.removePrefix("aiveMemoryDebug=")
                ?: return null
            return WebMemoryDebugHook(value.split(',').map(String::trim).filter(String::isNotEmpty))
        }
    }
}
