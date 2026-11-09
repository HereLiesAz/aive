package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.persistence.InMemorySettings
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
            inferenceSettings = InMemorySettings(),
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
            inferenceSettings = InMemorySettings(),
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
        val registry = AgentProviderRegistry(listOf(githubOnly, gitLabCapable), inferenceSettings = InMemorySettings())

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

    @Test
    fun workWithoutARepositoryNeverGoesToAnAgentThatOnlyWorksInsideOne() = runBlocking {
        // OpenCode on GitHub: listed first, yet useless to a hive with no repository.
        val insideARepository = SourceAwareProvider(
            id = AgentProviderId("opencode"),
            supportedSources = setOf(RepositorySource.GitHub),
            worksWithoutRepository = false,
        )
        val anywhere = SourceAwareProvider(AgentProviderId("claude"), setOf(RepositorySource.GitHub))
        val request = ProviderSelectionRequest(requiredCapabilities = setOf(AgentCapability.RepositoryWrite))

        val both = AgentProviderRegistry(listOf(insideARepository, anywhere), inferenceSettings = InMemorySettings())
        assertEquals(anywhere.id, both.select(request).id)

        val onlyInside = AgentProviderRegistry(listOf(insideARepository), inferenceSettings = InMemorySettings())
        assertFailsWith<IllegalStateException> { onlyInside.select(request) }
        Unit
    }

    @Test
    fun explicitOnlyProviderIsNeverChosenAutomatically() = runBlocking {
        val explicitAgent = SourceAwareProvider(
            id = AgentProviderId("explicit-agent"),
            supportedSources = setOf(RepositorySource.GitHub),
            explicitOnly = true,
        )
        val registry = AgentProviderRegistry(listOf(explicitAgent), inferenceSettings = InMemorySettings())
        val github = RepositoryRef(owner = "team", name = "project", source = RepositorySource.GitHub)

        val failure = assertFailsWith<IllegalStateException> {
            registry.select(
                ProviderSelectionRequest(
                    requiredCapabilities = setOf(AgentCapability.RepositoryWrite),
                    repository = github,
                ),
            )
        }
        assertTrue(failure.message.orEmpty().contains("assign to a role"))

        val preferred = registry.select(
            ProviderSelectionRequest(
                preferredProviderId = explicitAgent.id,
                requiredCapabilities = setOf(AgentCapability.RepositoryWrite),
                repository = github,
            ),
        )
        assertEquals(explicitAgent.id, preferred.id)
    }

    @Test
    fun explicitOnlyProviderYieldsToAutomaticProviders() = runBlocking {
        val explicitAgent = SourceAwareProvider(
            id = AgentProviderId("explicit-agent"),
            supportedSources = setOf(RepositorySource.GitHub),
            explicitOnly = true,
        )
        val automatic = SourceAwareProvider(
            id = AgentProviderId("automatic"),
            supportedSources = setOf(RepositorySource.GitHub),
        )
        val registry = AgentProviderRegistry(listOf(explicitAgent, automatic), inferenceSettings = InMemorySettings())

        val selected = registry.select(
            ProviderSelectionRequest(
                requiredCapabilities = setOf(AgentCapability.RepositoryWrite),
                repository = RepositoryRef(owner = "team", name = "project", source = RepositorySource.GitHub),
            ),
        )

        assertEquals(automatic.id, selected.id)
    }

    @Test
    fun aProviderThatKeepsFailingIsPassedOverUntilItSucceedsAgain() = runBlocking {
        val first = SourceAwareProvider(AgentProviderId("first"), setOf(RepositorySource.GitHub))
        val second = SourceAwareProvider(AgentProviderId("second"), setOf(RepositorySource.GitHub))
        val registry = AgentProviderRegistry(listOf(first, second), inferenceSettings = InMemorySettings())
        val request = ProviderSelectionRequest(requiredCapabilities = setOf(AgentCapability.RepositoryWrite))

        assertEquals(first.id, registry.select(request).id)
        repeat(3) { registry.recordOutcome(first.id, success = false) }
        assertEquals(second.id, registry.select(request).id, "three failures in a row open the circuit")
        registry.recordOutcome(first.id, success = true)
        assertEquals(first.id, registry.select(request).id, "a success closes it")
    }

    @Test
    fun autoWorkSpreadsOverEveryProviderThatCanDoItAndAPinnedRoleKeepsItsOwn() = runBlocking {
        val three = listOf("a", "b", "c").map { SourceAwareProvider(AgentProviderId(it), supportedSources = emptySet()) }
        val registry = AgentProviderRegistry(
            providers = three,
            orchestrationUtilities = RecordingLocalOrchestrationUtilities(),
            inferenceSettings = InMemorySettings(),
        )
        val auto = ProviderSelectionRequest(requiredCapabilities = setOf(AgentCapability.RepositoryRead))

        val chosen = (1..6).map { registry.select(auto).id.value }

        assertEquals(setOf("a", "b", "c"), chosen.toSet(), "all three are used, not just the first registered")
        assertEquals(chosen.take(3).toSet(), chosen.drop(3).toSet(), "and the turn repeats")
        val pinned = ProviderSelectionRequest(
            requiredCapabilities = setOf(AgentCapability.RepositoryRead),
            preferredProviderId = AgentProviderId("b"),
        )
        assertEquals(List(4) { "b" }, List(4) { registry.select(pinned).id.value }, "a pinned role is never moved")
    }

    @Test
    fun aProviderThatCannotReportItsCapabilitiesDoesNotStopTheOthersBeingChosen() = runBlocking {
        val broken = BrokenCapabilitiesProvider(AgentProviderId("broken"))
        val healthy = SourceAwareProvider(AgentProviderId("healthy"), supportedSources = emptySet())
        val registry = AgentProviderRegistry(listOf(broken, healthy), inferenceSettings = InMemorySettings())
        val request = ProviderSelectionRequest(requiredCapabilities = setOf(AgentCapability.RepositoryRead))

        assertEquals(healthy.id, registry.select(request).id)
        assertEquals(healthy.id, registry.select(request.copy(preferredProviderId = broken.id)).id)
        assertTrue(registry.canStaff(setOf(AgentCapability.RepositoryRead)))

        // With nothing else to turn to, the refusal says which provider could not be read.
        val alone = AgentProviderRegistry(listOf(broken), inferenceSettings = InMemorySettings())
        val failure = assertFailsWith<IllegalStateException> { alone.select(request) }
        assertTrue("broken" in failure.message.orEmpty(), failure.message)
    }
}

private class BrokenCapabilitiesProvider(override val id: AgentProviderId) : AgentProvider {
    override suspend fun capabilities(): AgentCapabilities = throw IllegalStateException("capability probe failed")
    override suspend fun start(request: AgentTaskRequest): AgentRunHandle = error("not started")
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}

private class SourceAwareProvider(
    override val id: AgentProviderId,
    private val supportedSources: Set<RepositorySource>,
    private val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.RepositoryRead,
        AgentCapability.RepositoryWrite,
    ),
    override val explicitOnly: Boolean = false,
    private val worksWithoutRepository: Boolean = true,
) : AgentProvider {
    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(capabilities)

    override suspend fun supportsRepository(repository: RepositoryRef?): Boolean =
        if (repository == null) worksWithoutRepository else repository.source in supportedSources

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
