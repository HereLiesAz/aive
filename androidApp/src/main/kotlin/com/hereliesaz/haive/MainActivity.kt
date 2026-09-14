package com.hereliesaz.haive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.App
import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.ProviderCredentialSetup
import com.hereliesaz.geministrator.RepositoryCredentialSetup
import com.hereliesaz.geministrator.RepositoryServiceCatalog
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

class MainActivity : ComponentActivity() {
    private val repositoryHttpClient by lazy { HttpClient(CIO) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val providerCredentialStore = AndroidProviderCredentialStore(this)
        val repositoryCredentialStore = AndroidRepositoryCredentialStore(this)
        val initialCredentials = providerCredentialStore.readAll()
        val initialRepositoryCredentials = repositoryCredentialStore.readAll()

        setContent {
            var credentials by remember { mutableStateOf(initialCredentials) }
            var repositoryCredentials by remember { mutableStateOf(initialRepositoryCredentials) }
            var configuringProviderId by remember { mutableStateOf<String?>(null) }
            var configuringRepositoryServiceId by remember { mutableStateOf<String?>(null) }
            val providers = remember(credentials) { configuredAndroidProviders(credentials) }
            val executorIntegrations = remember(repositoryCredentials) {
                configuredAndroidExecutorIntegrations(repositoryCredentials, repositoryHttpClient)
            }

            val repositoryServiceId = configuringRepositoryServiceId
            val providerId = configuringProviderId
            when {
                repositoryServiceId != null -> RepositoryCredentialSetup(
                    serviceId = repositoryServiceId,
                    onSave = { credential ->
                        repositoryCredentialStore.write(repositoryServiceId, credential)
                        repositoryCredentials = repositoryCredentialStore.readAll()
                        configuringRepositoryServiceId = null
                    },
                    onCancel = { configuringRepositoryServiceId = null },
                )
                providerId != null -> ProviderCredentialSetup(
                    providerId = providerId,
                    onSave = { key ->
                        providerCredentialStore.write(providerId, key)
                        credentials = providerCredentialStore.readAll()
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
                        repositoryCredentialStore.clear(serviceId)
                        repositoryCredentials = repositoryCredentialStore.readAll()
                    },
                    onReconfigureProvider = { configuringProviderId = it },
                    onDisconnectProvider = { disconnectedProviderId ->
                        providerCredentialStore.clear(disconnectedProviderId)
                        credentials = providerCredentialStore.readAll()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        repositoryHttpClient.close()
        super.onDestroy()
    }
}

internal fun configuredAndroidProviders(credentials: Map<String, String>): List<AgentProvider> = buildList {
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

internal fun configuredAndroidProviders(julesApiKey: String?): List<AgentProvider> =
    configuredAndroidProviders(
        julesApiKey
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { mapOf(ProviderCatalog.JULES_ID to it) }
            .orEmpty(),
    )

internal fun configuredAndroidExecutorIntegrations(
    repositoryCredentials: Map<String, String>,
    httpClient: HttpClient,
): TaskExecutorIntegrationRegistry {
    val githubToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITHUB_ID)
    val gitlabToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITLAB_ID)
    val repositoryClients = buildList {
        githubToken?.let { token ->
            add(
                GitHubRestRepositoryOperationClient(
                    tokenProvider = RepositoryServiceTokenProvider { token },
                    httpClient = httpClient,
                ),
            )
        }
        gitlabToken?.let { token ->
            add(
                GitLabRestRepositoryOperationClient(
                    tokenProvider = RepositoryServiceTokenProvider { token },
                    httpClient = httpClient,
                ),
            )
        }
    }
    if (repositoryClients.isEmpty() && githubToken == null) return TaskExecutorIntegrationRegistry.Empty

    return TaskExecutorIntegrationRegistry(
        buildList {
            if (repositoryClients.isNotEmpty()) {
                add(
                    RepositoryOperationExecutorIntegration(
                        RoutingRepositoryOperationClient(repositoryClients),
                    ),
                )
            }
            githubToken?.let { token ->
                add(
                    GitHubActionsExecutorIntegration(
                        GitHubRestActionsClient(
                            httpClient = httpClient,
                            tokenProvider = GitHubTokenProvider { token },
                        ),
                    ),
                )
            }
        },
    )
}

private fun Map<String, String>.cleanKey(id: String): String? =
    this[id]?.trim()?.takeIf(String::isNotEmpty)
