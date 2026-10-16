package com.hereliesaz.aive.remote

import com.hereliesaz.geministrator.domain.RepositorySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiveRemoteBridgeTest {
    @Test
    fun withNothingLinkedAPromptIsNeverSentToAKeylessGateway() {
        assertNull(remoteTextApi(credentials = emptyMap(), providerId = null, model = null))
    }

    @Test
    fun aNamedKeylessProviderNeedsNoCredential() {
        assertNotNull(remoteTextApi(credentials = emptyMap(), providerId = "kilo", model = null))
    }

    @Test
    fun aNamedKeyedProviderWithoutACredentialIsNotLinked() {
        assertNull(remoteTextApi(credentials = emptyMap(), providerId = "openai", model = null))
    }

    @Test
    fun onlyGitHubRepositoryUrlsParse() {
        val ref = assertNotNull(gitHubRepositoryRef("https://github.com/HereLiesAz/IDEaz.git"))
        assertEquals("HereLiesAz", ref.owner)
        assertEquals("IDEaz", ref.name)
        assertEquals(RepositorySource.GitHub, ref.source)
        assertNull(gitHubRepositoryRef("https://gitlab.com/a/b"))
        assertNull(gitHubRepositoryRef("https://github.com/a"))
        assertNull(gitHubRepositoryRef(null))
    }

    @Test
    fun launchesQueueInOrderAndLeaveOnlyWhenCompleted() {
        drain()
        val first = assertNotNull(AiveRemoteBridge.enqueue("P", "first", null))
        val second = assertNotNull(AiveRemoteBridge.enqueue("P", "second", null))
        assertEquals(listOf(first, second), AiveRemoteBridge.pendingLaunches.value.map { it.requestId })
        AiveRemoteBridge.complete(first, failure = null)
        AiveRemoteBridge.complete(second, failure = "Runtime is unavailable")
        assertEquals(emptyList(), AiveRemoteBridge.pendingLaunches.value)
        assertEquals(RemoteLaunchOutcome(first, started = true, error = null), AiveRemoteBridge.outcomeOf(first))
        assertEquals(
            RemoteLaunchOutcome(second, started = false, error = "Runtime is unavailable"),
            AiveRemoteBridge.outcomeOf(second),
        )
    }

    @Test
    fun completingTwiceRecordsOneOutcome() {
        drain()
        val id = assertNotNull(AiveRemoteBridge.enqueue("P", "once", null))
        AiveRemoteBridge.complete(id, failure = null)
        AiveRemoteBridge.complete(id, failure = "late failure")
        assertEquals(1, AiveRemoteBridge.outcomes.replayCache.count { it.requestId == id })
        assertTrue(assertNotNull(AiveRemoteBridge.outcomeOf(id)).started)
    }

    @Test
    fun theQueueIsCapped() {
        drain()
        repeat(AiveRemoteBridge.MAX_PENDING) { assertNotNull(AiveRemoteBridge.enqueue("P", "fill $it", null)) }
        assertNull(AiveRemoteBridge.enqueue("P", "one too many", null))
        assertEquals(AiveRemoteBridge.MAX_PENDING, AiveRemoteBridge.pendingLaunches.value.size)
        drain()
    }

    @Test
    fun staleRequestsExpireInsteadOfLaunching() {
        drain()
        val start = 1_000L
        val stale = assertNotNull(AiveRemoteBridge.enqueue("P", "stale", null, nowMillis = start))
        val fresh = assertNotNull(
            AiveRemoteBridge.enqueue("P", "fresh", null, nowMillis = start + AiveRemoteBridge.PENDING_TTL_MILLIS - 1),
        )
        AiveRemoteBridge.pruneExpired(nowMillis = start + AiveRemoteBridge.PENDING_TTL_MILLIS)
        assertEquals(listOf(fresh), AiveRemoteBridge.pendingLaunches.value.map { it.requestId })
        val outcome = assertNotNull(AiveRemoteBridge.outcomeOf(stale))
        assertFalse(outcome.started)
        assertEquals(AiveRemoteBridge.EXPIRED_MESSAGE, outcome.error)
        drain()
    }

    @Test
    fun invalidLaunchesAreRejectedBeforeQueueing() {
        val queued = mutableListOf<String>()
        val enqueue = { name: String, _: String, _: com.hereliesaz.geministrator.domain.RepositoryRef? ->
            queued += name
            7L
        }
        assertFailsWith<IllegalArgumentException> { queueRemoteLaunch("P", " ", null, enqueue) }
        assertFailsWith<IllegalArgumentException> { queueRemoteLaunch(null, "goal", null, enqueue) }
        assertFailsWith<IllegalArgumentException> { queueRemoteLaunch("P", "goal", "https://gitlab.com/a/b", enqueue) }
        assertEquals(emptyList(), queued)
        assertEquals(7L, queueRemoteLaunch(" P ", "goal", "https://github.com/a/b", enqueue))
        assertEquals(listOf("P"), queued)
        assertFailsWith<IllegalStateException> { queueRemoteLaunch("P", "goal", null) { _, _, _ -> null } }
    }

    /** Empties the process-wide queue left by other tests. */
    private fun drain() = AiveRemoteBridge.pruneExpired(nowMillis = Long.MAX_VALUE / 2)
}
