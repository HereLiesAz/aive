package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.jules.JulesApiKeyProvider
import com.hereliesaz.geministrator.providers.jules.JulesProvider
import com.hereliesaz.geministrator.providers.jules.JulesRestApi
import com.hereliesaz.geministrator.providers.llm.AnthropicMessagesApi
import com.hereliesaz.geministrator.providers.llm.AnthropicProvider
import com.hereliesaz.geministrator.providers.llm.GeminiGenerateContentApi
import com.hereliesaz.geministrator.providers.llm.GeminiProvider
import com.hereliesaz.geministrator.providers.llm.GitLabWorkspaceAgentProvider
import com.hereliesaz.geministrator.providers.llm.GitLabWorkspaceTokenProvider
import com.hereliesaz.geministrator.providers.llm.LlmApiKeyProvider
import com.hereliesaz.geministrator.providers.llm.OpenAiProvider
import com.hereliesaz.geministrator.providers.llm.OpenAiResponsesApi
import com.hereliesaz.geministrator.providers.llm.TextGenerationApi
import com.hereliesaz.geministrator.providers.llm.XaiProvider
import com.hereliesaz.geministrator.providers.llm.XaiResponsesApi
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
import javax.swing.JFileChooser

fun main() {
    val providerCredentialStore = DesktopProviderCredentialStore()
    val repositoryCredentialStore = DesktopRepositoryCredentialStore()
    val initialProviderCredentials = readDesktopProviderCredentials(providerCredentialStore)
    val initialRepositoryCredentials = readDesktopRepositoryCredentials(repositoryCredentialStore)
    val httpClient = HttpClient(CIO)

    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "The Haive",
                state = rememberWindowState(width = 1180.dp, height = 760.dp),
            ) {
                var credentials by remember { mutableStateOf(initialProviderCredentials) }
                var repositoryCredentials by remember { mutableStateOf(initialRepositoryCredentials) }
                var configuringProviderId by remember { mutableStateOf<String?>(null) }
                var configuringRepositoryServiceId by remember { mutableStateOf<String?>(null) }
                val providers = remember(credentials, repositoryCredentials) {
                    configuredDesktopProviders(credentials, repositoryCredentials, httpClient)
                }
                val executorIntegrations = remember(repositoryCredentials) {
                    configuredDesktopExecutorIntegrations(repositoryCredentials, httpClient)
                }

                val repositoryServiceId = configuringRepositoryServiceId
                val providerId = configuringProviderId
                when {
                    repositoryServiceId != null -> RepositoryCredentialSetup(
                        serviceId = repositoryServiceId,
                        onSave = { credential ->
                            repositoryCredentialStore.write(repositoryServiceId, credential)
                            repositoryCredentials = readDesktopRepositoryCredentials(repositoryCredentialStore)
                            configuringRepositoryServiceId = null
                        },
                        onCancel = { configuringRepositoryServiceId = null },
                    )
                    providerId != null -> ProviderCredentialSetup(
                        providerId = providerId,
                        onSave = { key ->
                            providerCredentialStore.write(providerId, key)
                            credentials = readDesktopProviderCredentials(providerCredentialStore)
                            configuringProviderId = null
                        },
                        onCancel = { configuringProviderId = null },
                    )
                    else -> App(
                        providers = providers,
                        executorIntegrations = executorIntegrations,
                        availableRepositorySources = RepositorySource.entries.toSet(),
                        onPickLocalRepository = ::pickLocalGitFolder,
                        connectedRepositoryServiceIds = repositoryCredentials.keys,
                        onConfigureRepositoryService = { configuringRepositoryServiceId = it },
                        onDisconnectRepositoryService = { serviceId ->
                            repositoryCredentialStore.clear(serviceId)
                            repositoryCredentials = readDesktopRepositoryCredentials(repositoryCredentialStore)
                        },
                        onReconfigureProvider = { configuringProviderId = it },
                        onDisconnectProvider = { disconnectedProviderId ->
                            providerCredentialStore.clear(disconnectedProviderId)
                            credentials = readDesktopProviderCredentials(providerCredentialStore)
                        },
                    )
                }
            }
        }
    } finally {
        httpClient.close()
    }
}

