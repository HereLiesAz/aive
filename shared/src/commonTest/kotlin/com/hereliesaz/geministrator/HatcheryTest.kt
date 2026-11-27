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

    /** Test evidence names the actual session, not the role's preferred source. */
    private fun observed(source: WorkflowRun, roles: List<RoleDefinition>): WorkflowRun = source.copy(
        roleSnapshot = roles,
        taskRuns = source.taskRuns.mapValues { (id, task) ->
            task.copy(assignedProviderId = AgentProviderId("a"), providerRunId = ProviderRunId("session-${id.value}"))
        },
    )

    private fun evidence(source: WorkflowRun) = source.taskRuns.values.map { task ->
        ProviderUsageRecorded(source.id, task.taskDefinitionId, requireNotNull(task.assignedProviderId),
            requireNotNull(task.providerRunId), model = "shared-model", modelConsistent = true, occurredAtEpochMillis = 2L)
    }

    @Test
    fun aClearedRunRecombinesItsMostRelatedViableContributors() {
        val compatible = qa.copy(instructions = engineer.instructions, keeperInstructions = engineer.keeperInstructions)
        val roster = listOf(engineer, compatible)
        val source = observed(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to engineer, "c" to compatible), roster)
        val child = assertNotNull(hatchFromRun(source, roster, reports = evidence(source)))
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
    fun fertilityRequiresTheDocumentedThresholdsAndKnownEvidence() {
        assertFalse(fertilityAllowsPair(74, false, 3, 2))
        kotlin.test.assertTrue(fertilityAllowsPair(75, false, 2, 1))
        kotlin.test.assertTrue(fertilityAllowsPair(60, true, 3, 1))
        kotlin.test.assertTrue(fertilityAllowsPair(60, false, 4, 1))
        assertFalse(fertilityAllowsPair(59, true, 4, 2))
        kotlin.test.assertTrue(fertilityAllowsPair(59, false, 3, 3))
        val clone = engineer.copy(id = com.hereliesaz.geministrator.domain.RoleDefinitionId("clone"))
        val roster = listOf(engineer, clone)
        val source = observed(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to clone), roster)
        val reports = evidence(source)
        val child = assertNotNull(hatchFromRun(source, roster, reports = reports))
        assertEquals(engineer.instructions, child.instructions) // identical inherited instructions are not doubled
        assertNull(hatchFromRun(source, roster))
        assertNull(hatchFromRun(source, roster, reports = reports.dropLast(1)))
        val incompatible = clone.copy(instructions = "Different instructions")
        val changed = source.copy(roleSnapshot = listOf(engineer, incompatible))
        assertNull(hatchFromRun(changed, changed.roleSnapshot, reports = reports))
        val a = engineer.copy(toolCeiling = listOf("read"))
        val b = clone.copy(toolCeiling = listOf("search"))
        val disjoint = source.copy(roleSnapshot = listOf(a,b))
        assertNull(hatchFromRun(disjoint, disjoint.roleSnapshot, reports = reports))
    }

    @Test
    fun lowKinshipRequiresThreeActualComparableGenera() {
        val second = engineer.copy(id = com.hereliesaz.geministrator.domain.RoleDefinitionId("second"),
            instructions = "Second approach")
        val third = engineer.copy(id = com.hereliesaz.geministrator.domain.RoleDefinitionId("third"),
            instructions = "Third approach")
        val roster = listOf(engineer, second, third)
        val source = observed(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to second, "c" to third), roster)
        val reports = evidence(source).map { it.copy(model = "model-${it.taskDefinitionId.value}") }
        assertEquals(setOf(40), hatchKinships(source, roster, reports).values.toSet())
        assertNull(hatchFromRun(source, roster, reports = reports))
        val genera = mapOf(engineer.id to "genus-one", second.id to "genus-two", third.id to "genus-three")
        val hybrid = assertNotNull(hatchFromRun(source, roster, reports = reports, genera = genera))
        assertEquals(listOf(engineer.id, second.id), hybrid.parents)
        assertFalse(hybrid.enabled)
        assertNull(hatchFromRun(source, roster, reports = reports.dropLast(1), genera = genera))
        assertNull(hatchFromRun(source, roster, reports = reports, genera = genera + (third.id to "genus-two")))
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
        val compatible = qa.copy(instructions = engineer.instructions, keeperInstructions = engineer.keeperInstructions)
        val roster = listOf(engineer, compatible)
        val source = observed(run(WorkflowRunStatus.Completed,"a" to engineer,"b" to engineer,"c" to compatible), roster)
        val hybrid = assertNotNull(hatchFromRun(source, roster, reports = evidence(source)))
        val genes=assertNotNull(spatialAgentGenome(hybrid))
        assertEquals(SpatialCellSignal.Excitatory,genes.pigment)
        assertEquals(SpatialCellSignal.Inhibitory,genes.accent)
        assertEquals(engineer.authorities,hybrid.authorities)
        assertFalse(hybrid.enabled)
        kotlin.test.assertNotEquals(parent.configurationSeed,genes.configurationSeed)
    }
}
