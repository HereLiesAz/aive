package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TEMPLATE_METADATA_KEY
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.queen.qDefinition
import com.hereliesaz.geministrator.queen.qRun
import com.hereliesaz.geministrator.queen.qTask
import com.hereliesaz.geministrator.queen.qTaskRun
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.CheckWorkSelection
import com.hereliesaz.geministrator.template.StepInstantiation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A check of the work wraps an untemplated step's plain text, refuses what cannot be reviewed, and leaves a templated step alone. */
class CheckWorkWrapTest {
    private fun artifact(text: String?, kind: ArtifactKind = ArtifactKind.Research, meta: Map<String, String> = emptyMap()) =
        ArtifactRef(ArtifactId("a"), kind, TaskRunId("r-a"), "x", uri = if (text == null) "file:x" else null, textContent = text, metadata = meta, createdAtEpochMillis = 0)

    private fun check(from: String = "a", requirements: String = "Be brief."): TaskDefinition =
        StepInstantiation.task(
            BuiltInTemplates.CheckWork,
            BuiltInTemplates.CheckWork.base(),
            TaskDefinitionId("b"),
            "Check",
            mapOf("work" to "@$from.out", "requirements" to requirements),
        )

    private fun definition(upstream: TaskDefinition = qTask("a")): WorkflowDefinition = qDefinition(upstream, check())

    private fun run(vararg artifacts: ArtifactRef): WorkflowRun = qRun(
        qTaskRun("a", TaskRunStatus.Completed, artifacts = artifacts.toList()),
        qTaskRun("b", TaskRunStatus.Blocked),
    )

    private fun decide(definition: WorkflowDefinition, run: WorkflowRun) =
        CheckWorkWrap.decide(definition, run, definition.tasks.first { it.id.value == "b" })

    @Test
    fun plainTextOfAnUntemplatedStepGetsTheFourGenericCriteria() {
        val wrap = assertIs<CheckWorkWrapDecision.Wrap>(decide(definition(), run(artifact("Some plain text."))))
        CheckWorkSelection.GENERIC_CRITERIA.forEachIndexed { i, c -> assertTrue("${i + 1}. $c" in wrap.requirements) }
        assertTrue("Also: Be brief." in wrap.requirements)
    }

    @Test
    fun effectfulEmptyAndReviewWorkIsRefusedInPlainWords() {
        val change = artifact("BRANCH: x", ArtifactKind.CodeChange)
        assertIs<CheckWorkWrapDecision.Refuse>(decide(definition(), run(change)))
        val empty = assertIs<CheckWorkWrapDecision.Refuse>(decide(definition(), run(artifact(null))))
        assertEquals("There is no text to check.", empty.reason)
        val review = assertIs<CheckWorkWrapDecision.Refuse>(decide(definition(), run(artifact("VERDICT: approve", ArtifactKind.Verification))))
        assertEquals(CheckWorkSelection.REVIEW_OF_REVIEW, review.reason)
        assertIs<CheckWorkWrapDecision.Refuse>(decide(definition(), run()))
    }

    @Test
    fun aStepWithATemplateOrContractOfItsOwnIsUnchanged() {
        val templatedUpstream = StepInstantiation.task(
            BuiltInTemplates.WriteDocument,
            BuiltInTemplates.WriteDocument.base(),
            TaskDefinitionId("a"),
            "Write",
            mapOf("brief" to "x"),
        )
        assertEquals(CheckWorkWrapDecision.Unchanged, decide(definition(templatedUpstream), run(artifact("text"))))
        val stamped = artifact("text", meta = mapOf(TEMPLATE_METADATA_KEY to "write-document@1"))
        assertEquals(CheckWorkWrapDecision.Unchanged, decide(definition(), run(stamped)))
        // Not a check-work, or the work is not another step's output: nothing is decided here.
        assertEquals(CheckWorkWrapDecision.Unchanged, CheckWorkWrap.decide(definition(), run(artifact("text")), qTask("a")))
        val literal = StepInstantiation.task(
            BuiltInTemplates.CheckWork, BuiltInTemplates.CheckWork.base(), TaskDefinitionId("b"), "Check", mapOf("work" to "typed text", "requirements" to "x"),
        )
        assertEquals(CheckWorkWrapDecision.Unchanged, CheckWorkWrap.decide(qDefinition(qTask("a"), literal), run(artifact("text")), literal))
    }

    @Test
    fun theRefusalHoldsTheStepWithTheReasonAndAPlainOneIsReleased() {
        val held = definition().let { WorkflowRunFactory.refreshReadiness(it, run(artifact("BRANCH: x", ArtifactKind.CodeChange)), 10L) }
        val heldRun = held.taskRuns.getValue(TaskDefinitionId("b"))
        assertEquals(TaskRunStatus.Blocked, heldRun.status)
        assertEquals(CheckWorkWrap.CHECK_WORK_REFUSED, heldRun.blockingReason?.code)
        val freed = definition().let { WorkflowRunFactory.refreshReadiness(it, run(artifact("Plain text.")), 10L) }
        assertEquals(TaskRunStatus.Ready, freed.taskRuns.getValue(TaskDefinitionId("b")).status)
    }
}
