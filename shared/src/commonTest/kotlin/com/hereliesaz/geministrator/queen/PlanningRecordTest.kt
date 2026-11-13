package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.orchestration.PlanSuggestion
import com.hereliesaz.geministrator.orchestration.PlanSuggestionKind
import com.hereliesaz.geministrator.providers.ModelPrice
import com.hereliesaz.geministrator.providers.ModelPriceTable
import com.hereliesaz.geministrator.workflow.HourlySpend
import com.hereliesaz.geministrator.workflow.SpendCaps
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The price her gate reads, the room a creature waits for, the planning record and what a skipped reviewer leaves missing. */
class PlanningRecordTest {
    private val table = ModelPriceTable(
        mapOf(
            ModelPriceTable.key("gemini", "flash") to ModelPrice(1.0, 4.0),
            ModelPriceTable.key("openai", "big") to ModelPrice(2.0, 8.0),
        ),
    )
    private val none = HourlySpend(0.0, false)

    @Test
    fun nothingListedIsUnknownAndAKeylessSourceIsFree() {
        assertEquals(PriceStatus.Unknown, priceRead(emptyList(), table, 1_000, 100).status)
        val free = priceRead(listOf(ModelUse("ovh", null, billsPerToken = false)), table, 1_000, 100)
        assertEquals(PriceStatus.Free, free.status)
        assertEquals(0.0, free.estimateUsd)
    }

    @Test
    fun aBilledModelWithNoPriceIsUnknownEvenBesideAFreeOne() {
        val uses = listOf(ModelUse("ovh", null, false), ModelUse("gemini", "unlisted", true))
        val read = priceRead(uses, table, 1_000, 100)
        assertEquals(PriceStatus.Unknown, read.status)
        assertNull(read.estimateUsd)
    }

    @Test
    fun pricedTakesTheDearestSourceSheMayMoveTo() {
        val read = priceRead(listOf(ModelUse("gemini", "flash", true), ModelUse("openai", "big", true)), table, 1_000, 100)
        assertEquals(PriceStatus.Priced, read.status)
        assertEquals(0.0028, read.estimateUsd!!, 1e-9)
    }

    @Test
    fun underADollarCapSheAsksOnlyWhenTheModelIsPricedAndWithinIt() {
        val caps = SpendCaps(runCostCapUsd = 1.0)
        fun may(uses: List<ModelUse>): Boolean {
            val read = priceRead(uses, table, PlanningLimits.QUEEN_INPUT_TOKENS.toLong(), PlanningLimits.QUEEN_OUTPUT_TOKENS.toLong())
            return queenMayCall(QueenCallKind.Ruling, QueenLevel.Quiet, read.status, read.estimateUsd, caps, none, 0, null, 0, false, false, false)
        }
        assertTrue(may(listOf(ModelUse("gemini", "flash", true))))
        assertFalse(may(listOf(ModelUse("gemini", "unlisted", true))))
        assertFalse(may(emptyList()))
        val hourly = SpendCaps(hourlySpendCapUsd = 0.001)
        val read = priceRead(listOf(ModelUse("gemini", "flash", true)), table, 1_500, 200)
        assertFalse(queenMayCall(QueenCallKind.Ruling, QueenLevel.Quiet, read.status, read.estimateUsd, hourly, none, 0, null, 0, false, false, false))
    }

    @Test
    fun aCreatureIsWaitingWhenAReadyStepHasNoRoomOnItsSource() {
        val waiting = qRun(qTaskRun("a", TaskRunStatus.Ready), qTaskRun("b", TaskRunStatus.Completed))
        assertTrue(creatureWaitingForRoom(waiting, setOf("ovh")))
        assertFalse(creatureWaitingForRoom(waiting, emptySet()))
        assertFalse(creatureWaitingForRoom(qRun(qTaskRun("a", TaskRunStatus.Running)), setOf("ovh")))
    }

    @Test
    fun aSkippedReviewerLeavesAMissingThingWithWhereToFixIt() {
        assertNull(missingForSkippedReviewer(null, 2))
        assertNull(missingForSkippedReviewer(GateReason.CallLimit, 2))
        assertEquals(MissingKind.ReviewerSkippedNoPrice, missingForSkippedReviewer(GateReason.UnpricedUnderCap, 2)?.kind)
        assertEquals(MissingKind.ReviewerSkippedNoPrice, missingForSkippedReviewer(GateReason.UnpricedUsage, 2)?.kind)
        assertEquals(MissingKind.ReviewerSkippedOverCap, missingForSkippedReviewer(GateReason.OverHourlyCap, 2)?.kind)
        assertEquals(MissingKind.ReviewerSkippedNoRoom, missingForSkippedReviewer(GateReason.NoRoom, 2)?.kind)
        assertEquals(MissingKind.ReviewerSkippedNoSecondSource, missingForSkippedReviewer(GateReason.NoRoom, 1)?.kind)
    }

