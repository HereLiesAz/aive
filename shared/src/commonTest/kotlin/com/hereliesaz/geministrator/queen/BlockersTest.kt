package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepInstantiation
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
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

    /** A step made from a template whose required input has no value, held for it by the run. */
    private fun heldDefinition(): Pair<com.hereliesaz.geministrator.domain.WorkflowDefinition, com.hereliesaz.geministrator.domain.TaskDefinition> {
        val template = BuiltInTemplates.byId.getValue("sheet-table")
        val task = StepInstantiation.task(template, template.base(), TaskDefinitionId("held"), "The table", emptyMap())
        return qDefinition(qTask("a"), task) to task
    }

    private fun heldRun(status: TaskRunStatus = TaskRunStatus.Blocked, held: Boolean = true) = qRun(
        qTaskRun("a", TaskRunStatus.Running),
        qTaskRun("held", status).copy(
            blockingReason = if (held) BlockingReason(WorkflowRunFactory.CONTRACT_INPUT_MISSING, "needs its input") else null,
        ),
    )

    @Test
    fun theQueenAddsABlockerForAStepHeldForAMissingInputAndItIsResolvedWhenTheInputIsThere() {
        val (def, _) = heldDefinition()
        val added = Blockers.live(emptyList(), def, heldRun(), emptyList())
        val blocker = added.single()
        assertEquals(BlockerOrigin.Queen, blocker.origin)
        assertEquals("held", blocker.forStep)
        assertEquals(BlockerState.Open, blocker.state)
        // Said again and again, it is added once.
        assertEquals(added, Blockers.live(added, def, heldRun(), emptyList()))
        // Once the step is no longer held for the input, it is resolved by what the creature was given.
        val fed = Blockers.live(added, def, heldRun(status = TaskRunStatus.Running, held = false), emptyList())
        assertEquals(BlockerState.Done, fed.single().state)
        // A dropped one is not added again.
        val dropped = Blockers.setState(added, blocker.id, BlockerState.Dropped)
        assertEquals(dropped, Blockers.live(dropped, def, heldRun(), emptyList()))
    }

    @Test
    fun aStepHeldForAnInputIsSaidInTheQueensOwnWordsAndLinksToItsNeedsTab() {
        val (def, _) = heldDefinition()
        val added = Blockers.live(emptyList(), def, heldRun(), emptyList())
        val note = blockerNotes("r1", def, heldRun(), added).single()
        assertEquals(QueenVoice.BlockerAddedQueen, note.voice)
        assertEquals(QueenLink.Creature("held", CreatureTab.Needs), note.link)
        assertEquals(QueenQuoteLabel.StillMissing, note.quotes.single().label)
    }

    private fun nannyFinding(subject: String, observation: String) =
        NannyFinding("f1", "rep", subject, observation, taskId = null, action = null, solutions = emptyList())

    @Test
    fun aNannyFindingThatNamesSomethingMissingBecomesABlockerOnce() {
        val findings = listOf(
            nannyFinding("Missing: the pricing page", "The pricing page does not exist yet"),
            nannyFinding("a", "Something else the Nanny saw"),
        )
        val added = Blockers.fromNannyFindings(emptyList(), findings)
        assertEquals(listOf("The pricing page does not exist yet"), added.map { it.sentence })
        assertEquals(BlockerOrigin.Nanny, added.single().origin)
        assertEquals(added, Blockers.fromNannyFindings(added, findings))
        val note = blockerNotes("r1", definition, qRun(qTaskRun("a", TaskRunStatus.Running)), added).single()
        assertEquals(QueenVoice.BlockerAddedNanny, note.voice)
        assertEquals(QueenRelay.Nanny, note.voice.relay)
    }

    @Test
    fun aCreaturesCompletedOutputResolvesABlockerWithTheSameStartingTemplateAndIsSaidAsMade() {
        val made = Blockers.assignStep(definition, Blocker("b0", "A comparison table", "sheet-table"))
        val def = assertIs<BlockerAssignment.Assigned>(made).definition
        val stepId = def.tasks.last().id.value
        val waiting = listOf(Blocker("b1", "Another table for the plans", "sheet-table"))
        val artifact = ArtifactRef(
            id = ArtifactId("x"), kind = ArtifactKind.Design, taskRunId = TaskRunId("r-$stepId"),
            label = "table", textContent = "rows", createdAtEpochMillis = 1,
        )
        val without = qRun(qTaskRun("a", TaskRunStatus.Running), qTaskRun(stepId, TaskRunStatus.Completed))
        assertEquals(BlockerState.Open, Blockers.resolved(waiting, def, without).single().state)
        val withOutput = qRun(qTaskRun("a", TaskRunStatus.Running), qTaskRun(stepId, TaskRunStatus.Completed, artifacts = listOf(artifact)))
        val resolved = Blockers.resolved(waiting, def, withOutput)
        assertEquals(BlockerState.Done, resolved.single().state)
        val note = blockerNotes("r1", def, withOutput, waiting).single()
        assertEquals(QueenVoice.BlockerMade, note.voice)
        assertEquals(QueenQuoteLabel.NowMade, note.quotes.single().label)
        assertTrue(Blockers.unmet(resolved).isEmpty())
    }

    @Test
    fun blockersSurviveARestartAndAnUnreadableValueReadsAsNone() {
        val book = mapOf(
            "run-1" to listOf(
                Blocker("b1", "A table", "sheet-table", state = BlockerState.InProgress, origin = BlockerOrigin.Nanny, forStep = "x"),
                Blocker("b2", "A page", stepId = "a", state = BlockerState.Done),
            ),
            "run-2" to emptyList<Blocker>(),
        )
        assertEquals(book, BlockerBook.decode(BlockerBook.encode(book)))
        val settings = InMemorySettings()
        BlockerStore(settings).save(book)
        assertEquals(book, BlockerStore(settings).load())
        assertEquals(emptyMap<String, List<Blocker>>(), BlockerBook.decode("{not json"))
        assertEquals(emptyMap<String, List<Blocker>>(), BlockerBook.decode(null))
        assertEquals(emptyMap<String, List<Blocker>>(), BlockerStore(InMemorySettings()).load())
    }
}
