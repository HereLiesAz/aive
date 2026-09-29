package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Opt-in throughput check for the memory layer on a persisted store:
 * `AIVE_MEMORY_BENCHMARK=<sessions> ./gradlew :shared:desktopTest --tests '*MemoryLayerBenchmarkTest'`.
 * Prints consolidation, commit and recall timings; asserts nothing.
 */
class MemoryLayerBenchmarkTest {
    @Test
    fun benchmark() = runBlocking {
        val sessions = System.getenv("AIVE_MEMORY_BENCHMARK")?.toIntOrNull() ?: return@runBlocking
        val store = SettingsMemoryStore(MapSettings())
        val layer = AgentMemoryLayer.createWithMicroAgents(store, ProgrammaticMemoryClerks.all { 1L })
        val random = Random(7)
        var packets = 0
        val consolidation = measureTime {
            repeat(sessions) { index ->
                layer.queue.enqueueSession(session(index, random))
                while (layer.consolidateOne(index * 1_000L) != MemoryConsolidationResult.Idle) packets++
            }
        }
        val snapshot = store.read()
        val commit = measureTime {
            repeat(COMMITS) {
                val current = store.read()
                val entry = current.queue.first()
                check(store.commit(current.revision, MemoryStoreMutation(queueUpserts = listOf(entry))))
            }
        }
        val queries = listOf("SettingsMemoryStore commit", "gradle build failure", "retry parked queue", "database migration")
        val recall = measureTime {
            repeat(RECALLS) { layer.tool.grip(MemoryQuery(queries[it % queries.size])) }
        }
        println(
            "memory benchmark: sessions=$sessions packets=$packets nodes=${snapshot.nodes.size} " +
                "edges=${snapshot.edges.size} | consolidation=$consolidation " +
                "(${consolidation / sessions}/session) | commit=${commit / COMMITS} | grip=${recall / RECALLS}",
        )
    }

    private fun session(index: Int, random: Random): MemorySessionEnvelope {
        fun pick(list: List<String>) = list[random.nextInt(list.size)]
        val subject = pick(SUBJECTS)
        val action = pick(ACTIONS)
        val detail = pick(DETAILS)
        return MemorySessionEnvelope(
            sourceSessionId = "session-$index",
            projectId = "project-${index % 3}",
            workflowRunId = "run-${index / 4}",
            userPrompt = "$action $subject so that $detail.",
            parts = listOf(
                MemorySessionPart(
                    MemorySourceKind.Message,
                    "Agent",
                    """
                    ## Result $index
                    I will $action `$subject` in ${pick(FILES)} because $detail.
                    The ${pick(SUBJECTS)} change needed ${random.nextInt(2, 40)} retries before tests passed.

                    ok

                    We decided to keep ${pick(SUBJECTS)} and ${action} ${pick(SUBJECTS)} later; see #${random.nextInt(1, 400)}.
                    """.trimIndent(),
                ),
            ),
            closedAtEpochMillis = index * 1_000L,
        )
    }

    private companion object {
        const val COMMITS = 50
        const val RECALLS = 50
        val SUBJECTS = listOf(
            "SettingsMemoryStore", "GraphMemoryTool", "the gradle build", "the queue", "DesktopOrtCausalGenerator",
            "the planner", "LocalModelLibrary", "the database migration", "the web worker", "the terrarium",
        )
        val ACTIONS = listOf("refactor", "fix", "replace", "migrate", "test", "document", "optimize", "remove")
        val DETAILS = listOf(
            "every commit re-encodes the snapshot", "recall scans every node", "the build fails on Windows",
            "users asked for control", "latency doubled", "the parked entries never retry",
        )
        val FILES = listOf("MemoryStore.kt", "MemoryTool.kt", "build.gradle.kts", "App.kt", "Main.kt")
    }
}
