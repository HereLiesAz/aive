package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentProviderRegistryRepositoryTest {
    @Test
    fun repositoryWorkSelectsProviderCompatibleWithLinkedSource() = runBlocking {
        val githubOnly = SourceAwareProvider(
            id = AgentProviderId("github-only"),
            supportedSources = setOf(RepositorySource.GitHub),
        )
        val gitLabCapable = SourceAwareProvider(
            id = AgentProviderId("gitlab"),
            supportedSources = setOf(RepositorySource.GitLab),
        )
        val utilities = RecordingLocalOrchestrationUtilities()
        val registry = AgentProviderRegistry(
            providers = listOf(githubOnly, gitLabCapable),
            orchestrationUtilities = utilities,
        )

        val selected = registry.select(
            ProviderSelectionRequest(
                requiredCapabilities = setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite),
                repository = RepositoryRef(
                    owner = "team",
                    name = "project",
                    source = RepositorySource.GitLab,
                    remoteUrl = "https://gitlab.com/team/project",
                ),
            ),
        )

        assertEquals(gitLabCapable.id, selected.id)
        assertEquals(1, utilities.agentRoutingCalls)
    }

    @Test
    fun repositoryWorkFailsWhenNoProviderSupportsLinkedSource() = runBlocking {
        val registry = AgentProviderRegistry(
            listOf(
                SourceAwareProvider(
                    id = AgentProviderId("github-only"),
                    supportedSources = setOf(RepositorySource.GitHub),
                ),
            ),
        )

        val failure = assertFailsWith<IllegalStateException> {
            registry.select(
                ProviderSelectionRequest(
                    requiredCapabilities = setOf(AgentCapability.RepositoryWrite),
                    repository = RepositoryRef(
                        owner = "team",
                        name = "project",
                        source = RepositorySource.Local,
                        localPath = "/workspace/project",
                    ),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("Local Git"))
    }

    @Test
    fun linkedProjectContextSkipsProviderThatCannotOpenThatRepositorySource() = runBlocking {
        val githubOnly = SourceAwareProvider(
            id = AgentProviderId("github-reviewer"),
            supportedSources = setOf(RepositorySource.GitHub),
            capabilities = setOf(AgentCapability.Research),
        )
        val gitLabCapable = SourceAwareProvider(
            id = AgentProviderId("gitlab-reviewer"),
            supportedSources = setOf(RepositorySource.GitLab),
            capabilities = setOf(AgentCapability.Research),
        )
        val registry = AgentProviderRegistry(listOf(githubOnly, gitLabCapable))

        val selected = registry.select(
            ProviderSelectionRequest(
                requiredCapabilities = setOf(AgentCapability.Research),
                repository = RepositoryRef(
                    owner = "team",
                    name = "project",
                    source = RepositorySource.GitLab,
                    remoteUrl = "https://gitlab.com/team/project",
                ),
            ),
        )

        assertEquals(gitLabCapable.id, selected.id)
    }
}

private class SourceAwareProvider(
    override val id: AgentProviderId,
    private val supportedSources: Set<RepositorySource>,
    private val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.RepositoryRead,
        AgentCapability.RepositoryWrite,
    ),
) : AgentProvider {
    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(capabilities)

    override suspend fun supportsRepository(repository: RepositoryRef?): Boolean =
        repository == null || repository.source in supportedSources

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle =
        AgentRunHandle(ProviderRunId("run"))

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult =
        ProviderActionResult.Accepted
}
