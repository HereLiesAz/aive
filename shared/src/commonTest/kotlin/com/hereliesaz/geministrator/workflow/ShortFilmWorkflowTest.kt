package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.WorkflowLibraryHost
import com.hereliesaz.geministrator.azphalt.SettingsAuthoredWorkflowStore
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ShortFilmWorkflowTest {
    private val hive = ShortFilmHive.definition()

    @Test
    fun theHiveHasItsIdItsTasksInOrderAndItsDependencies() {
        assertEquals("short-film", hive.id.value)
        assertEquals(
            listOf("concept", "storyboard", "style-sheet", "frames", "frame-review", "assemble", "approve-film"),
            hive.tasks.map { it.id.value },
        )
        assertEquals(ShortFilmHive.taskOrder, hive.tasks.map { it.id.value })
        val deps = hive.tasks.associate { it.id.value to it.dependsOn.map(TaskDefinitionId::value).toSet() }
        assertEquals(emptySet(), deps.getValue("concept"))
        assertEquals(setOf("concept"), deps.getValue("storyboard"))
        assertEquals(setOf("concept", "storyboard"), deps.getValue("style-sheet"))
        assertEquals(setOf("storyboard", "style-sheet"), deps.getValue("frames"))
        assertEquals(setOf("storyboard", "style-sheet", "frames"), deps.getValue("frame-review"))
        assertEquals(setOf("storyboard", "frames", "frame-review"), deps.getValue("assemble"))
        assertEquals(setOf("assemble"), deps.getValue("approve-film"))
    }

    @Test
    fun theHiveValidatesAndExpandsLikeTheCodingSuite() {
        assertEquals(emptyList(), WorkflowGraphValidator.validate(hive))
        WorkflowDefinitionExpander.expand(hive, BuiltInRoles.all)
    }

    @Test
    fun theExecutorsAreTheTwoServicesAndAPersonApprovesLast() {
        val byId = hive.tasks.associateBy { it.id.value }
        assertEquals(TaskExecutor.ExternalService(FILM_FRAMES_SERVICE, "frames"), byId.getValue("frames").executor)
        assertEquals(TaskExecutor.ExternalService(FILM_ASSEMBLE_SERVICE, "assemble"), byId.getValue("assemble").executor)
        assertEquals("film-frames", FILM_FRAMES_SERVICE)
        assertEquals("film-assemble", FILM_ASSEMBLE_SERVICE)
        val last = hive.tasks.last()
        assertIs<TaskExecutor.HumanApproval>(last.executor)
        assertEquals(hive.tasks.size - 1, hive.tasks.indexOfFirst { it.executor is TaskExecutor.HumanApproval }, "the only approval is the last step")
        hive.tasks.filter { it.executor is TaskExecutor.ExternalService }.forEach {
            assertEquals(1, it.retryPolicy.maxAttempts, "${it.id.value} never retries by itself: a retry spends images")
            assertEquals(ApprovalPolicy.None, it.approvalPolicy)
        }
    }

    @Test
    fun everyRoleIsABuiltInOneThatHoldsTheAuthorityTheStepExercises() {
        val roles = BuiltInRoles.all.associateBy { it.id }
        hive.tasks.mapNotNull { task -> task.roleId?.let { task to it } }.forEach { (task, roleId) ->
            val role = roles[roleId] ?: error("${task.id.value}: ${roleId.value} is not a built-in role")
            task.requiredRoleAuthority?.let { assertTrue(it in role.authorities, "${task.id.value}: ${role.name} cannot $it") }
        }
        val review = hive.tasks.first { it.id.value == "frame-review" }
        assertEquals(BuiltInRoles.AdversarialReviewer.id, review.roleId, "the frame review is adversarial")
    }

    @Test
    fun theStoryboardStepAsksForTheJsonTheParserReadsAndStatesTheLimits() {
        val objective = hive.tasks.first { it.id.value == "storyboard" }.objective
        listOf("\"shots\"", "durationSeconds", "$FILM_MAX_SHOTS", "$FILM_MAX_TOTAL_SECONDS", "$FILM_MAX_PROMPT_CHARS").forEach {
            assertTrue(it in objective, "the objective states $it")
        }
        val review = hive.tasks.first { it.id.value == "frame-review" }.objective
        assertTrue(FilmFrameReview.REGENERATE_KEY in review, "and the review is told the key to name shots under")
    }

    @Test
    fun theHiveSaysPlainlyItNeedsTheDesktopAndSpendsMoney() {
        val description = requireNotNull(hive.description)
        assertTrue("desktop" in description.lowercase() && "ffmpeg" in description && "OpenAI" in description)
        assertTrue("runs only when you start it" in description)
    }

    @Test
    fun theDefaultFramesObjectiveSendsNoShotBack() {
        val objective = hive.tasks.first { it.id.value == "frames" }.objective
        assertEquals(emptyList(), FilmFrameReview.parse(objective).regenerate, "the objective's own example line is not a request")
    }

    @Test
    fun theFilmIsInTheCreativeSuiteNotTheCodingSuiteAndHasNoSchedule() {
        assertEquals(listOf(hive), CreativeWorkflowSuite.definitions())
        assertTrue(CodingWorkflowSuite.definitions().none { it.id == hive.id })
        assertTrue(CodingWorkflowSuite.all().none { it.schedule != null && it.definition.id == hive.id })
    }

    @Test
    fun installingTheCreativeSuiteAddsTheHiveOnceAsOneYouAuthored() = runTest {
        val persistence = InMemoryWorkflowPersistence()
        val authored = SettingsAuthoredWorkflowStore(InMemorySettings())
        val host = WorkflowLibraryHost(persistence = persistence, storeService = null, authoredStore = authored, launchWorkflow = { _, _, _, _ -> }, onPackagesChanged = {})

        assertEquals(1, host.installCreativeSuite())
        val id = WorkflowDefinitionId("short-film")
        assertTrue(id in authored.all())
        assertEquals(hive, persistence.definitions.get(id))
        assertEquals(0, host.installCreativeSuite(), "a second install adds nothing")

        val edited = hive.copy(name = "My film")
        host.saveAuthored(edited)
        assertEquals(0, host.installCreativeSuite())
        assertEquals("My film", persistence.definitions.get(id)?.name, "the keeper's edits survive")
    }
}
