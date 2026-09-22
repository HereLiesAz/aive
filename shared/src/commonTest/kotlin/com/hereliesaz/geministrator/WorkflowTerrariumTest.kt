package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2SwarmIdentityKind
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationshipKind
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowTerrariumTest {
    @Test
    fun automatedExecutorDependencyBecomesAutomatedProcessCreatureAndFlowStaysSynaptic() {
        val implement = agentTask(
            id = "implement",
            roleId = BuiltInRoles.ImplementationEngineer.id,
        )
        val tests = TaskDefinition(
            id = TaskDefinitionId("tests"),
            name = "Run tests",
            objective = "Execute regression tests",
            roleId = null,
            dependsOn = setOf(implement.id),
            executor = TaskExecutor.TestRunner("./gradlew test"),
        )
        val verify = agentTask(
            id = "verify",
            roleId = BuiltInRoles.QaEngineer.id,
            dependsOn = setOf(tests.id),
        )
        val definition = workflow(implement, tests, verify)
        val projection = projectWorkflowTerrarium(
            definition = definition,
            run = runFor(definition),
            roles = listOf(BuiltInRoles.ImplementationEngineer, BuiltInRoles.QaEngineer),
        )

        // A deterministic executor (TestRunner) still represents real work, so it gets its own
        // creature node — the automated-process neuron — rather than becoming a worn adornment.
        val testsSubject = assertNotNull(projection.subjects.singleOrNull { it.node.id == "tests" })
        assertEquals("Test Runner", testsSubject.node.label)
        assertEquals(
            NodeCreatureRoleKind.AutomatedProcess,
            classifyNodeCreatureRole(testsSubject.node.label),
        )
        assertTrue(projection.subjects.any { it.node.id == TERRARIUM_ORCHESTRATOR_ID })
        assertEquals(
            H2g2SwarmIdentityKind.Orchestrator,
            projection.subjects.single { it.node.id == TERRARIUM_ORCHESTRATOR_ID }.identityKind,
        )
        assertTrue(projection.adornments["verify"].orEmpty().isEmpty())
        assertTrue(
            projection.relationships.any {
                it.kind == H2g2TerrariumRelationshipKind.Dependency &&
                    it.from == "implement" && it.to == "tests"
            },
        )
        assertTrue(
            projection.relationships.any {
                it.kind == H2g2TerrariumRelationshipKind.Dependency &&
                    it.from == "tests" && it.to == "verify"
            },
        )
    }

    @Test
    fun explicitOrchestratorTaskUsesCanonicalHostIdentityWithoutSyntheticDuplicate() {
        val orchestrator = agentTask("orchestrate", BuiltInRoles.Orchestrator.id)
        val worker = agentTask(
            id = "worker",
            roleId = BuiltInRoles.ImplementationEngineer.id,
            dependsOn = setOf(orchestrator.id),
        )
        val definition = workflow(orchestrator, worker)
        val projection = projectWorkflowTerrarium(
            definition = definition,
            run = runFor(definition),
            roles = listOf(BuiltInRoles.Orchestrator, BuiltInRoles.ImplementationEngineer),
        )

        assertTrue(projection.subjects.none { it.node.id == TERRARIUM_ORCHESTRATOR_ID })
        assertEquals(
            H2g2SwarmIdentityKind.Orchestrator,
            projection.subjects.single { it.node.id == orchestrator.id.value }.identityKind,
        )
    }

    @Test
    fun layoutRoundTripsAndClampsNormalizedCoordinates() {
        val settings = MapSettings()
        val definitionId = WorkflowDefinitionId("workflow/one")
        val first = SettingsTerrariumLayoutStore(settings)
        first.put(definitionId, "agent:a", H2g2TerrariumPosition(-4f, 7f))

        val restored = SettingsTerrariumLayoutStore(settings)
            .load(definitionId)
            .getValue("agent:a")

        assertEquals(.05f, restored.x)
        assertEquals(.93f, restored.y)
    }

    @Test
    fun dragDropAddsDependencyButRejectsCycle() {
        val a = agentTask("a", BuiltInRoles.ImplementationEngineer.id)
        val b = agentTask("b", BuiltInRoles.QaEngineer.id)
        val definition = workflow(a, b)

        val first = assertIs<TerrariumDependencyEditResult.Applied>(
            addTerrariumDependency(definition, downstreamId = b.id, upstreamId = a.id),
        ).definition
        assertEquals(setOf(a.id), first.tasks.single { it.id == b.id }.dependsOn)

        val cycle = addTerrariumDependency(first, downstreamId = a.id, upstreamId = b.id)
        assertIs<TerrariumDependencyEditResult.Rejected>(cycle)
    }

    private fun agentTask(
        id: String,
        roleId: RoleDefinitionId,
        dependsOn: Set<TaskDefinitionId> = emptySet(),
    ): TaskDefinition = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = roleId,
        dependsOn = dependsOn,
        executor = TaskExecutor.RoleAgent(roleId),
    )

    private fun workflow(vararg tasks: TaskDefinition): WorkflowDefinition = WorkflowDefinition(
        id = WorkflowDefinitionId("terrarium-test"),
        name = "Terrarium test",
        tasks = tasks.toList(),
    )

    private fun runFor(definition: WorkflowDefinition): WorkflowRun = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = ProjectId("project"),
        workflowDefinitionId = definition.id,
        objective = "Test the terrarium",
        status = WorkflowRunStatus.Created,
        taskRuns = emptyMap(),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
}
