package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.effectiveTokenBudget
import com.hereliesaz.geministrator.domain.RoleSurface
import com.hereliesaz.geministrator.workflow.AgentProviderRegistry
import com.hereliesaz.geministrator.workflow.ScriptedProvider
import com.hereliesaz.geministrator.persistence.InMemorySettings
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
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
    fun budgetsUseParentalMedianAndConflictingSurfacesCannotRecombine() {
        val solo = assertNotNull(hatchFromRun(run(WorkflowRunStatus.Completed, "a" to engineer), listOf(engineer)))
        assertEquals(engineer.effectiveTokenBudget(), solo.geneticTokenCap)
        assertNull(solo.tokenBudget)
        assertEquals(engineer.effectiveTokenBudget(), solo.effectiveTokenBudget())
        val a = engineer.copy(tokenBudget = 900L)
        val b = partner.copy(id = com.hereliesaz.geministrator.domain.RoleDefinitionId("second"), tokenBudget = 400L)
        val source = observed(run(WorkflowRunStatus.Completed, "a" to a, "b" to b), listOf(a,b))
        val child = assertNotNull(hatchFromRun(source, listOf(a,b), reports = evidence(source)))
        assertNull(child.tokenBudget)
        assertEquals(650L, child.geneticTokenCap)
        assertEquals(650L, child.effectiveTokenBudget())
        assertEquals(900L, child.copy(tokenBudget = 900L).effectiveTokenBudget())
        val first = a.copy(surfaces = listOf(RoleSurface.Flowchart("diagram", source = "graph TD; A-->B")))
        val second = b.copy(surfaces = listOf(RoleSurface.Flowchart("diagram", source = "graph TD; C-->D")))
        val conflict = source.copy(roleSnapshot = listOf(first,second))
        assertNull(hatchFromRun(conflict, conflict.roleSnapshot, reports = evidence(conflict)))
    }

    @Test
    fun medianHandlesOddTotalsUnlimitedParentsAndLargeCaps() {
        assertEquals(650L, medianParentalCap(900L, 400L))
        assertEquals(650L, medianParentalCap(400L, 901L))
        assertEquals(900L, medianParentalCap(null, 900L))
        assertEquals(400L, medianParentalCap(400L, null))
        assertNull(medianParentalCap(null, null))
        assertEquals(Long.MAX_VALUE - 1L, medianParentalCap(Long.MAX_VALUE, Long.MAX_VALUE - 2L))
    }

    @Test
    fun birthPreflightUsesRealRegistryWithoutDispatchOrPermissionExpansion() = runBlocking<Unit> {
        val provider = ScriptedProvider("read-only") { emptyFlow() }
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val parent = engineer.copy(capabilitiesRequired = setOf(AgentCapability.RepositoryRead),
            preferredProviderId = provider.id)
        val source = run(WorkflowRunStatus.Completed, "a" to parent).let { r ->
            r.copy(roleSnapshot = listOf(parent), definitionSnapshot = WorkflowDefinition(r.workflowDefinitionId,
                "parent workflow", tasks = listOf(TaskDefinition(TaskDefinitionId("a"), "read", "read", parent.id))))
        }
        val child = assertNotNull(hatchFromRun(source, listOf(parent)))
        kotlin.test.assertTrue(hatchCanExecute(source, child, registry))
        assertFalse(hatchCanExecute(source, child.copy(preferredProviderId = AgentProviderId("missing")), registry))
        assertFalse(hatchCanExecute(source, child.copy(capabilitiesRequired = setOf(AgentCapability.ShellExecution)), registry))
        assertFalse(hatchCanExecute(source, child.copy(toolCeiling = listOf("read")), registry))
        assertFalse(hatchCanExecute(source, child.copy(enabled = true), registry))
        val authority = RoleAuthority.ApproveRelease
        val restricted = source.copy(definitionSnapshot = source.definitionSnapshot!!.let { d ->
            d.copy(tasks = d.tasks.map { it.copy(requiredRoleAuthority = authority) })
        })
        assertFalse(hatchCanExecute(restricted, child.copy(authorities = emptySet()), registry))
        assertFalse(hatchCanExecute(source.copy(definitionSnapshot = null), child, registry))
        assertFalse(provider.observed.isCompleted)
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

    /** Kinship 80 with the engineer: viable, and not a near-clone (provider, model and instructions agree; the contract differs). */
    private val partner = engineer.copy(id = com.hereliesaz.geministrator.domain.RoleDefinitionId("partner"),
        capabilitiesRequired = engineer.capabilitiesRequired + AgentCapability.Testing)
    private val extra = engineer.copy(
        id = com.hereliesaz.geministrator.domain.RoleDefinitionId("extra"),
        instructions = "Extra approach",
        capabilitiesRequired = engineer.capabilitiesRequired + AgentCapability.Research,
        surfaces = listOf(RoleSurface.Flowchart("extra-diagram", source = "graph TD; X-->Y")),
        preferredProviderId = AgentProviderId("z"),
    )

    /** engineer and partner are the closest pair; [third] also did completed work in the same run. */
    private fun withThird(n: Int, third: RoleDefinition): Pair<WorkflowRun, List<RoleDefinition>> {
        val roster = listOf(engineer, partner, third)
        val source = observed(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to partner, "c" to third), roster)
            .copy(id = WorkflowRunId("r$n"))
        return source to roster
    }

    @Test
    fun anAdditionalContributorGivesExactlyOneFunctionalLocusAndNoAuthority() {
        val loci = mutableSetOf<String>()
        for (n in 1..60) {
            val (source, roster) = withThird(n, extra)
            val reports = evidence(source)
            val grown = assertNotNull(hatchFromRun(source, roster, reports = reports))
            val pair = assertNotNull(hatchFromRun(source, roster, reports = reports, extraParents = false))
            assertEquals(listOf(engineer.id, partner.id), pair.parents)
            // All four loci would add something here, so the contributor always becomes a parent.
            assertEquals(listOf(engineer.id, partner.id, extra.id), grown.parents)
            assertEquals(pair.authorities, grown.authorities)
            assertEquals(pair.toolCeiling, grown.toolCeiling)
            assertFalse(grown.enabled)
            val changed = listOfNotNull(
                "instructions".takeIf { "Extra approach" in grown.instructions && "Extra approach" !in pair.instructions },
                "capabilities".takeIf { grown.capabilitiesRequired != pair.capabilitiesRequired },
                "surfaces".takeIf { grown.surfaces != pair.surfaces },
                "source".takeIf { grown.preferredProviderId != pair.preferredProviderId },
            )
            assertEquals(1, changed.size, "run r$n gave $changed")
            loci += changed
            assertEquals(grown, hatchFromRun(source, roster, reports = reports)) // retrying a hatch gives the same child
        }
        assertEquals(setOf("instructions", "capabilities", "surfaces", "source"), loci)
    }

    @Test
    fun anAntagonistOrAnUnauthorisedContributorIsNotAParent() {
        val antagonist = extra.copy(id = com.hereliesaz.geministrator.domain.RoleDefinitionId("antagonist"), name = "Antagonist")
        for (n in 1..20) {
            val (source, roster) = withThird(n, antagonist)
            assertEquals(listOf(engineer.id, partner.id), assertNotNull(hatchFromRun(source, roster, reports = evidence(source))).parents)
        }
        // The extra's own completed task needs an authority the pair's child does not hold.
        val needed = RoleAuthority.entries.first { it !in engineer.authorities }
        val demanding = TaskDefinition(id = TaskDefinitionId("c"), name = "c", objective = "Do c", roleId = extra.id,
            requiredRoleAuthority = needed)
        var seenWithoutRequirement = false
        for (n in 1..20) {
            val (source, roster) = withThird(n, extra)
            val restricted = source.copy(definitionSnapshot = WorkflowDefinition(WorkflowDefinitionId("w"), "w", tasks = listOf(demanding)))
            assertEquals(listOf(engineer.id, partner.id), assertNotNull(hatchFromRun(restricted, roster, reports = evidence(restricted))).parents)
            if (assertNotNull(hatchFromRun(source, roster, reports = evidence(source))).parents.size == 3) seenWithoutRequirement = true
        }
        kotlin.test.assertTrue(seenWithoutRequirement)
    }

    @Test
    fun aChildOfThreeParentsInheritsEchoFromItsPairAlone() {
        val a = engineer.id
        val b = partner.id
        val c = extra.id
        val seed = EchoBirthSeed(WorkflowRunId("r1"), setOf(a, b, c), mapOf(a to 4_000, b to 3_000, c to 9_000),
            mapOf(setOf(a, b) to 100, setOf(a, c) to 50, setOf(b, c) to 50))
        assertEquals(7_000, seed.inherited(listOf(a, b, c))) // near-clone pair: their sum; the extra adds nothing
        val distant = seed.copy(kinships = seed.kinships + (setOf(a, b) to 40))
        assertEquals(16_000 / 12, distant.inherited(listOf(a, b, c))) // a quarter of the contributors' mean
        assertNull(seed.copy(kinships = emptyMap()).inherited(listOf(a, b, c)))
    }

    @Test
    fun twoNearIdenticalParentsCloneInsteadOfInbreeding() {
        val twin = engineer.copy(id = com.hereliesaz.geministrator.domain.RoleDefinitionId("twin"))
        val roster = listOf(engineer, twin)
        val source = observed(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to twin), roster)
        val child = assertNotNull(hatchFromRun(source, roster, reports = evidence(source)))
        assertEquals(1, child.parents.size) // one parent, a clone, not a recombination of two copies of itself
        assertEquals("${twin.name} · clone", child.name)
        // A viable pair that is not a near-clone still breeds, even beside an identical twin.
        val withPartner = listOf(engineer, twin, partner)
        val mixed = observed(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to twin, "c" to partner), withPartner)
        assertEquals(2, assertNotNull(hatchFromRun(mixed, withPartner, reports = evidence(mixed))).parents.size)
        // A clone from a run with several contributors inherits its parent's Echo, not a sum.
        val a = engineer.id
        val seed = EchoBirthSeed(WorkflowRunId("r1"), setOf(a, twin.id), mapOf(a to 4_000, twin.id to 3_000),
            mapOf(setOf(a, twin.id) to 100))
        assertEquals(4_000, seed.inherited(listOf(a)))
    }

    @Test
    fun anInbredCloneMutatesMoreOftenThanALoneCloneButNeverCertainly() {
        val twin = engineer.copy(id = com.hereliesaz.geministrator.domain.RoleDefinitionId("twin"))
        val pairRoster = listOf(engineer, twin)
        var lone = 0
        var inbred = 0
        for (n in 1..3_000) {
            val solo = run(WorkflowRunStatus.Completed, "a" to engineer).copy(id = WorkflowRunId("r$n"))
            if (assertNotNull(hatchFromRun(solo, listOf(engineer))).instructions != engineer.instructions) lone++
            val pair = observed(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to twin), pairRoster)
                .copy(id = WorkflowRunId("r$n"))
            val child = assertNotNull(hatchFromRun(pair, pairRoster, reports = evidence(pair)))
            assertEquals(1, child.parents.size)
            if (child.instructions != engineer.instructions) inbred++
        }
        kotlin.test.assertTrue(lone > 0 && inbred > lone, "lone=$lone inbred=$inbred")
        kotlin.test.assertTrue(inbred < 3_000 / 4)
    }

    @Test
    fun aPairRelatedByDescentIsInbredHoweverFarApartTheyMeasure() {
        fun id(v: String) = com.hereliesaz.geministrator.domain.RoleDefinitionId(v)
        // A clone mutates, so it measures below 95% against its own parent (80 here: the contract differs).
        val child = engineer.copy(id = id("child"), parents = listOf(engineer.id),
            capabilitiesRequired = engineer.capabilitiesRequired + AgentCapability.Testing)
        val roster = listOf(engineer, child)
        val source = observed(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to child), roster)
        assertEquals(1, assertNotNull(hatchFromRun(source, roster, reports = evidence(source))).parents.size)
        // The same pair unrelated by descent breeds.
        val stranger = child.copy(parents = emptyList())
        val strangers = observed(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to stranger), listOf(engineer, stranger))
        assertEquals(2, assertNotNull(hatchFromRun(strangers, listOf(engineer, stranger), reports = evidence(strangers))).parents.size)
        // Siblings share a parent; a grandchild descends from its grandparent through the roster.
        val sibling = child.copy(id = id("sibling"))
        assertEquals(true, relatedByDescent(child, sibling, mapOf(engineer.id to engineer)))
        val grandchild = child.copy(id = id("grandchild"), parents = listOf(child.id))
        val ancestry = listOf(engineer, child, grandchild).associateBy { it.id }
        assertEquals(true, relatedByDescent(engineer, grandchild, ancestry))
        assertEquals(true, relatedByDescent(grandchild, engineer, ancestry))
        assertEquals(false, relatedByDescent(engineer, stranger, ancestry))
        // A cousin (children of siblings) is not counted.
        val cousinA = engineer.copy(id = id("cousin-a"), parents = listOf(child.id))
        val cousinB = engineer.copy(id = id("cousin-b"), parents = listOf(sibling.id))
        assertEquals(false, relatedByDescent(cousinA, cousinB, (ancestry + (sibling.id to sibling))))
    }
}
