package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The associators skip recomputation and only rerun the edge-reading rule after their own commits.
 * After every session, a from-scratch recomputation must find nothing they missed.
 */
class IncrementalAssociationTest {
    @Test
    fun cachedRefreshReachesTheSameFixedPointAsFullRecomputation() = runBlocking {
        val store = InMemoryMemoryStore()
        // Low thresholds so clusters condense and the edge-reading overlap rule actually runs.
        val policy = MemoryConsolidationPolicy(maxSimilarPerKind = MemoryNodeKind.entries.associateWith { 2 })
        val layer = AgentMemoryLayer.createWithMicroAgents(store, ProgrammaticMemoryClerks.all { 1L }, policy)
        val subjects = listOf("SettingsMemoryStore", "GraphMemoryTool", "the gradle build", "the queue")
        val details = listOf("commits re-encode the snapshot", "recall scans every node", "Max connections: 50.", "Max connections: 50.")
        var condensed = 0
        var overlap = 0
        repeat(24) { index ->
            val subject = subjects[index % subjects.size]
            layer.queue.enqueueSession(
                MemorySessionEnvelope(
                    sourceSessionId = "s$index",
                    projectId = "p${index % 2}",
                    workflowRunId = "run-${index / 3}",
                    userPrompt = "Fix $subject because ${details[index % details.size]}",
                    parts = listOf(
                        MemorySessionPart(MemorySourceKind.Message, "Agent", "Fixed `$subject` in MemoryStore.kt; see #${index % 5}."),
                    ),
                    closedAtEpochMillis = index * 60_000L,
                ),
            )
            val now = index * 60_000L
            while (layer.consolidateOne(now) != MemoryConsolidationResult.Idle) Unit

            val snapshot = store.read()
            assertEquals(emptyList(), snapshot.programmaticAssociationCandidates(now, Int.MAX_VALUE).map { it.id.value }, "session $index")
            assertEquals(emptyList(), snapshot.lexicalAssociationCandidates(now, limit = Int.MAX_VALUE).map { it.id.value }, "session $index")
            condensed = snapshot.edges.count { it.relation == MemoryRelationKind.CondensedFrom }
            overlap = snapshot.edges.count { it.metadata["basis"] == "condensation:overlap" }
        }
        assertTrue(condensed > 0 && overlap > 0, "the scenario should exercise condensation overlap ($condensed, $overlap)")
    }
}
