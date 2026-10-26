package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TaskUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskUsageTest {
    @Test
    fun reportsAccumulateAcrossDrains() {
        val first = (null as TaskUsage?).plus(ManagedSessionUsage(inputTokens = 100, outputTokens = 20, costUsd = 0.01))
        val second = first.plus(ManagedSessionUsage(inputTokens = 50, outputTokens = 5))
        assertEquals(TaskUsage(inputTokens = 150, outputTokens = 25, costUsd = 0.01), second)
        assertEquals(175L, second?.totalTokens)
    }

    @Test
    fun aReportWithoutTokensOrCostChangesNothing() {
        assertNull((null as TaskUsage?).plus(ManagedSessionUsage(latencyMillis = 300)))
        val existing = TaskUsage(inputTokens = 1)
        assertEquals(existing, existing.plus(ManagedSessionUsage(cacheHitFraction = 0.5f)))
    }
}
