package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoleSuggestionsTest {
    private val roles: List<RoleDefinition> = BuiltInRoles.all.toList()

    private fun task(id: String, role: RoleDefinition, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id), name = id.replaceFirstChar { it.uppercase() }, objective = id, roleId = role.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(), executor = TaskExecutor.RoleAgent(role.id),
    )

    private fun hive(vararg tasks: TaskDefinition) = WorkflowDefinition(WorkflowDefinitionId("h"), "h", tasks = tasks.toList())

    private fun ids(d: WorkflowDefinition) = suggestRoles(d, roles).map { it.role.id }

    @Test
    fun anEmptyHiveGetsAPlannerAndAnImplementer() {
        assertEquals(listOf(BuiltInRoles.Orchestrator.id, BuiltInRoles.ImplementationEngineer.id), ids(hive()))
    }

    @Test
    fun anImplementerWithNoReviewerOrTesterGetsBoth() {
        val s = suggestRoles(hive(task("build", BuiltInRoles.ImplementationEngineer, "plan"), task("plan", BuiltInRoles.Architect)), roles)
        assertEquals(listOf(BuiltInRoles.CodeReviewer.id, BuiltInRoles.QaEngineer.id), s.map { it.role.id })
        assertEquals(SuggestionReason.NothingReviews, s[0].reason)
        assertEquals("Build", s[0].subject)
    }

    @Test
    fun aReviewedAndTestedHiveNeedsNothing() {
        val d = hive(
            task("plan", BuiltInRoles.Architect),
            task("build", BuiltInRoles.ImplementationEngineer, "plan"),
            task("check", BuiltInRoles.QaEngineer, "build"),
            task("review", BuiltInRoles.CodeReviewer, "check"),
        )
        assertEquals(listOf(BuiltInRoles.Orchestrator.id), ids(d)) // four creatures, no orchestrator
        assertTrue(suggestRoles(d.copy(tasks = d.tasks + task("o", BuiltInRoles.Orchestrator)), roles).isEmpty())
    }

    @Test
    fun aBuilderWithNothingFeedingItGetsAPlanner() {
        val d = hive(task("build", BuiltInRoles.ImplementationEngineer), task("check", BuiltInRoles.QaEngineer, "build"), task("review", BuiltInRoles.CodeReviewer, "check"))
        val s = suggestRoles(d, roles)
        assertTrue(s.any { it.reason == SuggestionReason.NothingFeedsBuilder && it.role.id == BuiltInRoles.Orchestrator.id })
    }

    @Test
    fun aCheckerWithNothingToCheckGetsAnImplementer() {
        val s = suggestRoles(hive(task("check", BuiltInRoles.QaEngineer)), roles)
        assertEquals(listOf(SuggestionReason.NothingFeedsChecker), s.map { it.reason })
        assertEquals(BuiltInRoles.ImplementationEngineer.id, s.single().role.id)
    }

    @Test
    fun noMoreThanThreeAndOnlyEnabledRoles() {
        val d = hive(task("build", BuiltInRoles.ImplementationEngineer), task("x", BuiltInRoles.Researcher), task("y", BuiltInRoles.Researcher))
        assertTrue(suggestRoles(d, roles).size <= MAX_ROLE_SUGGESTIONS)
        val off = roles.map { if (it.id == BuiltInRoles.CodeReviewer.id) it.copy(enabled = false) else it }
        assertTrue(suggestRoles(hive(task("build", BuiltInRoles.ImplementationEngineer)), off).none { it.role.id == BuiltInRoles.CodeReviewer.id })
    }

    @Test
    fun rolesConnectAsTheStarterWorkflowWiresThem() {
        assertEquals(listOf(RoleFunction.Verifier, RoleFunction.Reviewer), BuiltInRoles.ImplementationEngineer.usuallyHandsTo())
        assertTrue(RoleFunction.Implementer in BuiltInRoles.QaEngineer.usuallyReceivesFrom())
        assertTrue(BuiltInRoles.ReleaseEngineer.usuallyHandsTo().isEmpty())
    }

    @Test
    fun aDraftedPlanIsLinkedAndLaidOutLeftToRight() {
        val plan = listOf(task("plan", BuiltInRoles.Architect), task("build", BuiltInRoles.ImplementationEngineer, "plan"), task("review", BuiltInRoles.CodeReviewer, "build"))
        val d = hive(*plan.toTypedArray())
        val run = WorkflowRun(WorkflowRunId("r"), ProjectId("p"), d.id, "o", WorkflowRunStatus.Created, emptyMap(), 0, 0)
        val projection = projectWorkflowTerrarium(d, run, roles)
        val x = projection.subjects.associate { it.node.id to it.position.x }
        assertTrue(x.getValue("plan") < x.getValue("build") && x.getValue("build") < x.getValue("review"))
        val links = projection.relationships.map { it.from to it.to }
        assertTrue("plan" to "build" in links && "build" to "review" in links)
    }
}
