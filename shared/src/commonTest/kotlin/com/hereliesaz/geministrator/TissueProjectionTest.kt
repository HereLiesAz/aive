package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TissueProjectionTest {
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("tissue"),
        name = "Tissue",
        tasks = listOf("contract", "implement", "review").map { id ->
            TaskDefinition(
                id = TaskDefinitionId(id),
                name = id,
                objective = "Do $id",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
            )
        },
    )
    private val positions = mapOf(
        "contract" to H2g2TerrariumPosition(0.2f, 0.2f),
        "implement" to H2g2TerrariumPosition(0.4f, 0.4f),
        "review" to H2g2TerrariumPosition(0.6f, 0.4f),
    )

    @Test
    fun aRestingWorkflowHasNoEggAndNoCapillaries() {
        val scene = projectTissue(definition, run(WorkflowRunStatus.Created), positions)
        assertNull(scene.egg)
        assertEquals(emptyList(), scene.capillaries)
    }

    @Test
    fun eachAssignedProviderIsOneCapillary() {
        val scene = projectTissue(
            definition,
            run(
                WorkflowRunStatus.Running,
                "contract" to (TaskRunStatus.Completed to "b"),
                "implement" to (TaskRunStatus.Running to "a"),
                "review" to (TaskRunStatus.Created to "a"),
            ),
            positions,
        )
        assertEquals(listOf("a", "b"), scene.capillaries.map { it.providerId })
        assertEquals("b", scene.providerOf["contract"])
    }

    @Test
    fun theRunIsAnEggFedByTheTasksBeingWorkedOn() {
        val scene = projectTissue(
            definition,
            run(
                WorkflowRunStatus.Running,
                "contract" to (TaskRunStatus.Completed to "a"),
                "implement" to (TaskRunStatus.Running to "a"),
                "review" to (TaskRunStatus.Verifying to "a"),
            ),
            positions,
        )
        val egg = assertNotNull(scene.egg)
        assertEquals(TissueEggOutcome.Gestating, egg.outcome)
        assertEquals(1f / 3f, egg.growth)
        assertEquals(listOf("implement", "review"), egg.feederIds)
        // centred under its feeders
        assertEquals(0.5f, egg.position.x, 0.001f)
    }

    @Test
    fun aFailedRunWithersItsEggAndACompletedOneHatches() {
        assertEquals(
            TissueEggOutcome.Withered,
            projectTissue(definition, run(WorkflowRunStatus.Failed, "contract" to (TaskRunStatus.Failed to "a")), positions).egg?.outcome,
        )
        assertEquals(
            TissueEggOutcome.Hatched,
            projectTissue(definition, run(WorkflowRunStatus.Completed, "contract" to (TaskRunStatus.Completed to "a")), positions).egg?.outcome,
        )
    }

    @Test
    fun aCreatureWaitingOnAPersonRisesToTheSurface() {
        val scene = projectTissue(
            definition,
            run(WorkflowRunStatus.AwaitingHuman, "review" to (TaskRunStatus.AwaitingApproval to "a"), "implement" to (TaskRunStatus.Completed to "a")),
            positions,
        )
        assertEquals(setOf("review"), scene.atSurface)
    }

    private fun run(status: WorkflowRunStatus, vararg tasks: Pair<String, Pair<TaskRunStatus, String>>) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = ProjectId("project"),
        workflowDefinitionId = definition.id,
        objective = "Test the tissue",
        status = status,
        taskRuns = tasks.associate { (id, state) ->
            TaskDefinitionId(id) to TaskRun(
                id = TaskRunId("run-$id"),
                taskDefinitionId = TaskDefinitionId(id),
                status = state.first,
                assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
                assignedProviderId = AgentProviderId(state.second),
            )
        },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
}
