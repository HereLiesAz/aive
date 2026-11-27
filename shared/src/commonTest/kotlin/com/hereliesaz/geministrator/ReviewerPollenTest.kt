package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinition
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reviewer carries the genes of what it has reviewed; its nth review's child is born to all of them. */
class ReviewerPollenTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val writer = engineer.copy(id = RoleDefinitionId("writer"), name = "Technical Writer", instructions = "Write the docs.")
    private val roles = listOf(engineer, qa, writer)
    private val workflow = WorkflowDefinitionId("pollen")

    private fun task(id: String, role: RoleDefinition, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id), name = id, objective = "Do $id", roleId = role.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(), executor = TaskExecutor.RoleAgent(role.id),
    )

    // build -> review, docs -> docreview: one reviewer (QA) visits an engineer, then a writer.
    private val definition = WorkflowDefinition(
        id = workflow, name = "Pollen",
        tasks = listOf(task("build", engineer), task("review", qa, "build"), task("docs", writer), task("docreview", qa, "docs")),
    )

    private fun run(id: String, verdict: String? = "VERDICT: approve", workflowId: WorkflowDefinitionId = workflow): WorkflowRun {
        fun runOf(taskId: String): Pair<TaskDefinitionId, TaskRun> {
            val t = definition.tasks.first { it.id.value == taskId }
            val review = taskId.endsWith("review")
            return t.id to TaskRun(
                id = TaskRunId("$id-$taskId"), taskDefinitionId = t.id, status = TaskRunStatus.Completed,
                assignedRoleId = t.roleId, assignedProviderId = AgentProviderId("p"),
                providerRunId = ProviderRunId("$id-s-$taskId"),
                artifacts = listOf(ArtifactRef(ArtifactId("$id-a-$taskId"),
                    if (review) ArtifactKind.Verification else ArtifactKind.Research, TaskRunId("$id-$taskId"), taskId,
                    textContent = if (review) (verdict ?: "Looks fine") else "work", createdAtEpochMillis = 1L)),
            )
        }
        return WorkflowRun(
            id = WorkflowRunId(id), projectId = ProjectId("p"), workflowDefinitionId = workflowId, objective = "o",
            status = WorkflowRunStatus.Completed, taskRuns = definition.tasks.associate { runOf(it.id.value) },
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L, roleSnapshot = roles, definitionSnapshot = definition,
        )
    }

    private fun reports(run: WorkflowRun) = listOf("review" to ("build" to 2L), "docreview" to ("docs" to 3L)).map { (consumer, source) ->
        val task = run.taskRuns.getValue(TaskDefinitionId(consumer))
        ProviderUsageRecorded(run.id, task.taskDefinitionId, AgentProviderId("p"), requireNotNull(task.providerRunId),
            occurredAtEpochMillis = source.second, deliveredArtifactIds = setOf("${run.id.value}-a-${source.first}"))
    }

    @Test
    fun eachReviewIsAVisitAndTheNthChildCarriesEveryProducerSoFar() {
        val r = run("one")
        assertEquals(listOf(engineer.id, writer.id), pollenVisits(r, reports(r)).map { it.producer })
        val history = PollenHistory(workflow).advance(r, reports(r))
        assertEquals(listOf(listOf(qa.id, engineer.id), listOf(qa.id, engineer.id, writer.id)), history.pollinations.map { it.parents })
        assertEquals(listOf(1, 2), history.pollinations.map { it.index })
        // Across runs the tally continues: the third review is the engineer again, so the parents do not repeat.
        val next = history.advance(run("two"), reports(run("two")))
        assertEquals(listOf(3, 4), next.pollinations.drop(2).map { it.index })
        assertEquals(listOf(qa.id, writer.id, engineer.id), next.pollinations[2].parents) // engineer reviewed again: newest
        assertEquals(next, next.advance(run("two"), reports(run("two"))))
        assertEquals(history, history.advance(run("other", workflowId = WorkflowDefinitionId("else")), reports(run("other"))))
    }

    @Test
    fun aReviewWithNoStatedVerdictIsNotAVisit() {
        val r = run("quiet", verdict = null)
        assertEquals(emptyList(), pollenVisits(r, reports(r)))
        assertEquals(emptyList(), PollenHistory(workflow).advance(r, reports(r)).pollinations)
        assertEquals(emptyList(), pollenVisits(run("none"), emptyList()))
    }

    @Test
    fun theChildIsBornToTheReviewerAndItsPollenAndNeverGainsAuthority() {
        val byId = roles.associateBy { it.id }
        val history = PollenHistory(workflow).advance(run("one"), reports(run("one")))
        val child = assertNotNull(pollinatedChild(history.pollinations.last(), byId))
        assertEquals(listOf(qa.id, engineer.id, writer.id), child.parents)
        assertEquals(false, child.enabled)
        assertEquals("one", child.hatchedFromRunId)
        assertTrue(child.name.endsWith("· pollen 2"))
        assertTrue(child.authorities.all { it in roles.flatMap { r -> r.authorities } })
        assertEquals(child, pollinatedChild(history.pollinations.last(), byId)) // deterministic
        // Over many children some follow the reviewer's line and some a producer's, so the reviewer can improve too.
        val leads = (1..40).mapNotNull {
            pollinatedChild(Pollination(workflow, qa.id, it, listOf(qa.id, engineer.id, writer.id), WorkflowRunId("r$it")), byId)
        }.map { isReviewerRoleName(it.name) }
        assertTrue(true in leads && false in leads)
    }

    @Test
    fun parentsThatCannotBeJoinedBearNoChild() {
        val byId = roles.associateBy { it.id }
        val p = Pollination(workflow, qa.id, 1, listOf(qa.id, engineer.id), WorkflowRunId("r"))
        assertNull(pollinatedChild(p, byId - engineer.id))
        val narrow = byId + (qa.id to qa.copy(toolCeiling = listOf("read"))) + (engineer.id to engineer.copy(toolCeiling = listOf("write")))
        assertNull(pollinatedChild(p, narrow))
    }
}
