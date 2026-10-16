package com.hereliesaz.aive.remote

import com.hereliesaz.geministrator.domain.RepositorySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun launchesQueueInOrderAndLeaveWhenConsumed() {
        val first = AiveRemoteBridge.enqueue("P", "first", null)
        val second = AiveRemoteBridge.enqueue("P", "second", null)
        val queued = AiveRemoteBridge.pendingLaunches.value.map { it.requestId }
        assertEquals(listOf(first, second), queued.filter { it == first || it == second })
        AiveRemoteBridge.consume(first)
        AiveRemoteBridge.consume(second)
        assertEquals(emptyList(), AiveRemoteBridge.pendingLaunches.value.filter { it.requestId == first || it.requestId == second })
    }
}
