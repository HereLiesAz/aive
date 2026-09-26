package com.hereliesaz.aive

import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.RepositoryServiceCatalog
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.providers.llm.HostedLlmProviders
import com.hereliesaz.geministrator.providers.llm.TextGenerationApi
import com.hereliesaz.geministrator.providers.llm.TextGenerationResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfiguredAndroidProvidersTest {
    @Test
    fun blankCredentialLeavesProviderRegistryEmpty() {
        assertTrue(configuredAndroidProviders("   ").isEmpty())
    }

    @Test
    fun storedCredentialConfiguresJulesProvider() {
        val providers = configuredAndroidProviders(" test-key ")

        assertEquals(1, providers.size)
        assertEquals(AgentProviderId("jules"), providers.single().id)
    }

    @Test
    fun everyHostedCredentialConfiguresItsProvider() {
        HostedLlmProviders.entries.forEach { spec ->
            val providers = configuredAndroidProviders(
                credentials = mapOf(spec.id to if (spec.accountScoped) "account:key-${spec.id}" else "key-${spec.id}"),
            )
            assertTrue(
                providers.any { it.id == AgentProviderId(spec.id) },
                "${spec.displayName} should be configured from its stored credential",
            )
        }
    }

    @Test
    fun installedGeminiTransportUsesCanonicalGeminiProviderIdWithoutApiKey() {
        val installedApi = object : TextGenerationApi {
            override suspend fun generate(prompt: String): TextGenerationResult =
                TextGenerationResult(text = "installed")
        }

        val providers = configuredAndroidProviders(
            credentials = emptyMap(),
            installedGeminiApi = installedApi,
        )

        assertEquals(listOf(AgentProviderId(ProviderCatalog.GEMINI_ID)), providers.map { it.id })
    }

    @Test
    fun installedGeminiDoesNotDuplicateApiGeminiProvider() {
        val installedApi = object : TextGenerationApi {
            override suspend fun generate(prompt: String): TextGenerationResult =
                TextGenerationResult(text = "installed")
        }

        val providers = configuredAndroidProviders(
            credentials = mapOf(ProviderCatalog.GEMINI_ID to "gemini-key"),
            installedGeminiApi = installedApi,
        )

        assertEquals(1, providers.count { it.id == AgentProviderId(ProviderCatalog.GEMINI_ID) })
    }

    @Test
    fun gitLabWorkspaceProviderRequiresBothLlmAndRepositoryCredentials() {
        HttpClient(CIO).use { client ->
            val withoutGitLab = configuredAndroidProviders(
                credentials = mapOf(ProviderCatalog.OPENAI_ID to "openai-key"),
                repositoryHttpClient = client,
            )
            assertTrue(withoutGitLab.any { it.id == AgentProviderId("openai") })
            assertFalse(withoutGitLab.any { it.id == AgentProviderId("openai-gitlab-workspace") })

            val withGitLab = configuredAndroidProviders(
                credentials = mapOf(ProviderCatalog.OPENAI_ID to "openai-key"),
                repositoryCredentials = mapOf(RepositoryServiceCatalog.GITLAB_ID to "gitlab-token"),
                repositoryHttpClient = client,
            )
            assertTrue(withGitLab.any { it.id == AgentProviderId("openai") })
            assertTrue(withGitLab.any { it.id == AgentProviderId("openai-gitlab-workspace") })
        }
    }
}
