package com.hereliesaz.geministrator.memory

/**
 * Dev-only hook for verifying browser memory banks without a provider (see
 * `docs/architecture/LIVE_RUNTIME_ACCEPTANCE.md`, "Browser memory banks"). Active only on a
 * localhost/127.0.0.1 page whose query string carries `aiveMemoryDebug=<commands>`; otherwise it
 * does nothing. Commands, comma-separated:
 *
 * - `seed-legacy:<tag>` — before the banks open, writes one episode each for workflows `wf-a` and
 *   `wf-b` (ids `legacy-<tag>-a`/`-b`) into the old Settings memory log, which startup imports into
 *   the old shared database and then splits into banks once.
 * - `write:<workflow>:<episode>` — after the banks open, commits one episode to that workflow's bank.
 * - `dump` — logs `AIVE_MEMORY_DEBUG <json>`: every known bank's episode ids and the split report.
 */
class WebMemoryDebugHook private constructor(private val commands: List<String>) {
    /** Runs `seed-legacy` commands; call before [openWebMemoryBanks]. */
    suspend fun beforeBanksOpen() {
        commands.filter { it.startsWith("seed-legacy:") }.forEach { command ->
            val tag = command.removePrefix("seed-legacy:")
            val legacy = SettingsMemoryStore.createDefault()
            val current = legacy.read()
            legacy.replace(
                current.copy(
                    revision = current.revision + 1,
                    episodes = current.episodes + listOf("a", "b").map { suffix ->
                        debugEpisode("legacy-$tag-$suffix", "wf-$suffix")
                    },
                ),
            )
            println("AIVE_MEMORY_DEBUG_SEEDED $tag")
        }
    }

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
            val report = banks.migrationState.migrationReport()
            val entries = mutableListOf<String>()
            for (workflow in banks.known()) {
                val ids = banks.store(workflow).read().episodes.joinToString(",") { "\"${it.id.value}\"" }
                entries += "\"$workflow\":{\"stem\":\"${memoryBankFileStem(workflow)}\",\"episodes\":[$ids]}"
            }
            val bankJson = entries.joinToString(",")
            val reportJson = report?.let {
                "{\"sourceRevision\":${it.sourceRevision},\"episodesPerWorkflow\":{" +
                    it.episodesPerWorkflow.entries.joinToString(",") { (k, v) -> "\"$k\":$v" } + "}}"
            } ?: "null"
            println("AIVE_MEMORY_DEBUG {\"banks\":{$bankJson},\"migration\":$reportJson}")
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
