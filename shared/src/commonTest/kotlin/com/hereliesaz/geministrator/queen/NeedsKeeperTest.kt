package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.workflow.HourlySpend
import com.hereliesaz.geministrator.workflow.SpendCaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one place that says when she must hand a decision to the keeper, and her existing paths asking it. */
class NeedsKeeperTest {
    @Test
    fun aLimitAGateAndAPinAreAlwaysTheKeepers() {
        assertEquals(KeeperReason.RaisesALimit, needsKeeper(Situation.RaisesLimit))
        assertEquals(KeeperReason.PassesAGate, needsKeeper(Situation.PassesGate))
        assertEquals(KeeperReason.KeeperPinned, needsKeeper(Situation.ActsOnPin))
        assertEquals(KeeperReason.KeeperPinned, needsKeeper(Situation.Changes(KeeperOwned.Pin)))
    }

    @Test
    fun everythingOnlyTheKeeperMayChangeIsBeyondHerAuthority() {
        KeeperOwned.entries.filter { it != KeeperOwned.Pin }.forEach {
            assertEquals(KeeperReason.BeyondHerAuthority, needsKeeper(Situation.Changes(it)), it.name)
        }
    }

    @Test
    fun anUnknownPriceIsTheKeepersOnlyUnderADollarCap() {
        assertEquals(KeeperReason.SpendsUnpriced, needsKeeper(Situation.Spends(PriceStatus.Unknown, dollarCap = true)))
        assertNull(needsKeeper(Situation.Spends(PriceStatus.Unknown, dollarCap = false)))
        assertNull(needsKeeper(Situation.Spends(PriceStatus.Priced, dollarCap = true)))
        assertNull(needsKeeper(Situation.Spends(PriceStatus.Free, dollarCap = true)))
    }

    @Test
    fun anAdoptionNeedsTheKeepersYesAndADisputeOrRulingOnlyWhenNothingSettledIt() {
        assertEquals(KeeperReason.NeedsApproval, needsKeeper(Situation.Adopts(approved = false)))
        assertNull(needsKeeper(Situation.Adopts(approved = true)))
        assertEquals(KeeperReason.Disputed, needsKeeper(Situation.Disagreement(settled = false)))
        assertNull(needsKeeper(Situation.Disagreement(settled = true)))
        assertEquals(KeeperReason.UnreadableRuling, needsKeeper(Situation.Ruling(readable = false)))
        assertNull(needsKeeper(Situation.Ruling(readable = true)))
    }

    @Test
    fun everyReasonCanBeReachedAndTheAnswerIsTheSameEachTime() {
        val reached = listOf(
            Situation.RaisesLimit, Situation.PassesGate, Situation.ActsOnPin, Situation.Adopts(false), Situation.Changes(KeeperOwned.Role),
            Situation.Disagreement(false), Situation.Ruling(false), Situation.Spends(PriceStatus.Unknown, true),
        ).map { needsKeeper(it) }
        assertEquals(KeeperReason.entries.toSet(), reached.toSet())
        assertEquals(reached, listOf(
            Situation.RaisesLimit, Situation.PassesGate, Situation.ActsOnPin, Situation.Adopts(false), Situation.Changes(KeeperOwned.Role),
            Situation.Disagreement(false), Situation.Ruling(false), Situation.Spends(PriceStatus.Unknown, true),
        ).map { needsKeeper(it) })
    }

    @Test
    fun everySuggestionIsHersToApplyOrKeepOrTheKeepersToChange() {
        QueenAction.entries.forEach { action ->
            assertTrue(action.appliesHere || action.keepsForNextTime || action.keeperSituation() != null, action.name)
            if (action.keeperSituation() != null) assertTrue(!action.appliesHere && !action.keepsForNextTime, action.name)
        }
        assertEquals(KeeperReason.RaisesALimit, needsKeeper(QueenAction.RaiseAllowance.keeperSituation()!!))
        assertEquals(KeeperReason.RaisesALimit, needsKeeper(QueenAction.RaiseBudget.keeperSituation()!!))
        assertEquals(KeeperReason.BeyondHerAuthority, needsKeeper(QueenAction.SwapSource.keeperSituation()!!))
    }

    @Test
    fun theMoneyGateAsksItUnderADollarCapAndNotWithoutOne() {
        val none = HourlySpend(0.0, false)
        assertEquals(
            CallDecision.Refuse(GateReason.UnpricedUnderCap),
            planningMayCall(PlanningCaller.Planner, PriceStatus.Unknown, null, SpendCaps(runCostCapUsd = 1.0), none, 0, true),
        )
        assertEquals(CallDecision.Allow, planningMayCall(PlanningCaller.Planner, PriceStatus.Unknown, null, SpendCaps(), none, 0, true))
    }
}
