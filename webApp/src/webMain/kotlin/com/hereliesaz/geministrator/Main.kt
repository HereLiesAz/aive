package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.jules.JulesApiKeyProvider
import com.hereliesaz.geministrator.providers.jules.JulesProvider
import com.hereliesaz.geministrator.providers.jules.JulesRestApi
import com.hereliesaz.geministrator.providers.llm.AnthropicProvider
import com.hereliesaz.geministrator.providers.llm.GeminiProvider
import com.hereliesaz.geministrator.providers.llm.LlmApiKeyProvider
import com.hereliesaz.geministrator.providers.llm.OpenAiProvider
import com.hereliesaz.geministrator.providers.llm.XaiProvider
import com.hereliesaz.geministrator.workflow.GitHubActionsExecutorIntegration
import com.hereliesaz.geministrator.workflow.GitHubRestActionsClient
import com.hereliesaz.geministrator.workflow.GitHubRestRepositoryOperationClient
import com.hereliesaz.geministrator.workflow.GitHubTokenProvider
import com.hereliesaz.geministrator.workflow.GitLabRestRepositoryOperationClient
import com.hereliesaz.geministrator.workflow.RepositoryOperationExecutorIntegration
import com.hereliesaz.geministrator.workflow.RepositoryServiceTokenProvider
import com.hereliesaz.geministrator.workflow.RoutingRepositoryOperationClient
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import kotlinx.browser.window

private const val JULES_API_KEY_STORAGE_KEY = "haive.julesApiKey"
private const val OPENAI_API_KEY_STORAGE_KEY = "haive.openaiApiKey"
private const val ANTHROPIC_API_KEY_STORAGE_KEY = "haive.anthropicApiKey"
private const val GEMINI_API_KEY_STORAGE_KEY = "haive.geminiApiKey"
private const val XAI_API_KEY_STORAGE_KEY = "haive.xaiApiKey"
private const val GITHUB_TOKEN_STORAGE_KEY = "haive.githubToken"
private const val GITLAB_TOKEN_STORAGE_KEY = "haive.gitlabToken"

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "webApp") {
        var credentials by remember { mutableStateOf(readWebProviderCredentials()) }
        var repositoryCredentials by remember { mutableStateOf(readWebRepositoryCredentials()) }
        var configuringProviderId by remember { mutableStateOf<String?>(null) }
        var configuringRepositoryServiceId by remember { mutableStateOf<String?>(null) }
        val providers = remember(credentials) { configuredWebProviders(credentials) }
        val executorIntegrations = remember(repositoryCredentials) {
            configuredWebExecutorIntegrations(
                githubToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITHUB_ID),
                gitlabToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITLAB_ID),
            )
        }

        val repositoryServiceId = configuringRepositoryServiceId
        val providerId = configuringProviderId
        when {
            repositoryServiceId != null -> RepositoryCredentialSetup(
                serviceId = repositoryServiceId,
                onSave = { credential ->
                    window.localStorage.setItem(repositoryStorageKey(repositoryServiceId), credential)
                    repositoryCredentials = readWebRepositoryCredentials()
                    configuringRepositoryServiceId = null
                },
                onCancel = { configuringRepositoryServiceId = null },
            )
            providerId != null -> ProviderCredentialSetup(
                providerId = providerId,
                onSave = { key ->
                    window.localStorage.setItem(providerStorageKey(providerId), key)
                    credentials = readWebProviderCredentials()
                    configuringProviderId = null
                },
                onCancel = { configuringProviderId = null },
            )
            else -> App(
                providers = providers,
                executorIntegrations = executorIntegrations,
                connectedRepositoryServiceIds = repositoryCredentials.keys,
                onConfigureRepositoryService = { configuringRepositoryServiceId = it },
                onDisconnectRepositoryService = { serviceId ->
                    window.localStorage.removeItem(repositoryStorageKey(serviceId))
                    repositoryCredentials = readWebRepositoryCredentials()
                },
                onReconfigureProvider = { configuringProviderId = it },
                onDisconnectProvider = { disconnectedProviderId ->
                    window.localStorage.removeItem(providerStorageKey(disconnectedProviderId))
                    credentials = readWebProviderCredentials()
                },
            )
        }
    }
}

