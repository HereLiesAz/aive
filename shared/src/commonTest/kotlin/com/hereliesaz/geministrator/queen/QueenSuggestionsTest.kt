package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowKeeperPause
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.SpendCaps
import com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueenSuggestionsTest {
    private val definition = qDefinition(
        qTask("a"),
        qTask("b").copy(dependsOn = setOf(TaskDefinitionId("a"))),
        qTask("c").copy(dependsOn = setOf(TaskDefinitionId("b"))),
    )

    /** The same swarm with step c behind a human approval, so a new job is allowed even for a role that writes. */
    private val gated = definition.copy(
        tasks = definition.tasks.map { if (it.id.value == "c") it.copy(approvalPolicy = ApprovalPolicy.HumanApproval) else it },
    )

    /** Step b builds on the finished step a and has failed twice. */
    private val failing = qRun(
        qTaskRun("a", TaskRunStatus.Completed),
        qTaskRun("b", TaskRunStatus.Running, attempt = 3),
        qTaskRun("c", TaskRunStatus.Blocked),
    )

    private fun candidates(run: com.hereliesaz.geministrator.domain.WorkflowRun = failing, caps: SpendCaps = SpendCaps(), roles: List<com.hereliesaz.geministrator.domain.RoleDefinition> = listOf(engineerRole)) =
        queenCandidates(definition, run, queenSignals(definition, run, roles), caps)

    private fun made(
        existing: List<ImprovementProposal> = emptyList(),
        level: QueenLevel = QueenLevel.Quiet,
        now: Long = 10_000_000L,
        run: com.hereliesaz.geministrator.domain.WorkflowRun = failing,
    ) = queenNewProposals(definition, run, candidates(run), existing, level, now)

    @Test
    fun twoFailuresOfAStepThatBuildsOnFinishedWorkOfferARedoFromTheFinishedStep() {
        val found = candidates().single()
        assertEquals(QueenAction.RedoFromStep, found.action)
        assertEquals("a", found.subject)
        assertEquals(listOf("1", "Task a", "2", "2"), found.args)
    }

    @Test
    fun healthyRunOffersNothing() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Running), qTaskRun("c", TaskRunStatus.Blocked))
        assertTrue(candidates(run).isEmpty())
    }

    @Test
    fun identicalStateGivesIdenticalProposals() {
        assertEquals(made(), made())
    }

    @Test
    fun providerFailoverOffersALinkToTheMindNotAnApply() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, attempt = 2, failedProviders = listOf("claude")))
        val c = candidates(run).single()
        assertEquals(QueenAction.SwapSource, c.action)
        assertFalse(c.action.appliesHere)
        val note = queenSuggestionNotes(queenNewProposals(definition, run, listOf(c), emptyList(), QueenLevel.Quiet, 1L), run).single()
        assertEquals(QueenLink.Creature("a", CreatureTab.Mind), note.link)
    }

    @Test
    fun roleAtEightyPercentOfBudgetOffersARaisedBudgetOnTheMind() {
        val role = engineerRole.copy(tokenBudget = 1000)
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, tokens = 850), qTaskRun("b", TaskRunStatus.Ready))
        val c = candidates(run, roles = listOf(role)).single()
        assertEquals(QueenAction.RaiseBudget, c.action)
        assertEquals(listOf(role.name, "85"), c.args)
    }

    @Test
    fun costNearTheRunAllowanceWithManyStepsLeftOffersTheAllowance() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Completed, cost = 0.8), qTaskRun("b", TaskRunStatus.Running), qTaskRun("c", TaskRunStatus.Blocked))
        val c = candidates(run, SpendCaps(runCostCapUsd = 1.0)).single()
        assertEquals(QueenAction.RaiseAllowance, c.action)
        assertEquals(QUEEN_RUN_SUBJECT, c.subject)
        assertEquals("80", c.args[0])
        val note = queenSuggestionNotes(queenNewProposals(definition, run, listOf(c), emptyList(), QueenLevel.Quiet, 1L), run).single()
        assertEquals(QueenLink.Limits, note.link)
    }

    @Test
    fun costPaceNeedsAnAllowanceAndManyStepsLeft() {
        val spent = qRun(qTaskRun("a", TaskRunStatus.Completed, cost = 0.8), qTaskRun("b", TaskRunStatus.Completed), qTaskRun("c", TaskRunStatus.Running))
        assertTrue(candidates(spent, SpendCaps(runCostCapUsd = 1.0)).isEmpty(), "only one step of three is left")
        val noCap = qRun(qTaskRun("a", TaskRunStatus.Completed, cost = 0.8), qTaskRun("b", TaskRunStatus.Running), qTaskRun("c", TaskRunStatus.Blocked))
        assertTrue(candidates(noCap).isEmpty())
    }

    @Test
    fun oneOpenAtQuietTwoAtHelpful() {
        val first = made().single()
        assertTrue(made(listOf(first), QueenLevel.Quiet, now = first.createdAtEpochMillis + QueenPacing.COOLDOWN_MILLIS).isEmpty())
        val run = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Running, attempt = 4, failedProviders = listOf("x")), qTaskRun("c", TaskRunStatus.Blocked))
        val both = queenCandidates(definition, run, queenSignals(definition, run, emptyList()), SpendCaps())
        assertTrue(both.size >= 2)
        val one = queenNewProposals(definition, run, both, emptyList(), QueenLevel.Helpful, 1_000_000L)
        val second = queenNewProposals(definition, run, both, one, QueenLevel.Helpful, 1_000_000L + QueenPacing.COOLDOWN_MILLIS)
        assertEquals(1, second.size)
        assertTrue(queenNewProposals(definition, run, both, one + second, QueenLevel.Helpful, 9_000_000L).isEmpty(), "two open is the most at Helpful")
    }

    @Test
    fun fifteenMinutesBetweenNewOnes() {
        val first = made().single()
        val run = qRun(qTaskRun("a", TaskRunStatus.Running, attempt = 2, failedProviders = listOf("x")))
        val c = queenCandidates(definition, run, queenSignals(definition, run, emptyList()), SpendCaps())
        val mine = first.copy(cycleRunId = run.id.value, status = ProposalStatus.Adopted)
        assertTrue(queenNewProposals(definition, run, c, listOf(mine), QueenLevel.Quiet, mine.createdAtEpochMillis + 1).isEmpty())
        assertEquals(1, queenNewProposals(definition, run, c, listOf(mine), QueenLevel.Quiet, mine.createdAtEpochMillis + QueenPacing.COOLDOWN_MILLIS).size)
    }

    @Test
    fun atMostFivePerRunWhateverTheirFate() {
        val run = failing
        val rows = (1..5).map { n ->
            made().single().copy(id = "old$n", status = ProposalStatus.Adopted, createdAtEpochMillis = n.toLong())
        }
        assertTrue(queenNewProposals(definition, run, candidates(), rows, QueenLevel.Helpful, 90_000_000L).isEmpty())
    }

    @Test
    fun theSameSignalOnTheSameStepIsNotOfferedTwice() {
        val first = made().single()
        val adopted = first.copy(status = ProposalStatus.Adopted)
        assertTrue(made(listOf(adopted), now = first.createdAtEpochMillis + 10 * QueenPacing.COOLDOWN_MILLIS).isEmpty())
    }

    @Test
    fun dismissedIsNotOfferedAgainForTheSameStepAndActionInAnyRunForTwoWeeks() {
        val first = made().single()
        val dismissed = first.copy(cycleRunId = "earlier-run", id = "earlier", status = ProposalStatus.Dismissed, decidedAtEpochMillis = 5_000_000L)
        assertTrue(made(listOf(dismissed), now = 5_000_000L + QueenPacing.DISMISSED_MEMORY_MILLIS - 1).isEmpty())
        assertEquals(1, made(listOf(dismissed), now = 5_000_000L + QueenPacing.DISMISSED_MEMORY_MILLIS + 1).size)
    }

    @Test
    fun dismissalIsPerStepAndAction() {
        val first = made().single()
        val dismissedOtherStep = first.copy(cycleRunId = "earlier-run", id = "earlier", target = "z", status = ProposalStatus.Dismissed, decidedAtEpochMillis = 5_000_000L)
        assertEquals(1, made(listOf(dismissedOtherStep), now = 6_000_000L).size)
    }

    @Test
    fun aSwarmWithANannyStillGetsTheQueensOwnRedo() {
        val withNanny = qDefinition(
            qTask("a"),
            qTask("b").copy(dependsOn = setOf(TaskDefinitionId("a"))),
            qTask("nanny", com.hereliesaz.geministrator.domain.HallMonitorRole.id),
        )
        val run = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Running, attempt = 3), qTaskRun("nanny", TaskRunStatus.Running))
        val found = queenCandidates(withNanny, run, queenSignals(withNanny, run, emptyList()), SpendCaps())
        assertTrue(found.any { it.action == QueenAction.RedoFromStep })
    }

    @Test
    fun aFinishedRunGetsNothing() {
        assertTrue(candidates(failing.copy(status = WorkflowRunStatus.Completed)).isEmpty())
    }

    @Test
    fun supersededOnceTheStepStartsOrTheSignalPasses() {
        val reword = made().single().copy(action = QueenAction.RewordStep.name, target = "c", newText = "New job")
        val unstarted = failing
        assertFalse(queenIsMoot(reword, definition, unstarted, candidates(unstarted)))
        val started = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Running), qTaskRun("c", TaskRunStatus.Running))
        assertTrue(queenIsMoot(reword, definition, started, candidates(started)))
        val redo = made().single()
        assertFalse(queenIsMoot(redo, definition, failing, candidates(failing)))
        val recovered = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Running, attempt = 1), qTaskRun("c", TaskRunStatus.Blocked))
        assertTrue(queenIsMoot(redo, definition, recovered, candidates(recovered)))
        assertTrue(queenIsMoot(redo, definition, failing.copy(status = WorkflowRunStatus.Failed), emptyList()))
    }

    private fun reword(target: String, outside: Boolean = false, text: String = "A narrower job") =
        made().single().copy(action = QueenAction.RewordStep.name, target = target, newText = text, outside = outside)

    @Test
    fun rewordOnlyForUnstartedSteps() {
        assertFalse(queenAcceptRefused(reword("c"), gated, failing, listOf(engineerRole)))
        assertTrue(queenAcceptRefused(reword("b"), gated, failing, listOf(engineerRole)), "b is running")
        assertTrue(queenAcceptRefused(reword("c", text = " "), gated, failing, listOf(engineerRole)), "no wording")
    }

    @Test
    fun outsideFreeTextIsNeverAppliedAndAsksFirstOtherwise() {
        assertTrue(queenAcceptRefused(reword("c", outside = true), gated, failing, listOf(engineerRole)))
        assertTrue(queenSuggestionConfirms(QueenAction.RedoFromStep, outside = false))
        assertTrue(queenSuggestionConfirms(QueenAction.RewordStep, outside = true))
        assertFalse(queenSuggestionConfirms(QueenAction.RewordStep, outside = false))
        assertFalse(queenSuggestionConfirms(QueenAction.Rest, outside = false))
    }

    @Test
    fun rewordRefusedForMutatingStepWithoutApprovalAllowedBehindOne() {
        // The engineer role can write the repository, so its step is a mutating one.
        assertTrue(mutatesWithoutApproval(definition, definition.tasks[2], listOf(engineerRole)))
        assertFalse(mutatesWithoutApproval(gated, gated.tasks[2], listOf(engineerRole)))
        assertTrue(queenAcceptRefused(reword("c"), definition, failing, listOf(engineerRole)))
        assertFalse(queenAcceptRefused(reword("c"), gated, failing, listOf(engineerRole)))
    }

    @Test
    fun shapeChangesAreNeverOnTheMenu() {
        // Everything she can offer is one of these; no action removes or loosens an approval, skips a step or moves work to a writing Source.
        assertEquals(
            setOf("RewordStep", "RedoFromStep", "SwapSource", "RaiseBudget", "RaiseAllowance", "Rest"),
            QueenAction.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun linkOnlyActionsAreNeverApplied() {
        val run = failing
        for (action in listOf(QueenAction.SwapSource, QueenAction.RaiseBudget, QueenAction.RaiseAllowance)) {
            assertTrue(queenAcceptRefused(made().single().copy(action = action.name), definition, run, listOf(engineerRole)))
        }
    }

    @Test
    fun restIsRefusedWhenAlreadyResting() {
        val rest = made().single().copy(action = QueenAction.Rest.name, target = QUEEN_RUN_SUBJECT)
        assertFalse(queenAcceptRefused(rest, definition, failing, emptyList()))
        assertTrue(queenAcceptRefused(rest, definition, failing.copy(keeperPause = WorkflowKeeperPause(pausedAtEpochMillis = 1L)), emptyList()))
    }

    @Test
    fun outsideContentLabelsTheSuggestion() {
        val tainted = failing.copy(objectiveProvenance = ContextProvenance.ExternalContent)
        val proposal = queenNewProposals(definition, tainted, candidates(tainted), emptyList(), QueenLevel.Quiet, 1L).single()
        assertTrue(proposal.outside)
        val note = queenSuggestionNotes(listOf(proposal), tainted).single()
        assertTrue(note.outside)
        assertTrue(note.suggestion!!.confirm, "redo always asks")
    }

    @Test
    fun aSuggestionNoteCountsToTheBadgeAndCarriesItsRow() {
        val proposal = made().single()
        val note = queenSuggestionNotes(listOf(proposal), failing).single()
        assertTrue(note.countsToBadge)
        assertEquals(proposal.id, note.suggestion!!.proposalId)
        assertEquals(QueenVoice.SuggestRedoStep, note.voice)
        assertEquals(QueenLink.Creature("a", CreatureTab.Work), note.link)
    }

    @Test
    fun onlyOpenRowsOfThisRunBecomeNotes() {
        val proposal = made().single()
        assertTrue(queenSuggestionNotes(listOf(proposal.copy(status = ProposalStatus.Dismissed)), failing).isEmpty())
        assertTrue(queenSuggestionNotes(listOf(proposal.copy(cycleRunId = "other")), failing).isEmpty())
        assertTrue(queenSuggestionNotes(listOf(proposal.copy(source = ImprovementSource.Loop)), failing).isEmpty())
    }

    @Test
    fun theWordingOfARewordIsQuotedNotPartOfTheSentence() {
        val note = queenSuggestionNotes(listOf(reword("c", text = "Summarise **the** findings\u0000 at http://x.example")), failing).single()
        val quote = note.quotes.single()
        assertEquals(QueenQuoteLabel.QueenWording, quote.label)
        assertFalse(quote.text.contains("*") || quote.text.contains("http") || quote.text.contains('\u0000'))
    }

    @Test
    fun theUpdateShowsSuggestionsAndDropsHerDuplicateLine() {
        val proposal = made().single()
        val notes = queenSuggestionNotes(listOf(proposal), failing)
        val with = queenUpdate(definition, failing, listOf(engineerRole), QueenLevel.Quiet, suggestions = notes)
        assertTrue(with.any { it.suggestion != null })
        assertTrue(with.none { it.voice == QueenVoice.WatchRepeatedFailure })
        val without = queenUpdate(definition, failing, listOf(engineerRole), QueenLevel.Quiet)
        assertTrue(without.any { it.voice == QueenVoice.WatchRepeatedFailure })
    }
}
