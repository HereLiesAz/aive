package com.hereliesaz.aive

import com.hereliesaz.geministrator.LocalOrchestrationSpecialistStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LocalOrchestrationSpecialistLifecycleTest {
    @Test
    fun removalFailureBecomesVisibleFailedStatus() = runBlocking {
        val status = removeLocalOrchestrationSpecialists(
            resetExecutor = {},
            removeArtifacts = { error("disk failure") },
        )

        val failed = assertIs<LocalOrchestrationSpecialistStatus.Failed>(status)
        assertEquals("disk failure", failed.message)
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingFailure() {
        assertFailsWith<CancellationException> {
            runBlocking {
                removeLocalOrchestrationSpecialists(
                    resetExecutor = {},
                    removeArtifacts = { throw CancellationException("scope cancelled") },
                )
            }
        }
    }

    @Test
    fun successfulRemovalReturnsNotInstalled() = runBlocking {
        var reset = false
        var removed = false

        val status = removeLocalOrchestrationSpecialists(
            resetExecutor = { reset = true },
            removeArtifacts = { removed = true },
        )

        assertEquals(true, reset)
        assertEquals(true, removed)
        assertEquals(LocalOrchestrationSpecialistStatus.NotInstalled, status)
    }
}