internal fun configuredDesktopProviders(
    credentials: Map<String, String>,
    repositoryCredentials: Map<String, String> = emptyMap(),
    repositoryHttpClient: HttpClient? = null,
): List<AgentProvider> = buildList {
    val gitlabToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITLAB_ID)
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
        val keyProvider = LlmApiKeyProvider { key }
        add(OpenAiProvider(keyProvider))
        add(
            LocalWorkspaceAgentProvider(
                id = AgentProviderId("openai-local-workspace"),
                displayName = "OpenAI / Local Workspace",
                api = OpenAiResponsesApi(keyProvider),
            ),
        )
        addGitLabWorkspaceProvider(
            providerId = "openai-gitlab-workspace",
            displayName = "OpenAI / GitLab Workspace",
            api = OpenAiResponsesApi(keyProvider),
            gitlabToken = gitlabToken,
            httpClient = repositoryHttpClient,
        )
    }
    credentials.cleanKey(ProviderCatalog.ANTHROPIC_ID)?.let { key ->
        val keyProvider = LlmApiKeyProvider { key }
        add(AnthropicProvider(keyProvider))
        add(
            LocalWorkspaceAgentProvider(
                id = AgentProviderId("anthropic-local-workspace"),
                displayName = "Claude / Local Workspace",
                api = AnthropicMessagesApi(keyProvider),
            ),
        )
        addGitLabWorkspaceProvider(
            providerId = "anthropic-gitlab-workspace",
            displayName = "Claude / GitLab Workspace",
            api = AnthropicMessagesApi(keyProvider),
            gitlabToken = gitlabToken,
            httpClient = repositoryHttpClient,
        )
    }
    credentials.cleanKey(ProviderCatalog.GEMINI_ID)?.let { key ->
        val keyProvider = LlmApiKeyProvider { key }
        add(GeminiProvider(keyProvider))
        add(
            LocalWorkspaceAgentProvider(
                id = AgentProviderId("gemini-local-workspace"),
                displayName = "Gemini / Local Workspace",
                api = GeminiGenerateContentApi(keyProvider),
            ),
        )
        addGitLabWorkspaceProvider(
            providerId = "gemini-gitlab-workspace",
            displayName = "Gemini / GitLab Workspace",
            api = GeminiGenerateContentApi(keyProvider),
            gitlabToken = gitlabToken,
            httpClient = repositoryHttpClient,
        )
    }
    credentials.cleanKey(ProviderCatalog.XAI_ID)?.let { key ->
        val keyProvider = LlmApiKeyProvider { key }
        add(XaiProvider(keyProvider))
        add(
            LocalWorkspaceAgentProvider(
                id = AgentProviderId("xai-local-workspace"),
                displayName = "Grok / Local Workspace",
                api = XaiResponsesApi(keyProvider),
            ),
        )
        addGitLabWorkspaceProvider(
            providerId = "xai-gitlab-workspace",
            displayName = "Grok / GitLab Workspace",
            api = XaiResponsesApi(keyProvider),
            gitlabToken = gitlabToken,
            httpClient = repositoryHttpClient,
        )
    }
}

private fun MutableList<AgentProvider>.addGitLabWorkspaceProvider(
    providerId: String,
    displayName: String,
    api: TextGenerationApi,
    gitlabToken: String?,
    httpClient: HttpClient?,
) {
    if (gitlabToken == null || httpClient == null) return
    add(
        GitLabWorkspaceAgentProvider(
            id = AgentProviderId(providerId),
            displayName = displayName,
            api = api,
            tokenProvider = GitLabWorkspaceTokenProvider { gitlabToken },
            httpClient = httpClient,
        ),
    )
}

internal fun configuredDesktopExecutorIntegrations(
    repositoryCredentials: Map<String, String>,
    httpClient: HttpClient,
): TaskExecutorIntegrationRegistry {
    val githubToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITHUB_ID)
    val gitlabToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITLAB_ID)
    val repositoryClients = buildList {
        add(LocalGitRepositoryOperationClient())
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
    return TaskExecutorIntegrationRegistry(
        buildList {
            add(
                RepositoryOperationExecutorIntegration(
                    RoutingRepositoryOperationClient(repositoryClients),
                ),
            )
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

private fun readDesktopProviderCredentials(store: DesktopProviderCredentialStore): Map<String, String> = buildMap {
    environmentProviderCredentials().forEach { (providerId, key) -> put(providerId, key) }
    store.readAll().forEach { (providerId, key) -> put(providerId, key) }
}

private fun readDesktopRepositoryCredentials(store: DesktopRepositoryCredentialStore): Map<String, String> = buildMap {
    environmentRepositoryCredentials().forEach { (serviceId, credential) -> put(serviceId, credential) }
    store.readAll().forEach { (serviceId, credential) -> put(serviceId, credential) }
}

private fun environmentProviderCredentials(): Map<String, String> = buildMap {
    listOf(
        ProviderCatalog.JULES_ID to "JULES_API_KEY",
        ProviderCatalog.OPENAI_ID to "OPENAI_API_KEY",
        ProviderCatalog.ANTHROPIC_ID to "ANTHROPIC_API_KEY",
        ProviderCatalog.GEMINI_ID to "GEMINI_API_KEY",
        ProviderCatalog.XAI_ID to "XAI_API_KEY",
    ).forEach { (providerId, environmentName) ->
        System.getenv(environmentName)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { put(providerId, it) }
    }
}

private fun environmentRepositoryCredentials(): Map<String, String> = buildMap {
    listOf(
        RepositoryServiceCatalog.GITHUB_ID to "GITHUB_TOKEN",
        RepositoryServiceCatalog.GITLAB_ID to "GITLAB_TOKEN",
    ).forEach { (serviceId, environmentName) ->
        System.getenv(environmentName)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { put(serviceId, it) }
    }
}

private fun pickLocalGitFolder(): String? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Link local Git repository"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile?.absolutePath ?: return null

    return runCatching {
        val process = ProcessBuilder(
            "git",
            "-C",
            selected,
            "rev-parse",
            "--show-toplevel",
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0) output.lineSequence().lastOrNull()?.trim()?.takeIf(String::isNotEmpty) else null
    }.getOrNull()
}

private fun Map<String, String>.cleanKey(id: String): String? =
    this[id]?.trim()?.takeIf(String::isNotEmpty)