    @Test
    fun theNotesLeadToTheCapsOrToConnections() {
        fun note(reason: GateReason, sources: Int) = missingNotes("run-1", listOfNotNull(missingForSkippedReviewer(reason, sources)), QueenLevel.Quiet).single()
        assertEquals(QueenLink.Limits, note(GateReason.UnpricedUnderCap, 2).link)
        assertEquals(QueenVoice.MissingReviewerNoPrice, note(GateReason.UnpricedUnderCap, 2).voice)
        assertEquals(QueenLink.Limits, note(GateReason.OverHourlyCap, 2).link)
        assertEquals(QueenLink.Connections, note(GateReason.NoRoom, 2).link)
        assertEquals(QueenVoice.MissingReviewerNoSecondSource, note(GateReason.NoRoom, 1).voice)
        assertEquals(note(GateReason.NoRoom, 2), note(GateReason.NoRoom, 2))
    }

    @Test
    fun theStoryLineSaysWhoPlannedWhoReviewedAndWhatWasChecked() {
        val reviewed = PlanningRecord("Planner", "gemini", "Reviewer", "openai", rounds = 2, checkedByRules = 4, metByRules = 4, suggestions = 1)
        val line = reviewed.storyLine()
        assertTrue("Planner drafted the plan on" in line && "Reviewer reviewed it on" in line && "in 2 rounds" in line, line)
        assertTrue("the rules met 4 of 4" in line && "1 idea kept for next time" in line, line)
        val skipped = PlanningRecord("Planner", null, "Reviewer", null, rounds = 1, skipped = GateReason.NoRoom, checkedByRules = 4, metByRules = 4)
        val skippedLine = skipped.storyLine()
        assertTrue("a Source the app chose" in skippedLine && "was not asked (no room on the Source)" in skippedLine, skippedLine)
    }

    private fun idea(kind: PlanSuggestionKind, text: String = "Add a test") = PlanSuggestion(kind, text, "It catches a regression", "a")

    @Test
    fun ideasBecomeKeptRowsForTheNextRunAndNeverAnEdit() {
        val rows = planSuggestionRows("run-1", "workflow-1", listOf(idea(PlanSuggestionKind.AddCheck)), emptyList(), 100L, objectiveOutside = false)
        val row = rows.single()
        assertEquals(ImprovementSource.QueenKept, row.source)
        assertEquals(ProposalStatus.Proposed, row.status)
        assertEquals("", row.newText)
        assertEquals("run-1", row.cycleRunId)
        assertEquals("workflow-1", row.sourceRef)
        assertEquals("a", row.target)
        assertFalse(row.outside)
    }

    @Test
    fun theSameIdeaIsWrittenOnceAndNotAgain() {
        val twice = planSuggestionRows("run-1", "w", listOf(idea(PlanSuggestionKind.AddCheck), idea(PlanSuggestionKind.AddCheck)), emptyList(), 1L, false)
        assertEquals(1, twice.size)
        assertTrue(planSuggestionRows("run-1", "w", listOf(idea(PlanSuggestionKind.AddCheck)), twice, 2L, false).isEmpty())
        assertEquals(1, planSuggestionRows("run-1", "w", listOf(idea(PlanSuggestionKind.SplitStep)), twice, 2L, false).size)
    }

    @Test
    fun aGoalFromOutsideKeepsNoFreeTextIdeaAndMarksTheRest() {
        val rows = planSuggestionRows(
            "run-1", "w", listOf(idea(PlanSuggestionKind.Other, "Do something"), idea(PlanSuggestionKind.ConnectSource, "Connect a Source")), emptyList(), 1L, objectiveOutside = true,
        )
        assertEquals(1, rows.size)
        assertTrue(rows.single().outside)
    }

    @Test
    fun anIdeaWithMarkupOrALinkIsMadeSafeBeforeItIsKept() {
        val row = planSuggestionRows("r", "w", listOf(PlanSuggestion(PlanSuggestionKind.AddCheck, "See https://example.com <b>now</b>", "why", null)), emptyList(), 1L, false).single()
        assertFalse("http" in row.summary || "<" in row.summary)
        assertEquals("AddCheck", row.target)
    }
}