internal fun configuredWebProviders(credentials: Map<String, String>): List<AgentProvider> = buildList {
    credentials.cleanKey(ProviderCatalog.JULES_ID)?.let { key ->
        add(
            JulesProvider(
                JulesRestApi(
                    JulesApiKeyProvider { key },
                ),
            ),
        )
    }
    credentials.cleanKey(ProviderCatalog.OPENAI_ID)?.let { key ->
        add(OpenAiProvider(LlmApiKeyProvider { key }))
    }
    credentials.cleanKey(ProviderCatalog.ANTHROPIC_ID)?.let { key ->
        add(AnthropicProvider(LlmApiKeyProvider { key }))
    }
    credentials.cleanKey(ProviderCatalog.GEMINI_ID)?.let { key ->
        add(GeminiProvider(LlmApiKeyProvider { key }))
    }
    credentials.cleanKey(ProviderCatalog.XAI_ID)?.let { key ->
        add(XaiProvider(LlmApiKeyProvider { key }))
    }
}

internal fun configuredWebProviders(julesApiKey: String?): List<AgentProvider> =
    configuredWebProviders(
        julesApiKey
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { mapOf(ProviderCatalog.JULES_ID to it) }
            .orEmpty(),
    )

internal fun configuredWebExecutorIntegrations(
    githubToken: String?,
    gitlabToken: String?,
): TaskExecutorIntegrationRegistry {
    val github = githubToken?.trim()?.takeIf(String::isNotEmpty)
    val gitlab = gitlabToken?.trim()?.takeIf(String::isNotEmpty)
    val repositoryClients = buildList {
        github?.let { token ->
            add(GitHubRestRepositoryOperationClient(RepositoryServiceTokenProvider { token }))
        }
        gitlab?.let { token ->
            add(GitLabRestRepositoryOperationClient(RepositoryServiceTokenProvider { token }))
        }
    }
    if (repositoryClients.isEmpty() && github == null) return TaskExecutorIntegrationRegistry.Empty

    return TaskExecutorIntegrationRegistry(
        buildList {
            if (repositoryClients.isNotEmpty()) {
                add(
                    RepositoryOperationExecutorIntegration(
                        RoutingRepositoryOperationClient(repositoryClients),
                    ),
                )
            }
            github?.let { token ->
                add(
                    GitHubActionsExecutorIntegration(
                        GitHubRestActionsClient(
                            tokenProvider = GitHubTokenProvider { token },
                        ),
                    ),
                )
            }
        },
    )
}

internal fun configuredWebExecutorIntegrations(githubToken: String?): TaskExecutorIntegrationRegistry =
    configuredWebExecutorIntegrations(githubToken, null)

private fun readWebProviderCredentials(): Map<String, String> = buildMap {
    ProviderCatalog.entries.forEach { entry ->
        window.localStorage.getItem(providerStorageKey(entry.id))
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { put(entry.id, it) }
    }
}

private fun readWebRepositoryCredentials(): Map<String, String> = buildMap {
    RepositoryServiceCatalog.entries.forEach { entry ->
        window.localStorage.getItem(repositoryStorageKey(entry.id))
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { put(entry.id, it) }
    }
}

private fun providerStorageKey(providerId: String): String = when (providerId) {
    ProviderCatalog.JULES_ID -> JULES_API_KEY_STORAGE_KEY
    ProviderCatalog.OPENAI_ID -> OPENAI_API_KEY_STORAGE_KEY
    ProviderCatalog.ANTHROPIC_ID -> ANTHROPIC_API_KEY_STORAGE_KEY
    ProviderCatalog.GEMINI_ID -> GEMINI_API_KEY_STORAGE_KEY
    ProviderCatalog.XAI_ID -> XAI_API_KEY_STORAGE_KEY
    else -> error("Unknown provider $providerId")
}

private fun repositoryStorageKey(serviceId: String): String = when (serviceId) {
    RepositoryServiceCatalog.GITHUB_ID -> GITHUB_TOKEN_STORAGE_KEY
    RepositoryServiceCatalog.GITLAB_ID -> GITLAB_TOKEN_STORAGE_KEY
    else -> error("Unknown repository service $serviceId")
}

private fun Map<String, String>.cleanKey(id: String): String? =
    this[id]?.trim()?.takeIf(String::isNotEmpty)
