package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.domain.effectiveAllowedTools
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.promptInstructions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HatcheryTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer.copy(preferredProviderId = AgentProviderId("b"))

    private fun run(status: WorkflowRunStatus, vararg tasks: Pair<String, RoleDefinition>) = WorkflowRun(
        id = WorkflowRunId("r1"),
        projectId = ProjectId("p"),
        workflowDefinitionId = WorkflowDefinitionId("w"),
        objective = "o",
        status = status,
        taskRuns = tasks.associate { (t, role) ->
            TaskDefinitionId(t) to TaskRun(TaskRunId("r-$t"), TaskDefinitionId(t), TaskRunStatus.Completed, assignedRoleId = role.id)
        },
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    @Test
    fun aClearedRunRecombinesItsTwoBusiestContributors() {
        val child = assertNotNull(hatchFromRun(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to engineer, "c" to qa), listOf(engineer, qa)))
        assertEquals(listOf(engineer.id, qa.id), child.parents)
        assertEquals("hatch-r1", child.id.value)
        assertEquals(AgentProviderId("b"), child.preferredProviderId)
        assertEquals(engineer.capabilitiesRequired + qa.capabilitiesRequired, child.capabilitiesRequired)
        assertFalse(child.enabled)
    }

    @Test
    fun oneContributorHatchesAClone() {
        val child = assertNotNull(hatchFromRun(run(WorkflowRunStatus.Completed, "a" to engineer), listOf(engineer)))
        assertEquals(listOf(engineer.id), child.parents)
        assertEquals(engineer.instructions, child.instructions)
    }

    @Test
    fun functionalMutationChangesTheActualPromptWithoutChangingAuthority() {
        val source = run(WorkflowRunStatus.Completed, "a" to engineer)
            .copy(id = WorkflowRunId("mutation-58"))
        val variant = assertNotNull(hatchFromRun(source, listOf(engineer)))
        assertEquals(engineer.instructions + "\n\nWhen otherwise comparable approaches are available within the assigned scope, favor reversible choices and identify material trade-offs.", variant.instructions)
        assertEquals(variant.instructions, variant.promptInstructions())
        assertEquals(engineer.authorities, variant.authorities)
        assertEquals(engineer.capabilitiesRequired, variant.capabilitiesRequired)
        assertEquals(engineer.surfaces, variant.surfaces)
        assertEquals(engineer.toolCeiling, variant.toolCeiling)
        assertEquals(engineer.keeperInstructions, variant.keeperInstructions)
        assertEquals(engineer.preferredProviderId, variant.preferredProviderId)
        assertFalse(variant.enabled)
        assertEquals(listOf(engineer.id), variant.parents)
        assertEquals(variant, hatchFromRun(source, listOf(engineer)))
        assertEquals(70, configurationKinship(engineer, "p", variant, "p", "m", "m"))
    }

    @Test
    fun highEchoIncreasesTheChanceButNeverForcesMutation() {
        val source = run(WorkflowRunStatus.Completed, "a" to engineer)
            .copy(id = WorkflowRunId("mutation-0"))
        assertEquals(engineer.instructions, assertNotNull(hatchFromRun(source, listOf(engineer))).instructions)
        val high = assertNotNull(hatchFromRun(source, listOf(engineer), mapOf(engineer.id to 6_000)))
        kotlin.test.assertNotEquals(engineer.instructions, high.instructions)
        val noDraw = source.copy(id = WorkflowRunId("mutation-1"))
        assertEquals(engineer.instructions, assertNotNull(hatchFromRun(noDraw, listOf(engineer),
            mapOf(engineer.id to 10_000))).instructions)
        val historical = source.copy(roleSnapshot = listOf(engineer))
        val edited = engineer.copy(instructions = "Later roster edit")
        assertEquals(high, hatchFromRun(historical, listOf(edited), mapOf(engineer.id to 6_000)))
    }

    @Test
    fun toolMutationOnlyNarrowsANonemptyCeiling() {
        val parent = engineer.copy(toolCeiling = listOf("read", "search"))
        val source = run(WorkflowRunStatus.Completed, "a" to parent).copy(id = WorkflowRunId("tool-17"))
        val child = assertNotNull(hatchFromRun(source, listOf(parent)))
        assertEquals(listOf("read"), child.toolCeiling)
        assertEquals(listOf("read"), effectiveAllowedTools(listOf("read", "search", "write"), child.toolCeiling))
        val single = parent.copy(toolCeiling = listOf("read"))
        assertEquals(listOf("read"), assertNotNull(hatchFromRun(source, listOf(single))).toolCeiling)
        assertEquals(emptyList<String>(), assertNotNull(hatchFromRun(source, listOf(engineer))).toolCeiling)
        assertFalse(child.enabled)
    }

    @Test
    fun modelSourceMutationRequiresDistinctObservedAndStillConfiguredModels() {
        val a = AgentProviderId("a")
        val b = AgentProviderId("b")
        val parent = engineer.copy(preferredProviderId = a)
        val source = run(WorkflowRunStatus.Completed, "a" to parent, "b" to parent).let { original ->
            original.copy(id = WorkflowRunId("source-14"), taskRuns = original.taskRuns.mapValues { (id, task) ->
                task.copy(assignedProviderId = AgentProviderId(id.value), providerRunId = ProviderRunId("session-${id.value}"))
            })
        }
        val reports = source.taskRuns.values.map { task ->
            ProviderUsageRecorded(source.id, task.taskDefinitionId, requireNotNull(task.assignedProviderId),
                requireNotNull(task.providerRunId), model = "model-${task.taskDefinitionId.value}", modelConsistent = true,
                occurredAtEpochMillis = 2L)
        }
        val configured = mapOf(a to "model-a", b to "model-b")
        val child = assertNotNull(hatchFromRun(source, listOf(parent), reports = reports, configuredModels = configured))
        assertEquals(b, child.preferredProviderId)
        assertEquals(parent.authorities, child.authorities)
        assertFalse(child.enabled)
        assertEquals(a, assertNotNull(hatchFromRun(source, listOf(parent), configuredModels = configured)).preferredProviderId)
        assertEquals(a, assertNotNull(hatchFromRun(source, listOf(parent), reports = reports,
            configuredModels = mapOf(a to "model-a", b to "new-model"))).preferredProviderId)
        val stale = reports.map { if (it.providerId == b) it.copy(providerRunId = ProviderRunId("old")) else it }
        assertEquals(a, assertNotNull(hatchFromRun(source, listOf(parent), reports = stale,
            configuredModels = configured)).preferredProviderId)
    }

    @Test
    fun aRunThatHasNotClearedHatchesNothing() {
        assertNull(hatchFromRun(run(WorkflowRunStatus.Failed, "a" to engineer), listOf(engineer)))
    }

    @Test
    fun actualHatchedConfigurationsExpressFunctionalInheritanceWithoutNewAuthority() {
        val parent=assertNotNull(spatialAgentGenome(engineer))
        val clone=assertNotNull(hatchFromRun(run(WorkflowRunStatus.Completed,"a" to engineer),listOf(engineer)))
        assertEquals(parent,spatialAgentGenome(clone))
        val hybrid=assertNotNull(hatchFromRun(run(WorkflowRunStatus.Completed,"a" to engineer,"b" to engineer,"c" to qa),
            listOf(engineer,qa)))
        val genes=assertNotNull(spatialAgentGenome(hybrid))
        assertEquals(SpatialCellSignal.Excitatory,genes.pigment)
        assertEquals(SpatialCellSignal.Inhibitory,genes.accent)
        assertEquals(engineer.authorities,hybrid.authorities)
        assertFalse(hybrid.enabled)
        kotlin.test.assertNotEquals(parent.configurationSeed,genes.configurationSeed)
    }
}
