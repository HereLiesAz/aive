package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
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
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wide Inbox page: what it lists, which entry it opens, and MARK ALL READ reaching the badge. */
class InboxPageTest {
    private val architect = BuiltInRoles.Architect
    private val engineer = BuiltInRoles.ImplementationEngineer

    private fun task(id: String, role: com.hereliesaz.geministrator.domain.RoleDefinition) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = "Task $id",
        objective = "Do $id",
        roleId = role.id,
        executor = TaskExecutor.RoleAgent(role.id),
    )

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("w"),
        name = "Hive",
        tasks = listOf(task("plan", architect), task("busy", engineer), task("fail", engineer), task("gate", architect)),
    )

    private fun taskRun(id: String, status: TaskRunStatus, provider: String? = null, attempt: Int = 1) = TaskRun(
        id = TaskRunId("r-$id"),
        taskDefinitionId = TaskDefinitionId(id),
        status = status,
        attempt = attempt,
        assignedRoleId = definition.tasks.first { it.id.value == id }.roleId,
        assignedProviderId = provider?.let(::AgentProviderId),
    )

    private fun presentation(vararg runs: TaskRun) = LiveWorkflowPresentation(
        project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1, updatedAtEpochMillis = 2),
        definition = definition,
        run = WorkflowRun(
            id = WorkflowRunId("r1"),
            projectId = ProjectId("p"),
            workflowDefinitionId = definition.id,
            objective = "Ship",
            status = WorkflowRunStatus.Running,
            taskRuns = runs.associateBy { it.taskDefinitionId },
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        ),
        roles = listOf(architect, engineer),
    )

    @Test
    fun listsEveryWaitInPlanOrderWithItsKindAndRole() {
        val live = presentation(
            taskRun("gate", TaskRunStatus.AwaitingApproval),
            taskRun("fail", TaskRunStatus.Escalated, attempt = 2),
            taskRun("busy", TaskRunStatus.Running),
            taskRun("plan", TaskRunStatus.AwaitingApproval, provider = "claude"),
        )
        val entries = inboxEntries(live)
        assertEquals(listOf("plan", "fail", "gate"), entries.map { it.taskId })
        assertEquals(
            listOf(InboxEntryKind.PlanApproval, InboxEntryKind.Escalation, InboxEntryKind.TaskApproval),
            entries.map { it.kind },
        )
        assertEquals(listOf(architect.name, engineer.name, architect.name), entries.map { it.role })
        assertEquals(2, entries[1].attempt)
        // Each entry is the badge's own item, so marking them read clears the badge.
        assertEquals(inboxItemKeys(live.run).toSet(), entries.map { it.key }.toSet())
    }

    @Test
    fun opensTheRememberedEntryWhileItWaitsElseTheFirst() {
        val entries = inboxEntries(
            presentation(
                taskRun("plan", TaskRunStatus.AwaitingApproval, provider = "claude"),
                taskRun("fail", TaskRunStatus.Escalated),
            ),
        )
        assertEquals("fail", selectedInboxEntry(entries, entries[1].key)?.taskId)
        assertEquals("plan", selectedInboxEntry(entries, "gone")?.taskId)
        assertEquals("plan", selectedInboxEntry(entries, null)?.taskId)
        assertNull(selectedInboxEntry(emptyList(), entries[0].key))
    }

    @Test
    fun markAllReadOnThePageClearsTheHeadersBadge() {
        val settings = MapSettings()
        val badge = InboxReadLog(settings)
        val page = InboxReadLog(settings)
        val keys = inboxItemKeys(
            presentation(
                taskRun("plan", TaskRunStatus.AwaitingApproval, provider = "claude"),
                taskRun("fail", TaskRunStatus.Escalated),
            ).run,
        )
        assertEquals(2, unreadInboxCount(keys, badge.read))
        page.markRead(keys)
        assertEquals(0, unreadInboxCount(keys, badge.read))
        assertTrue(keys.all { it in InboxReadLog(settings).read })
    }
}
