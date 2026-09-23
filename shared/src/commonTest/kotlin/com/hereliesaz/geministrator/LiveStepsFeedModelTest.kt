package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.orchestration.LaunchProgress
import com.hereliesaz.geministrator.orchestration.reportLaunchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class LiveStepsFeedModelTest {
    private fun LiveStepsFeedModel.snapshot() = rows.value.map { Triple(it.text, it.slot, it.exiting) }

    @Test
    fun firstLineRestsInTheBottomSlot() {
        val model = LiveStepsFeedModel(maxRows = 5)
        model.push("a")
        assertEquals(listOf(Triple("a", 4, false)), model.snapshot())
    }

    @Test
    fun eachNewLineShiftsExistingRowsUpOneSlot() {
        val model = LiveStepsFeedModel(maxRows = 5)
        model.push("a")
        model.push("b")
        model.push("c")
        assertEquals(
            listOf(Triple("a", 2, false), Triple("b", 3, false), Triple("c", 4, false)),
            model.snapshot(),
        )
    }

    @Test
    fun sixthLineEvictsOldestAsExitingAboveTheTopInTheSameTransition() {
        val model = LiveStepsFeedModel(maxRows = 5)
        listOf("a", "b", "c", "d", "e").forEach(model::push)
        assertTrue(model.rows.value.none { it.exiting })
        model.push("f")
        assertEquals(
            listOf(
                Triple("a", -1, true),
                Triple("b", 0, false),
                Triple("c", 1, false),
                Triple("d", 2, false),
                Triple("e", 3, false),
                Triple("f", 4, false),
            ),
            model.snapshot(),
        )
    }

    @Test
    fun previouslyExitedRowIsDroppedOnNextPush() {
        val model = LiveStepsFeedModel(maxRows = 5)
        listOf("a", "b", "c", "d", "e", "f", "g").forEach(model::push)
        val rows = model.snapshot()
        assertEquals(Triple("b", -1, true), rows.first())
        assertEquals(6, rows.size)
        assertEquals(1, rows.count { it.third })
    }

    @Test
    fun rowIdsAreStableAcrossShifts() {
        val model = LiveStepsFeedModel(maxRows = 2)
        model.push("a")
        val idA = model.rows.value.single().id
        model.push("b")
        assertEquals(idA, model.rows.value.first { it.text == "a" }.id)
        model.push("c")
        assertEquals(idA, model.rows.value.first { it.exiting }.id)
    }

    @Test
    fun blankAndConsecutiveDuplicateLinesAreIgnored() {
        val model = LiveStepsFeedModel()
        model.push("a")
        model.push("  ")
        model.push("a")
        assertEquals(1, model.rows.value.size)
        model.clear()
        assertTrue(model.rows.value.isEmpty())
    }

    @Test
    fun launchProgressReachesTheFeedAcrossDispatcherHops() = runTest {
        val model = LiveStepsFeedModel()
        withContext(LaunchProgress(model::push)) {
            reportLaunchProgress("one")
            withContext(Dispatchers.Default) { reportLaunchProgress("two") }
        }
        reportLaunchProgress("ignored without a sink")
        assertEquals(listOf("one", "two"), model.rows.value.map { it.text })
    }
}
