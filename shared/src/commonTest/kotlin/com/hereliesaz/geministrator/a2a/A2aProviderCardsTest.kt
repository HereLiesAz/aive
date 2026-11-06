package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.a2aDescribeProviders
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.AgentProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class A2aProviderCardsTest {
    /** A provider that holds a fake credential, base URL and account; none of it may reach a card. */
    private class FakeProvider(name: String, private val caps: Set<AgentCapability>) : AgentProvider {
        override val id = AgentProviderId(name)
        val apiKey = "sk-FAKE-KEY-$name-0001"
        val baseUrl = "https://user:pw-$name@billing.example/v1?token=FAKE-TOKEN-$name"
        val account = "owner-$name@example.com"
        override suspend fun capabilities() = AgentCapabilities(supported = caps)
        override suspend fun start(request: AgentTaskRequest): AgentRunHandle = AgentRunHandle(ProviderRunId("r"))
        override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()
        override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
        override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
        override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
    }

    private class Host(
        val roles: List<RoleDefinition>,
        val providers: List<A2aProviderInfo>,
    ) : A2aWorkflowHost {
        override suspend fun run(runId: String): Pair<WorkflowRun, WorkflowDefinition?>? = null
        override suspend fun busy() = false
        override suspend fun cancel(runId: String) = false
        override suspend fun roles() = roles
        override suspend fun providers() = providers
    }

    private val researcher = BuiltInRoles.Researcher.copy(preferredProviderId = AgentProviderId("openai"))
    private val architect = BuiltInRoles.Architect
    private val infos = listOf(
        A2aProviderInfo("openai", "OpenAI / Codex", setOf(AgentCapability.PlanGeneration, AgentCapability.Research)),
        A2aProviderInfo("anthropic", "Claude", setOf(AgentCapability.Messaging)),
    )

    private fun server(describe: () -> Boolean, providers: List<A2aProviderInfo> = infos, lanOpen: () -> Boolean = { false }) = A2aServer(
        host = Host(listOf(researcher, architect), providers),
        token = { "t" },
        baseUrl = { "http://127.0.0.1:41241" },
        agentVersion = "1",
        lanOpen = lanOpen,
        describeProviders = describe,
    )

    @Test
    fun theCardListsLinkedProvidersAndEachRolesAssignment() = runTest {
        val card = server({ true }).card()
        val ids = card.skills.map { it.id }
        assertEquals(listOf("hive", "role:researcher", "role:architect", "provider:anthropic", "provider:openai"), ids)
        val openai = card.skills.first { it.id == "provider:openai" }
        assertEquals("OpenAI / Codex", openai.name)
        assertEquals(listOf("provider", "plangeneration", "research"), openai.tags)
        assertEquals("Linked provider. Reports: plan generation, research.", openai.description)
        assertTrue("provider:openai" in card.skills.first { it.id == "role:researcher" }.tags)
        assertTrue("provider:auto" in card.skills.first { it.id == "role:architect" }.tags)
    }

    @Test
    fun aPreferredProviderThatIsNotLinkedReadsAsAuto() = runTest {
        val card = server({ true }, providers = listOf(infos[1])).card()
        assertTrue("provider:auto" in card.skills.first { it.id == "role:researcher" }.tags)
    }

    @Test
    fun withTheSettingOffTheCardIsUnchanged() = runTest {
        val card = server({ false }).card()
        assertEquals(listOf("hive", "role:researcher", "role:architect"), card.skills.map { it.id })
        assertTrue(card.skills.none { skill -> skill.tags.any { it.startsWith("provider") } })
    }

    @Test
    fun theDefaultRuleFollowsTheNetworkUnlessTheKeeperChose() {
        assertTrue(a2aDescribeProviders(null, openToNetwork = false))
        assertFalse(a2aDescribeProviders(null, openToNetwork = true))
        assertTrue(a2aDescribeProviders(true, openToNetwork = true))
        assertFalse(a2aDescribeProviders(false, openToNetwork = false))
    }

    @Test
    fun openingTheNetworkDropsTheProvidersUnlessChosen() = runTest {
        var open = false
        val stored: Boolean? = null
        val server = server({ a2aDescribeProviders(stored, open) }, lanOpen = { open })
        assertTrue(server.card().skills.any { it.id.startsWith("provider:") })
        open = true
        assertTrue(server.card().skills.none { it.id.startsWith("provider:") })
        val chosen = server({ a2aDescribeProviders(true, open) }, lanOpen = { open })
        assertTrue(chosen.card().skills.any { it.id.startsWith("provider:") })
    }

    @Test
    fun aRemoteAgentsHostNeverAppearsAndItsKeyIsStable() = runTest {
        val remote = A2aProviderInfo("a2a:internal-host.lan", "A2A · internal-host.lan", setOf(AgentCapability.Research))
        val card = server({ true }, providers = listOf(remote)).card()
        val text = A2aCodec.encodeCard(card).toString()
        assertFalse("internal-host" in text)
        val skill = card.skills.single { it.id.startsWith("provider:") }
        assertEquals("Remote A2A agent", skill.name)
        assertEquals(A2aProviderCards.cardKey("a2a:internal-host.lan"), A2aProviderCards.cardKey("a2a:internal-host.lan"))
        assertTrue(skill.id.startsWith("provider:a2a-"))
    }

    @Test
    fun providerTextIsOneBoundedLineAndTheCountIsCapped() = runTest {
        val many = (1..60).map { A2aProviderInfo("p$it", "Name\nwith\tbreaks ${"z".repeat(200)}", emptySet()) }
        val skills = A2aProviderCards.skills(many)
        assertEquals(A2aProviderCards.MAX_PROVIDER_SKILLS, skills.size)
        assertTrue(skills.all { it.name.length <= A2aProviderCards.MAX_NAME_CHARS && it.name.none(Char::isISOControl) })
        assertEquals(skills, A2aProviderCards.skills(many.reversed()))
        assertEquals("a b", a2aOneLine("  a \n\t b  ", 10))
        assertEquals("", a2aOneLine(null, 10))
        assertEquals("ab", a2aOneLine("abcd", 2))
    }

    @Test
    fun noCredentialBaseUrlOrAccountReachesTheCard() = runTest {
        val providers = listOf(
            FakeProvider("openai", setOf(AgentCapability.Research)),
            FakeProvider("anthropic", setOf(AgentCapability.PlanGeneration)),
        )
        val registry = AgentProviderRegistry(providers, inferenceSettings = InMemorySettings())
        val live = registry.a2aProviderInfos()
        assertEquals(listOf("anthropic", "openai"), live.map { it.id })
        val card = server({ true }, providers = live).card()
        val text = A2aCodec.encodeCard(card).toString()
        for (provider in providers) {
            for (secret in listOf(provider.apiKey, provider.baseUrl, provider.account, "billing.example", "FAKE-TOKEN", "pw-", "@example.com", "sk-")) {
                assertFalse(secret in text, "leaked $secret")
            }
        }
        assertTrue("provider:openai" in text)
    }
}
