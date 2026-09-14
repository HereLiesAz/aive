package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryMicroAgentsTest {
    @Test
    fun codeHintsTreatSymbolsAsEntitiesAndOperationsAsActions() {
        val hints = extractCodeSemanticHints(
            """
            class UserRepository {
                fun saveUser(user: User) = api.post("/users", user)
            }
            POST /users
            ./gradlew test
            git commit -m "persist user"
            `UserRepository.saveUser`
            """.trimIndent(),
        )

        assertTrue(hints.nounCandidates.any { "UserRepository" in it })
        assertTrue(hints.nounCandidates.any { "saveUser" in it })
        assertTrue(hints.nounCandidates.any { "/users" in it })
        assertContains(hints.verbCandidates, "persist")
        assertContains(hints.verbCandidates, "post")
        assertTrue(hints.verbCandidates.any { it == "test" || "test" in it })
        assertTrue(hints.verbCandidates.any { it == "commit" || "commit" in it })
    }

    @Test
    fun nounAndVerbAgentsReceiveSameCodeWithDifferentSemanticHints() = runBlocking {
        val seen = mutableMapOf<MemoryMicroAgentRole, MemoryWorkPacket>()
        val agents = MemoryMicroAgentRouter.REQUIRED_ROLES.map { role ->
            RecordingMicroAgent(role, seen)
        }
        val router = MemoryMicroAgentRouter(agents)
        val packet = MemoryWorkPacket(
            queueId = MemoryQueueId("queue"),
            episodeId = MemoryEpisodeId("episode"),
            stage = MemoryConsolidationStage.Tags,
            packetKey = "tags-0",
            items = listOf(
                MemoryWorkItem(
                    id = "context",
                    kind = "node:Context",
                    text = "UserRepository.saveUser(user) validates then writes the user to POST /users.",
                ),
            ),
            instruction = "Create semantic indexes.",
        )

        router.process(packet)

        val nounPacket = assertNotNull(seen[MemoryMicroAgentRole.NounTagger])
        val verbPacket = assertNotNull(seen[MemoryMicroAgentRole.VerbTagger])
        assertEquals(packet.items.single().text, nounPacket.items.single().text)
        assertEquals(packet.items.single().text, verbPacket.items.single().text)
        assertTrue(nounPacket.items.single().metadata[CODE_NOUN_HINTS].orEmpty().contains("UserRepository"))
        assertTrue(verbPacket.items.single().metadata[CODE_VERB_HINTS].orEmpty().contains("persist"))
        assertTrue(nounPacket.instruction.contains("semantic entity/reference"))
        assertTrue(verbPacket.instruction.contains("semantic action/transformation"))
    }

    @Test
    fun routerConstrainsPacketsToSmallestLocalModelBudget() {
        val agents = MemoryMicroAgentRouter.REQUIRED_ROLES.mapIndexed { index, role ->
            RecordingMicroAgent(
                role = role,
                sink = mutableMapOf(),
                spec = MemoryMicroAgentModelSpec(
                    modelId = "model-$role",
                    maxInputItems = if (index == 0) 5 else 12,
                    maxInputChars = if (index == 1) 2_048 else 8_000,
                    maxMutations = if (index == 2) 7 else 48,
                ),
            )
        }
        val constrained = MemoryMicroAgentRouter(agents).constrainPolicy(
            MemoryConsolidationPolicy(
                maxPacketItems = 24,
                maxPacketChars = 12_000,
                maxMutationsPerPacket = 96,
            ),
        )

        assertEquals(5, constrained.maxPacketItems)
        assertEquals(2_048, constrained.maxPacketChars)
        assertEquals(7, constrained.maxMutationsPerPacket)
    }

    private class RecordingMicroAgent(
        override val role: MemoryMicroAgentRole,
        private val sink: MutableMap<MemoryMicroAgentRole, MemoryWorkPacket>,
        override val model: MemoryMicroAgentModelSpec = MemoryMicroAgentModelSpec("test-$role"),
    ) : MemoryMicroAgent {
        override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
            sink[role] = packet
            return MemoryMutationBatch()
        }
    }
}
