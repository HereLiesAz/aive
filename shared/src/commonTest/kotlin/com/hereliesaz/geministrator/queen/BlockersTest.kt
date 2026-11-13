package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A blocker is something this hive still needs created or obtained: typed data, checked, never a model's opinion. */
class BlockersTest {
    private val definition = qDefinition(qTask("a"))

    private fun plan(vararg blockers: String) =
        OrchestrationPlan(listOf(OrchestrationPlanStep("a", "A", "Do a", "r")), blockers = blockers.toList())

    @Test
    fun theWordsOfAPlanAreReadAndAnythingUnknownIsDropped() {
        assertEquals(Triple("a page", "make-page", "a"), Blockers.parse("a page => make-page @ a"))
        assertEquals(Triple("a page", null, null), Blockers.parse("a page"))
        val found = Blockers.nominated(
            plan("The landing page => design-page @ a", "A table => nope @ zzz", "  ", "The landing page", "Files => prepare-files-for-repository"),
        )
        assertEquals(listOf("The landing page", "A table", "Files"), found.map { it.sentence })
        assertEquals("design-page", found[0].templateId)
        assertEquals("a", found[0].stepId)
        assertNull(found[1].templateId)
        assertNull(found[1].stepId)
        // The queen's own pick may not be a step that changes something outside its output.
        assertNull(found[2].templateId)
        assertEquals(listOf("b1", "b2", "b3"), found.map { it.id })
    }

    @Test
    fun theKeeperMayNameAnyTemplateOfTheCatalogAndTheListIsBounded() {
        val kept = Blockers.add(emptyList(), "", "Remove the old row", "remove-in-connected-source", null, byKeeper = true)
        assertEquals("remove-in-connected-source", kept.single().templateId)
        var many = emptyList<Blocker>()
        repeat(20) { many = Blockers.add(many, "", "Thing $it", null, null, byKeeper = true) }
        assertEquals(Blockers.MAX, many.size)
        assertEquals(many, Blockers.add(many, "", "Another", null, null, byKeeper = true))
    }

    @Test
    fun aBlockerFollowsItsStepAndAMarkHolds() {
        val open = listOf(Blocker("b1", "The page", "make-page", stepId = "a"), Blocker("b2", "Dropped one", state = BlockerState.Dropped))
        val running = qRun(qTaskRun("a", TaskRunStatus.Running))
        assertEquals(BlockerState.InProgress, Blockers.resolved(open, definition, running)[0].state)
        val done = qRun(qTaskRun("a", TaskRunStatus.Completed), status = WorkflowRunStatus.Completed)
        assertEquals(BlockerState.Done, Blockers.resolved(open, definition, done)[0].state)
        val failed = qRun(qTaskRun("a", TaskRunStatus.Failed))
        assertEquals(BlockerState.Open, Blockers.resolved(open, definition, failed)[0].state)
        assertEquals(BlockerState.Dropped, Blockers.resolved(open, definition, done)[1].state)
        assertEquals(listOf("b1"), Blockers.unmet(Blockers.resolved(open, definition, running)).map { it.id })
    }

    @Test
    fun theHiveIsNotReachedWhileABlockerIsOpen() {
        val done = qRun(qTaskRun("a", TaskRunStatus.Completed), status = WorkflowRunStatus.Completed)
        assertTrue(hiveReached(definition, done))
        assertFalse(hiveReached(definition, done, blockers = listOf(Blocker("b1", "A table"))))
        assertTrue(hiveReached(definition, done, blockers = listOf(Blocker("b1", "A table", state = BlockerState.Dropped))))
        assertTrue(hiveReached(definition, done, blockers = listOf(Blocker("b1", "The page", stepId = "a"))))
    }

    @Test
    fun finishedWithABlockerOpenIsSaidAndOpenOnesAreWhatIsMissing() {
        val done = qRun(qTaskRun("a", TaskRunStatus.Completed), status = WorkflowRunStatus.Completed)
        val note = queenUpdate(definition, done, listOf(engineerRole), QueenLevel.Quiet, blockers = listOf(Blocker("b1", "A table"))).single()
        assertEquals(QueenVoice.FinishedBlockersOpen, note.voice)
        assertEquals(listOf("1"), note.args)
        val midRun = queenUpdate(definition, qRun(qTaskRun("a", TaskRunStatus.Running)), listOf(engineerRole), QueenLevel.Quiet, blockers = listOf(Blocker("b1", "A table", stepId = "a")))
        val missing = midRun.single { it.voice == QueenVoice.MissingBlocker }
        assertEquals(QueenRelay.Missing, missing.voice.relay)
        assertEquals(QueenLink.Creature("a", CreatureTab.Work), missing.link)
        assertEquals("A table", missing.quotes.single().text)
        assertEquals(missing.id, queenUpdate(definition, qRun(qTaskRun("a", TaskRunStatus.Running)), listOf(engineerRole), QueenLevel.Quiet, blockers = listOf(Blocker("b1", "A table", stepId = "a"))).single { it.voice == QueenVoice.MissingBlocker }.id)
    }

    @Test
    fun aBlockerWithAStartingTemplateBecomesAStepTheExistingValidationChecks() {
        val blocker = Blocker("b1", "A comparison table of the plans", "sheet-table")
        val assigned = assertIs<BlockerAssignment.Assigned>(Blockers.assignStep(definition, blocker))
        val made = assigned.definition.tasks.last()
        assertEquals(2, assigned.definition.tasks.size)
        assertEquals("sheet-table", made.contract?.template?.id)
        assertEquals(made.id.value, assigned.blocker.stepId)
        assertEquals(BlockerState.InProgress, assigned.blocker.state)
        assertTrue(made.objective.contains("A comparison table of the plans"))
        assertIs<BlockerAssignment.Rejected>(Blockers.assignStep(definition, blocker.copy(templateId = null)))
        assertIs<BlockerAssignment.Rejected>(Blockers.assignStep(definition, blocker.copy(templateId = "nope")))
        assertIs<BlockerAssignment.Rejected>(Blockers.assignStep(definition, blocker.copy(templateId = "prepare-files-for-repository")))
        assertIs<BlockerAssignment.Rejected>(Blockers.assignStep(definition, blocker.copy(stepId = "a")))
    }

    @Test
    fun whatIsStillMissingIsCarriedToTheNextHive() {
        val finished = listOf(
            Blocker("b1", "A table", "sheet-table", stepId = "x", state = BlockerState.InProgress),
            Blocker("b2", "A page", state = BlockerState.Done),
            Blocker("b3", "A diagram", state = BlockerState.Dropped),
        )
        val next = Blockers.carryForward(finished)
        assertEquals(listOf(Blocker("b1", "A table", "sheet-table")), next)
    }
}
