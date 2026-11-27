package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.memory.MemoryEpisode
import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.MemoryNode
import com.hereliesaz.geministrator.memory.MemoryNodeId
import com.hereliesaz.geministrator.memory.MemoryNodeKind
import com.hereliesaz.geministrator.memory.MemorySnapshot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Echo beyond reads: its own memory, criticism that came back, a fevered creature ignoring it, a septic producer's reach. */
class EchoEffectsTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val roles = listOf(engineer, qa)

    private fun task(id: String, role: com.hereliesaz.geministrator.domain.RoleDefinition, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id), name = id, objective = "Do $id", roleId = role.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(), executor = TaskExecutor.RoleAgent(role.id),
    )

    // plan (engineer) -> build (engineer) -> review (QA)
    private val definition = WorkflowDefinition(
        WorkflowDefinitionId("echo"), "Echo",
        tasks = listOf(task("plan", engineer), task("build", engineer, "plan"), task("review", qa, "build")),
    )

    private fun run(reviewText: String? = null, reviewStatus: TaskRunStatus = TaskRunStatus.Running): WorkflowRun {
        fun runOf(taskId: String, provider: String, status: TaskRunStatus, text: String? = null): Pair<TaskDefinitionId, TaskRun> {
            val t = definition.tasks.first { it.id.value == taskId }
            return t.id to TaskRun(
                id = TaskRunId("r-$taskId"), taskDefinitionId = t.id, status = status, assignedRoleId = t.roleId,
                assignedProviderId = AgentProviderId(provider), providerRunId = ProviderRunId("s-$taskId"),
                artifacts = listOf(ArtifactRef(ArtifactId("artifact-$taskId"),
                    if (text != null) ArtifactKind.Verification else ArtifactKind.Research,
                    TaskRunId("r-$taskId"), taskId, textContent = text ?: "work", createdAtEpochMillis = 1L)),
            )
        }
        return WorkflowRun(
            id = WorkflowRunId("run"), projectId = ProjectId("p"), workflowDefinitionId = definition.id, objective = "o",
            status = WorkflowRunStatus.Completed,
            taskRuns = mapOf(runOf("plan", "a", TaskRunStatus.Completed), runOf("build", "a", TaskRunStatus.Completed),
                runOf("review", "b", reviewStatus, reviewText)),
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L, roleSnapshot = roles, definitionSnapshot = definition,
        )
    }

    private fun report(task: String, at: Long, artifacts: Set<String>? = emptySet(), memory: Set<String>? = null) =
        ProviderUsageRecorded(WorkflowRunId("run"), TaskDefinitionId(task), AgentProviderId(if (task == "review") "b" else "a"),
            ProviderRunId("s-$task"), model = "model-a", modelConsistent = true, occurredAtEpochMillis = at,
            deliveredArtifactIds = artifacts, deliveredMemoryAddresses = memory)

    private val base = listOf(report("plan", 1), report("build", 2, setOf("artifact-plan")),
        report("review", 2, setOf("artifact-build")))

    private val kinship = configurationKinship(engineer, "a", qa, "b", "model-a", "model-a")!!

    private fun engineerEcho(start: Int, run: WorkflowRun, reports: List<ProviderUsageRecorded>,
        authors: Map<String, Set<RoleDefinitionId>> = emptyMap()) =
        EchoHistory(balances = mapOf(engineer.id to start)).advance(run, reports, authors).balances.getValue(engineer.id)

    @Test
    fun aCriticismThatCameBackLowersEchoByHowDifferentTheReviewerIsAndNothingElse() {
        val rejected = run("VERDICT: reject", TaskRunStatus.Completed)
        val returned = base + report("build", 5, setOf("artifact-review"))
        val fall = 15 * max(0, 90 - kinship)
        assertEquals(5_000 + 60 - fall, engineerEcho(5_000, rejected, returned))
        // The verdict never reached the producer: no criticism heard, only the read of its own plan.
        assertEquals(5_060, engineerEcho(5_000, rejected, base))
        // An approval is not criticism.
        val approved = run("VERDICT: approve", TaskRunStatus.Completed)
        assertEquals(5_060, engineerEcho(5_000, approved, returned))
    }

    @Test
    fun aFeveredCreatureStopsRespondingToCriticism() {
        val rejected = run("VERDICT: reject", TaskRunStatus.Completed)
        val returned = base + report("build", 5, setOf("artifact-review"))
        assertEquals(7_060, engineerEcho(7_000, rejected, returned))
        assertEquals(6_060, engineerEcho(6_000, rejected, returned)) // at the threshold it already ignores it
    }

    @Test
    fun memoryItWroteItselfIsFeedbackFromTheSelfAndOnlyThat() {
        val withMemory = base + report("build", 3, artifacts = null, memory = setOf("memory-node:n1"))
        fun echo(authors: Map<String, Set<RoleDefinitionId>>) = engineerEcho(5_000, run(), withMemory, authors)
        assertEquals(5_120, echo(mapOf("memory-node:n1" to setOf(engineer.id))))
        assertEquals(5_060, echo(mapOf("memory-node:n1" to setOf(qa.id)))) // a stranger's: unknown kinship, no step
        assertEquals(5_060, echo(mapOf("memory-node:n1" to setOf(engineer.id, qa.id)))) // not solely its own
        assertEquals(5_060, echo(emptyMap()))
        assertEquals(5_060, echo(mapOf("memory-node:other" to setOf(engineer.id)))) // not a node it carried
    }

    @Test
    fun aSepticProducerContaminatesItsReadersOnTopOfTheRead() {
        val reader = run()
        fun reviewerEcho(engineerStart: Int): Int =
            EchoHistory(balances = mapOf(engineer.id to engineerStart, qa.id to 1_000)).advance(reader, base).balances.getValue(qa.id)
        val k = configurationKinship(qa, "b", engineer, "a", "model-a", "model-a")!!
        val read = when { k >= 95 -> 40; k < 85 -> -4 * (90 - k); else -> 0 }
        assertEquals(1_000 + read + 300, reviewerEcho(9_000))
        assertEquals(1_000 + read, reviewerEcho(8_000))
    }

    @Test
    fun aSepticInputCostsTheReaderIntegrity() {
        val reader = run()
        val septic = assertNotNull(creatureNeeds(TaskDefinitionId("review"), definition, reader, roles, base,
            cumulative = mapOf(engineer.id to 9_000)))
        assertEquals(1, septic.septicInputs)
        assertEquals(98, septic.integrity)
        val fine = assertNotNull(creatureNeeds(TaskDefinitionId("review"), definition, reader, roles, base,
            cumulative = mapOf(engineer.id to 8_000)))
        assertEquals(0, fine.septicInputs)
        assertEquals(100, fine.integrity)
    }

    @Test
    fun authorshipComesFromEpisodeRolesAndUnknownAuthorshipIsLeftOut() {
        fun episode(id: String, role: String?) = MemoryEpisode(MemoryEpisodeId(id), sourceSessionId = "s", roleId = role,
            userPrompt = "p", chunks = emptyList(), createdAtEpochMillis = 1L)
        fun node(id: String, vararg episodes: String) = MemoryNode(MemoryNodeId(id), MemoryNodeKind.NounTag, "t",
            sourceEpisodeIds = episodes.map(::MemoryEpisodeId).toSet(), createdAtEpochMillis = 1L)
        val snapshot = MemorySnapshot(
            episodes = listOf(episode("e1", engineer.id.value), episode("e2", qa.id.value), episode("keeper", null)),
            nodes = listOf(node("own", "e1"), node("shared", "e1", "e2"), node("keepers", "keeper"),
                node("mixed", "e1", "keeper"), node("orphan")),
        )
        assertEquals(
            mapOf("memory-node:own" to setOf(engineer.id), "memory-node:shared" to setOf(engineer.id, qa.id)),
            memoryAuthorsOf(snapshot),
        )
    }
}
