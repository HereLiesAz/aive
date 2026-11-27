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

    @Test
    fun deliveryEvidenceIsAUnionAndMissingReportsDoNotEraseIt() {
        val a = ManagedSessionUsage(deliveredArtifactIds = setOf("a"))
        val b = ManagedSessionUsage(deliveredArtifactIds = setOf("b"))
        assertEquals(setOf("a", "b"), a.plusReport(b).plusReport(a).deliveredArtifactIds)
        assertEquals(setOf("a"), a.plusReport(ManagedSessionUsage()).deliveredArtifactIds)
        assertEquals(null, ManagedSessionUsage().plusReport(ManagedSessionUsage()).deliveredArtifactIds)
        assertEquals(emptySet<String>(), ManagedSessionUsage().plusReport(ManagedSessionUsage(deliveredArtifactIds = emptySet())).deliveredArtifactIds)
    }

    @Test
    fun modelAgreementCannotBeRestoredByLaterReportsInAMixedBatch() {
        val a=ManagedSessionUsage(inputTokens=10,model="a")
        val b=ManagedSessionUsage(inputTokens=20,model="b")
        val unknown=ManagedSessionUsage(inputTokens=5)
        assertEquals(true,a.plusReport(a).modelConsistent)
        val mixed=a.plusReport(b)
        assertEquals("b",mixed.model)
        assertEquals(30L,mixed.inputTokens)
        assertEquals(false,mixed.modelConsistent)
        assertEquals(false,mixed.plusReport(b).modelConsistent)
        assertEquals(false,a.plusReport(unknown).modelConsistent)
        assertEquals(false,unknown.plusReport(a).modelConsistent)
    }
}
