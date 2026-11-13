package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.workflow.HourlySpend
import com.hereliesaz.geministrator.workflow.SpendCaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanningGateTest {
    private val none = HourlySpend(0.0, false)

    private fun plan(caller: PlanningCaller, price: PriceStatus, caps: SpendCaps = SpendCaps(), hourly: HourlySpend = none, calls: Int = 0, room: Boolean = true, est: Double? = 0.01) =
        planningMayCall(caller, price, est, caps, hourly, calls, room)

    @Test
    fun unpricedUnderACapRefusesThePlannerAndSkipsTheReviewer() {
        val caps = SpendCaps(runCostCapUsd = 1.0)
        assertEquals(CallDecision.Refuse(GateReason.UnpricedUnderCap), plan(PlanningCaller.Planner, PriceStatus.Unknown, caps))
        assertEquals(CallDecision.ChecklistOnly(GateReason.UnpricedUnderCap), plan(PlanningCaller.Reviewer, PriceStatus.Unknown, caps))
    }

    @Test
    fun pricedUnderHalfTheHourlyCapIsAllowedAndOverIsNot() {
        val caps = SpendCaps(hourlySpendCapUsd = 1.0)
        assertEquals(CallDecision.Allow, plan(PlanningCaller.Planner, PriceStatus.Priced, caps, HourlySpend(0.2, false)))
        assertEquals(CallDecision.Refuse(GateReason.OverHourlyCap), plan(PlanningCaller.Planner, PriceStatus.Priced, caps, HourlySpend(0.5, false)))
        assertEquals(CallDecision.Refuse(GateReason.UnpricedUsage), plan(PlanningCaller.Planner, PriceStatus.Priced, caps, HourlySpend(0.0, true)))
    }

    @Test
    fun freeIsAllowedUnderACapAndNoCapAllowsAnything() {
        assertEquals(CallDecision.Allow, plan(PlanningCaller.Planner, PriceStatus.Free, SpendCaps(hourlySpendCapUsd = 1.0)))
        assertEquals(CallDecision.Allow, plan(PlanningCaller.Planner, PriceStatus.Unknown))
    }

    @Test
    fun noRoomSkipsOnlyTheReviewerAndTheCallLimitIsHard() {
        assertEquals(CallDecision.ChecklistOnly(GateReason.NoRoom), plan(PlanningCaller.Reviewer, PriceStatus.Free, room = false))
        assertEquals(CallDecision.Allow, plan(PlanningCaller.Planner, PriceStatus.Free, room = false))
        assertEquals(CallDecision.Refuse(GateReason.CallLimit), plan(PlanningCaller.Planner, PriceStatus.Free, calls = PlanningLimits.MAX_CALLS))
    }

    @Test
    fun herCheckpointsNeedHelpfulSpacingAndTheKeepersOwnGoal() {
        fun may(level: QueenLevel = QueenLevel.Helpful, calls: Int = 0, last: Long? = null, now: Long = 1_000_000_000L, outside: Boolean = false, waiting: Boolean = false, frozen: Boolean = false) =
            queenMayCall(QueenCallKind.Checkpoint, level, PriceStatus.Free, null, SpendCaps(), none, calls, last, now, frozen, outside, waiting)
        assertTrue(may())
        assertFalse(may(level = QueenLevel.Quiet))
        assertFalse(may(calls = PlanningLimits.QUEEN_CALLS_PER_RUN))
        assertFalse(may(last = 999_999_000L))
        assertFalse(may(outside = true))
        assertFalse(may(waiting = true))
        assertFalse(may(frozen = true))
    }

    @Test
    fun aRulingIsMadeAtQuietButNeverUnpricedUnderACap() {
        assertTrue(queenMayCall(QueenCallKind.Ruling, QueenLevel.Quiet, PriceStatus.Free, null, SpendCaps(), none, 0, null, 0, true, true, true))
        assertFalse(queenMayCall(QueenCallKind.Ruling, QueenLevel.Quiet, PriceStatus.Unknown, null, SpendCaps(runCostCapUsd = 1.0), none, 0, null, 0, false, false, false))
    }

    @Test
    fun herSourceIsThePinOrTheBestOrderAndTheReviewerPrefersAnotherSource() {
        val linked = listOf(SourceTraits("free", false, false, true), SourceTraits("paid", true, true, true), SourceTraits("down", true, true, false))
        assertEquals(listOf("pinned"), queenSourceOrder("pinned", linked))
        assertEquals(listOf("paid", "free", "down"), queenSourceOrder(null, linked))
        assertEquals(emptyList(), queenSourceOrder(null, emptyList()))
        assertEquals(listOf("b", "a"), reviewerSourceOrder("a", null, listOf("a", "b")))
        assertEquals(listOf("a"), reviewerSourceOrder("a", null, listOf("a")))
        assertEquals(listOf("z"), reviewerSourceOrder("a", "z", listOf("a", "b")))
    }
}
