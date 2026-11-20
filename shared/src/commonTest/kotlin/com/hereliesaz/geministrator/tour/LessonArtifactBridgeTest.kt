package com.hereliesaz.geministrator.tour

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LessonArtifactBridgeTest {
    private val rows = listOf(
        TargetRow("MESSAGES_BUTTON", "Messages", emptySet(), true, setOf(ExpectKind.Tapped)),
        TargetRow("KEEPERS_ROOM_GEAR", "Keeper's room", emptySet(), false, setOf(ExpectKind.Tapped)),
    )

    /** A fixture payload for tests only; these words are never shipped. */
    private fun payload(
        target: String = "MESSAGES_BUTTON",
        checkIn: String = "Are you with me so far?",
        extra: String = "",
    ) = """{"target":"$target","sentence":"Do the one thing at the top.","expect":{"type":"tapped","target":"$target"},"explain":"Short explanation.","ifMissed":"Look again.","checkIn":"$checkIn","anotherWay":"Another angle.","smaller":["one","two"]$extra}"""

    private fun output(line: String) = "Here is the lesson in words.\n\nMore words.\n$line"

    @Test
    fun theLastPayloadLineIsFoundAndOrdinaryTextHasNone() {
        assertEquals(payload(), LessonArtifactBridge.payloadLine(output(payload())))
        assertNull(LessonArtifactBridge.payloadLine("Just a survey of the material.\n{\"note\":1}"))
        assertEquals(LessonHandIn.None, LessonArtifactBridge.inspect("Just a survey.", rows))
    }

    @Test
    fun aValidPayloadPlaysVoicedByItsCreature() {
        val hand = LessonArtifactBridge.inspect(output(payload()), rows, voice = "Teacher")
        val play = assertIs<LessonHandIn.Play>(hand)
        assertEquals("Teacher", play.payload.voice)
        assertEquals("MESSAGES_BUTTON", play.payload.target)
    }

    @Test
    fun anInvalidPayloadIsNotPlayedAndIsReportedAsFailureReasons() {
        val quiz = LessonArtifactBridge.inspect(output(payload(checkIn = "What does Messages do?")), rows)
        val turned = assertIs<LessonHandIn.Turned>(quiz)
        assertTrue(turned.problems.any { it.startsWith("L11") }, turned.problems.toString())

        val unknownTarget = LessonArtifactBridge.inspect(output(payload(target = "NOPE")), rows)
        assertTrue(assertIs<LessonHandIn.Turned>(unknownTarget).problems.any { it.startsWith("L2") })

        val unknownKey = LessonArtifactBridge.inspect(output(payload(extra = ""","surprise":"x"""")), rows)
        assertTrue(assertIs<LessonHandIn.Turned>(unknownKey).problems.any { it.startsWith("L1") })
    }

    @Test
    fun withNoRegistryRowsNothingIsJudgedAndNothingIsTurnedBack() {
        assertEquals(LessonHandIn.None, LessonArtifactBridge.inspect(output(payload(checkIn = "What is it?")), emptyList()))
    }

    @Test
    fun theCompiledCheckTurnsBackOnlyABadPayload() {
        val registry = LessonArtifactBridge.registry { rows }
        assertTrue(registry.knows(LessonArtifactBridge.VALIDATOR_ID))
        assertEquals(emptyList(), registry.problems(LessonArtifactBridge.VALIDATOR_ID, listOf(artifact("a", "plain text", 1))))
        assertEquals(emptyList(), registry.problems(LessonArtifactBridge.VALIDATOR_ID, listOf(artifact("a", output(payload()), 1))))
        val bad = registry.problems(LessonArtifactBridge.VALIDATOR_ID, listOf(artifact("a", output(payload(checkIn = "Why?")), 1)))
        assertTrue(bad.isNotEmpty())
    }

    @Test
    fun newLessonsTakesEachCompletedCreatureOnceInFinishOrder() {
        val roleId = RoleDefinitionId("teacher")
        val run = run(
            roles = listOf(RoleDefinition(roleId, "Teacher", "d", "i")),
            tasks = listOf(
                taskRun("late", TaskRunStatus.Completed, roleId, output(payload()), createdAt = 20),
                taskRun("early", TaskRunStatus.Completed, roleId, output(payload()), createdAt = 10),
                taskRun("running", TaskRunStatus.Running, roleId, output(payload()), createdAt = 5),
                taskRun("survey", TaskRunStatus.Completed, roleId, "Only prose.", createdAt = 1),
                taskRun("bad", TaskRunStatus.Completed, roleId, output(payload(checkIn = "Why?")), createdAt = 2),
            ),
        )
        val played = mutableSetOf<String>()
        val lessons = LessonArtifactBridge.newLessons(run, rows, played)
        assertEquals(2, lessons.size)
        assertTrue(lessons.all { it.voice == "Teacher" })
        // Each lesson carries the step that wrote it, so the queen can find the step to rebuild.
        assertEquals(setOf("late", "early"), lessons.map { it.stepId }.toSet())
        // Taken once: a second pass over the same run plays nothing again.
        assertEquals(emptyList(), LessonArtifactBridge.newLessons(run, rows, played))
    }

    @Test
    fun newLessonsWaitsWhenTheRegistryIsNotKnownYet() {
        val roleId = RoleDefinitionId("teacher")
        val run = run(emptyList(), listOf(taskRun("a", TaskRunStatus.Completed, roleId, output(payload()), createdAt = 1)))
        val played = mutableSetOf<String>()
        assertEquals(emptyList(), LessonArtifactBridge.newLessons(run, emptyList(), played))
        assertTrue(played.isEmpty())
        assertEquals(1, LessonArtifactBridge.newLessons(run, rows, played).size)
    }

    @Test
    fun onlyATeachingSwarmsNonReviewingCreaturesNameTheLessonCheck() {
        val check = com.hereliesaz.geministrator.domain.VerificationPolicy.Deterministic(LessonArtifactBridge.VALIDATOR_ID)
        val teach = "Teach me how to use the Aive"
        assertEquals(check, com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory.teachingLessonCheck(teach, "teacher"))
        assertEquals(com.hereliesaz.geministrator.domain.VerificationPolicy.None, com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory.teachingLessonCheck(teach, "reviewer"))
        assertEquals(com.hereliesaz.geministrator.domain.VerificationPolicy.None, com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory.teachingLessonCheck("Fix the login bug", "teacher"))
    }

    private fun artifact(task: String, text: String, createdAt: Long) = ArtifactRef(
        id = ArtifactId("art-$task-$createdAt"),
        kind = ArtifactKind.Research,
        taskRunId = TaskRunId("tr-$task"),
        label = "out",
        textContent = text,
        createdAtEpochMillis = createdAt,
    )

    private fun taskRun(task: String, status: TaskRunStatus, role: RoleDefinitionId, text: String, createdAt: Long) = TaskRun(
        id = TaskRunId("tr-$task"),
        taskDefinitionId = TaskDefinitionId(task),
        status = status,
        assignedRoleId = role,
        artifacts = listOf(artifact(task, text, createdAt)),
    )

    private fun run(roles: List<RoleDefinition>, tasks: List<TaskRun>) = WorkflowRun(
        id = WorkflowRunId("run-1"),
        projectId = ProjectId("p"),
        workflowDefinitionId = WorkflowDefinitionId("w"),
        objective = "Teach me how to use the Aive",
        status = WorkflowRunStatus.Running,
        taskRuns = tasks.associateBy { it.taskDefinitionId },
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
        roleSnapshot = roles,
    )
}
