package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.TaskExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodingWorkflowSuiteTest {
    private val entries = CodingWorkflowSuite.all()

    @Test
    fun theSuiteIsBigEnoughAndEveryHiveHasADistinctIdAndNameAndDescription() {
        assertTrue(entries.size >= 15, "a robust suite, not a sample")
        assertEquals(entries.size, entries.map { it.definition.id.value }.toSet().size)
        assertEquals(entries.size, entries.map { it.definition.name }.toSet().size)
        assertTrue(entries.all { it.definition.id.value.startsWith("suite-") && !it.definition.description.isNullOrBlank() })
        assertTrue(entries.count { it.schedule != null } >= 5, "and some of it runs by itself")
    }

    @Test
    fun everyHiveIsAValidGraphAndExpandsForARun() {
        entries.forEach { entry ->
            assertEquals(emptyList(), WorkflowGraphValidator.validate(entry.definition), entry.definition.id.value)
            WorkflowDefinitionExpander.expand(entry.definition, BuiltInRoles.all)
        }
    }

    @Test
    fun everyAgentStepIsGivenToARoleThatHoldsTheAuthorityItExercises() {
        val roles = BuiltInRoles.all.associateBy { it.id }
        entries.flatMap { e -> e.definition.tasks.map { e to it } }.forEach { (entry, task) ->
            val role = roles[task.roleId] ?: error("${entry.definition.id.value}/${task.id.value}: unknown role ${task.roleId}")
            task.requiredRoleAuthority?.let { authority ->
                assertTrue(authority in role.authorities, "${entry.definition.id.value}/${task.id.value}: ${role.name} cannot $authority")
            }
        }
    }

    @Test
    fun aHiveThatChangesCodeApprovesTheChangeAndStopsForAReleaseDecision() {
        entries.filter { it.kind == CodingSuiteEntry.Kind.Delivers }.forEach { entry ->
            val tasks = entry.definition.tasks
            val id = entry.definition.id.value
            assertTrue(tasks.any { it.executor is TaskExecutor.HumanApproval }, "$id ends at a person's approval")
            tasks.filter { it.requiredRoleAuthority == RoleAuthority.Implement || it.requiredRoleAuthority == RoleAuthority.AuthorTests && it.approvalPolicy != ApprovalPolicy.None }
                .forEach { assertEquals(ApprovalPolicy.HumanApproval, it.approvalPolicy, "$id/${it.id.value}") }
            tasks.filter { it.requiredRoleAuthority == RoleAuthority.Implement }
                .forEach { assertEquals(ApprovalPolicy.HumanApproval, it.approvalPolicy, "$id/${it.id.value} is approved before it runs") }
        }
    }

    @Test
    fun aHiveMeantForASchedulePurelyReadsAndReportsAndNeverWaitsOnAPerson() {
        entries.filter { it.schedule != null }.forEach { entry ->
            val id = entry.definition.id.value
            assertEquals(CodingSuiteEntry.Kind.Reports, entry.kind, id)
            entry.definition.tasks.forEach { task ->
                assertTrue(task.executor is TaskExecutor.RoleAgent, "$id/${task.id.value} is an agent step")
                assertEquals(ApprovalPolicy.None, task.approvalPolicy, "$id/${task.id.value} waits on no one")
                assertTrue(
                    task.requiredRoleAuthority != RoleAuthority.Implement && task.requiredRoleAuthority != RoleAuthority.AuthorTests,
                    "$id/${task.id.value} changes nothing",
                )
            }
            assertTrue(entry.schedule!!.objective.isNotBlank(), id)
        }
    }

    @Test
    fun theFailureBranchOfTheIncidentHotfixOnlyRunsWhenVerificationFails() {
        val hotfix = entries.first { it.definition.id.value == "suite-incident-hotfix" }.definition
        val rollback = hotfix.tasks.first { it.id.value == "rollback-plan" }
        assertEquals("verify", (rollback.condition as com.hereliesaz.geministrator.domain.TaskCondition.OnFailure).ofTask.value)
    }
}
