package com.hereliesaz.geministrator

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.DistributedComputeUiState
import com.hereliesaz.geministrator.distributed.SettingsDistributedComputeConfigurationStore
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
import com.hereliesaz.geministrator.providers.llm.HostedLlmProviders
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
import java.util.UUID
import javax.swing.JFileChooser
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

fun main() {
    val providerCredentialStore = DesktopProviderCredentialStore()
    val repositoryCredentialStore = DesktopRepositoryCredentialStore()
    val initialProviderCredentials = readDesktopProviderCredentials(providerCredentialStore)
    val initialRepositoryCredentials = readDesktopRepositoryCredentials(repositoryCredentialStore)
    val computeConfigurationStore = SettingsDistributedComputeConfigurationStore()
    val computeCredentialStore = DesktopDistributedComputeCredentialStore()
    var initialComputeConfiguration = computeConfigurationStore.read()
    if (initialComputeConfiguration.nodeId.isBlank() || initialComputeConfiguration.displayName.isBlank()) {
        initialComputeConfiguration = initialComputeConfiguration.copy(
            nodeId = initialComputeConfiguration.nodeId.ifBlank { UUID.randomUUID().toString() },
            displayName = initialComputeConfiguration.displayName.ifBlank {
                val osName = System.getProperty("os.name", "Desktop").trim()
                val user = System.getProperty("user.name", "").trim()
                listOf(user, osName).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Desktop" }
            },
        )
        computeConfigurationStore.write(initialComputeConfiguration)
    }
    val initialComputeToken = computeCredentialStore.readToken()
    val httpClient = HttpClient(CIO)

    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "The Aive",
                state = rememberWindowState(width = 1180.dp, height = 760.dp),
            ) {
                var credentials by remember { mutableStateOf(initialProviderCredentials) }
                var repositoryCredentials by remember { mutableStateOf(initialRepositoryCredentials) }
                var configuringProviderId by remember { mutableStateOf<String?>(null) }
                var configuringRepositoryServiceId by remember { mutableStateOf<String?>(null) }
                var computeConfiguration by remember { mutableStateOf(initialComputeConfiguration) }
                var computeToken by remember { mutableStateOf(initialComputeToken) }
                val providers = remember(credentials, repositoryCredentials) {
                    configuredDesktopProviders(credentials, repositoryCredentials, httpClient)
                }
                val baseExecutorIntegrations = remember(repositoryCredentials) {
                    configuredDesktopExecutorIntegrations(repositoryCredentials, httpClient)
                }
                val computeSession = remember(computeConfiguration, computeToken, baseExecutorIntegrations) {
                    val token = computeToken
                    if (computeConfiguration.configured && token != null) {
                        DesktopDistributedComputeSession(
                            configuration = computeConfiguration,
                            token = token,
                            baseIntegrations = baseExecutorIntegrations,
                            supportedExecutorKinds = desktopDistributedExecutorKinds(repositoryCredentials),
                        )
                    } else {
                        null
                    }
                }
                var computeConnected by remember(computeSession) { mutableStateOf(false) }
                var computeOnlineNodes by remember(computeSession) { mutableStateOf(emptyList<ComputeNodeDescriptor>()) }
                var computeError by remember(computeSession) { mutableStateOf<String?>(null) }
                DisposableEffect(computeSession) {
                    computeSession?.start()
                    onDispose { computeSession?.close() }
                }
                LaunchedEffect(computeSession) {
                    computeConnected = false
                    computeOnlineNodes = emptyList()
                    computeError = null
                    val session = computeSession ?: return@LaunchedEffect
                    launch {
                        session.client.connected.collectLatest { computeConnected = it }
                    }
                    launch {
                        session.client.onlineNodes.collectLatest { nodes ->
                            computeOnlineNodes = nodes.values.toList()
                        }
                    }
                    launch {
                        session.client.errors.collectLatest { error ->
                            computeError = error.message
                        }
                    }
                }
                val executorIntegrations = computeSession?.executorIntegrations ?: baseExecutorIntegrations
                val repositoryDiscovery = remember(repositoryCredentials) {
                    configuredDesktopRepositoryDiscovery(repositoryCredentials, httpClient)
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
                        onSearchRepositories = repositoryDiscovery::search,
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
                        distributedComputeState = DistributedComputeUiState(
                            configuration = computeConfiguration,
                            tokenConfigured = computeToken != null,
                            connected = computeConnected,
                            onlineNodes = computeOnlineNodes,
                            lastError = computeError,
                        ),
                        onSaveDistributedCompute = { configuration, token ->
                            computeConfigurationStore.write(configuration)
                            computeConfiguration = configuration
                            if (token != null) {
                                computeCredentialStore.writeToken(token)
                                computeToken = computeCredentialStore.readToken()
                            }
                        },
                        onDisconnectDistributedCompute = {
                            computeCredentialStore.clear()
                            computeToken = null
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
    addAll(HostedLlmProviders.configured(credentials))
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

internal fun configuredDesktopRepositoryDiscovery(
    repositoryCredentials: Map<String, String>,
    httpClient: HttpClient,
): RemoteRepositoryDiscoveryClient {
    val githubToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITHUB_ID)
    val gitlabToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITLAB_ID)
    return RemoteRepositoryDiscoveryClient(
        httpClient = httpClient,
        githubTokenProvider = githubToken?.let { token -> RepositoryServiceTokenProvider { token } },
        gitlabTokenProvider = gitlabToken?.let { token -> RepositoryServiceTokenProvider { token } },
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
        ProviderCatalog.DEEPSEEK_ID to "DEEPSEEK_API_KEY",
        ProviderCatalog.GROQ_ID to "GROQ_API_KEY",
        ProviderCatalog.CEREBRAS_ID to "CEREBRAS_API_KEY",
        ProviderCatalog.MISTRAL_ID to "MISTRAL_API_KEY",
        ProviderCatalog.HUGGING_FACE_ID to "HF_TOKEN",
        ProviderCatalog.OPENROUTER_ID to "OPENROUTER_API_KEY",
        ProviderCatalog.TOGETHER_ID to "TOGETHER_API_KEY",
        ProviderCatalog.FIREWORKS_ID to "FIREWORKS_API_KEY",
        ProviderCatalog.PERPLEXITY_ID to "PERPLEXITY_API_KEY",
        ProviderCatalog.COHERE_ID to "COHERE_API_KEY",
        ProviderCatalog.NVIDIA_ID to "NVIDIA_API_KEY",
        ProviderCatalog.SAMBANOVA_ID to "SAMBANOVA_API_KEY",
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

private fun desktopDistributedExecutorKinds(
    repositoryCredentials: Map<String, String>,
): Set<String> = buildSet {
    add("repository-operation")
    if (repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITHUB_ID) != null) {
        add("github-action")
    }
}

private fun Map<String, String>.cleanKey(id: String): String? =
    this[id]?.trim()?.takeIf(String::isNotEmpty)
