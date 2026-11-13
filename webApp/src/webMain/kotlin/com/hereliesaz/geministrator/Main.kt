package com.hereliesaz.geministrator

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.llm.AnthropicMessagesApi
import com.hereliesaz.geministrator.providers.llm.AnthropicProvider
import com.hereliesaz.geministrator.providers.llm.GeminiGenerateContentApi
import com.hereliesaz.geministrator.providers.llm.GeminiProvider
import com.hereliesaz.geministrator.providers.llm.GitLabWorkspaceAgentProvider
import com.hereliesaz.geministrator.providers.llm.GitLabWorkspaceTokenProvider
import com.hereliesaz.geministrator.providers.llm.HostedLlmProviders
import com.hereliesaz.geministrator.providers.llm.LlmApiKeyProvider
import com.hereliesaz.geministrator.providers.llm.NodeCharacterImagePipelineExecutorIntegration
import com.hereliesaz.geministrator.providers.llm.StoreNodeCharacterRigPipelineExecutorIntegration
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
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import com.hereliesaz.geministrator.memory.HostedMemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore
import com.hereliesaz.geministrator.memory.MemoryMicroAgentPlatform
import com.hereliesaz.geministrator.memory.deferredMemoryBanks
import com.hereliesaz.geministrator.memory.openWebMemoryBanks
import com.hereliesaz.geministrator.memory.WebMemoryDebugHook
import com.hereliesaz.geministrator.providers.llm.memoryTextApi

private const val OPENAI_API_KEY_STORAGE_KEY = "haive.openaiApiKey"
private const val ANTHROPIC_API_KEY_STORAGE_KEY = "haive.anthropicApiKey"
private const val GEMINI_API_KEY_STORAGE_KEY = "haive.geminiApiKey"
private const val XAI_API_KEY_STORAGE_KEY = "haive.xaiApiKey"
private const val GITHUB_TOKEN_STORAGE_KEY = "haive.githubToken"
private const val GITLAB_TOKEN_STORAGE_KEY = "haive.gitlabToken"

/** True when the browser reports `prefers-reduced-motion: reduce`. */
private fun prefersReducedMotion(): Boolean =
    js("typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    AzphaltMotion.platformReduceMotion = shouldReduceMotion(animatorDurationScale = null, systemPrefersReduced = prefersReducedMotion())
    // Memory in SQLite persisted in OPFS (opened in a worker; the old Settings log is imported once).
    // Programmatic stages by default; attached before App builds the runtime.
    val memoryEngines = HostedMemoryEngineProvider(MemoryMicroAgentPlatform.Web)
    val memoryScope = MainScope()
    // One database per workflow run's memory bank; lineage and projects in Settings.
    val memoryLineage = com.hereliesaz.geministrator.memory.MemoryLineage.createDefault()
    // Dev-only: null unless on localhost with ?aiveMemoryDebug=… (see WebMemoryDebugHook).
    val memoryDebug = WebMemoryDebugHook.fromLocation(window.location.hostname, window.location.search)
    val memoryBanks = memoryScope.async {
        memoryDebug?.beforeBanksOpen()
        openWebMemoryBanks(memoryLineage) { reason -> println("Aive memory: SQLite unavailable ($reason)") }
            .also { banks -> memoryDebug?.afterBanksOpen(banks) }
    }
    val memoryLayer = MemoryLayerController(
        banks = deferredMemoryBanks(memoryBanks),
        settingsStore = MemoryLayerSettingsStore.createDefault(),
        lineage = memoryLineage,
        procedureCatalog = com.hereliesaz.geministrator.memory.procedural.webProcedureCatalog(memoryLineage),
        engineProvider = memoryEngines,
        scope = memoryScope,
    ).also { it.attach() }
    ComposeViewport(viewportContainerId = "webApp") {
        if (window.location.search.contains("terrariumPreview=1")) {
            TerrariumVisualProofScreen()
            return@ComposeViewport
        }
        if (window.location.search.contains("creatureRigs=1")) {
            CreatureRigGalleryScreen()
            return@ComposeViewport
        }

        var credentials by remember { mutableStateOf(emptyMap<String, String>()) }
        var repositoryCredentials by remember { mutableStateOf(emptyMap<String, String>()) }
        var credentialsLoaded by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val freeProviderWrites = remember { kotlinx.coroutines.sync.Mutex() }
        // Credentials are encrypted at rest, so they load asynchronously before the app starts.
        LaunchedEffect(Unit) {
            // Keyless sources start on, once each: a source the keeper disconnects later stays off.
            KeylessDefaults.pending(readWebProviderCredentials().keys).let { plan ->
                plan.connect.forEach { id ->
                    runCatching { WebCredentialStore.write(providerStorageKey(id), ProviderCatalog.ANONYMOUS_CREDENTIAL) }
                }
                KeylessDefaults.commit(plan)
            }
            credentials = readWebProviderCredentials()
            repositoryCredentials = readWebRepositoryCredentials()
            credentialsLoaded = true
        }
        LaunchedEffect(credentials) {
            memoryEngines.hostedTextGenerator = { providerId, model, prompt ->
                val api = memoryTextApi(credentials, providerId, model)
                    ?: error("Memory provider ${providerId ?: "(default)"} is not configured")
                api.generate(prompt).text
            }
        }
        var configuringProviderId by remember { mutableStateOf<String?>(null) }
        var configuringRepositoryServiceId by remember { mutableStateOf<String?>(null) }
        val repositoryHttpClient = remember { HttpClient() }
        DisposableEffect(Unit) {
            onDispose { repositoryHttpClient.close() }
        }
        // MCP servers the keeper connects (docs/architecture/MCP_HOST.md): Streamable HTTP only, and
        // only servers whose CORS policy admits this origin.
        val mcpConsent = remember { com.hereliesaz.geministrator.mcp.PromptingMcpConsentGate() }
        val mcpElicitation = remember { com.hereliesaz.geministrator.mcp.PromptingMcpElicitationGate() }
        val mcpHost = remember {
            com.hereliesaz.geministrator.mcp.McpHost(
                store = com.hereliesaz.geministrator.mcp.McpServerStore(),
                vault = WebMcpCredentialStore,
                transports = com.hereliesaz.geministrator.mcp.DefaultMcpTransportFactory(repositoryHttpClient),
                consent = mcpConsent,
                elicitation = mcpElicitation,
            )
        }
        val providers = remember(credentials, repositoryCredentials) {
            configuredWebProviders(credentials, repositoryCredentials, mcpHost)
        }
        val executorIntegrations = remember(repositoryCredentials, credentials) {
            val configured = configuredWebExecutorIntegrations(
                githubToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITHUB_ID),
                gitlabToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITLAB_ID),
            )
            credentials.cleanKey(ProviderCatalog.OPENAI_ID)?.let { key ->
                configured
                    .withPriorityIntegration(
                        NodeCharacterImagePipelineExecutorIntegration(
                            apiKeyProvider = LlmApiKeyProvider { key },
                        ),
                    )
                    .withPriorityIntegration(
                        StoreNodeCharacterRigPipelineExecutorIntegration(
                            apiKeyProvider = LlmApiKeyProvider { key },
                        ),
                    )
            } ?: configured
        }
        val repositoryDiscovery = remember(repositoryCredentials) {
            configuredWebRepositoryDiscovery(repositoryCredentials, repositoryHttpClient)
        }

        if (!credentialsLoaded) return@ComposeViewport
        val repositoryServiceId = configuringRepositoryServiceId
        val providerId = configuringProviderId
        when {
            repositoryServiceId != null -> RepositoryCredentialSetup(
                serviceId = repositoryServiceId,
                onSave = { credential ->
                    configuringRepositoryServiceId = null
                    scope.launch {
                        WebCredentialStore.write(repositoryStorageKey(repositoryServiceId), credential)
                        repositoryCredentials = readWebRepositoryCredentials()
                    }
                },
                onCancel = { configuringRepositoryServiceId = null },
            )
            providerId != null -> ProviderCredentialSetup(
                providerId = providerId,
                onSave = { key ->
                    configuringProviderId = null
                    scope.launch {
                        WebCredentialStore.write(providerStorageKey(providerId), key)
                        credentials = readWebProviderCredentials()
                    }
                },
                onCancel = { configuringProviderId = null },
            )
            else -> App(
                providers = providers,
                mcpHost = mcpHost,
                mcpConsent = mcpConsent,
                mcpElicitation = mcpElicitation,
                executorIntegrations = executorIntegrations,
                connectedRepositoryServiceIds = repositoryCredentials.keys,
                onSearchRepositories = repositoryDiscovery::search,
                onConfigureRepositoryService = { configuringRepositoryServiceId = it },
                onDisconnectRepositoryService = { serviceId ->
                    WebCredentialStore.remove(repositoryStorageKey(serviceId))
                    repositoryCredentials = repositoryCredentials - serviceId
                },
                onReconfigureProvider = { configuringProviderId = it },
                onConnectFreeProvider = { freeProviderId ->
                    // One at a time: the keyless switch connects several, and each re-reads what is stored.
                    scope.launch {
                        freeProviderWrites.withLock {
                            WebCredentialStore.write(providerStorageKey(freeProviderId), ProviderCatalog.ANONYMOUS_CREDENTIAL)
                            credentials = readWebProviderCredentials()
                        }
                    }
                },
                onDisconnectProvider = { disconnectedProviderId ->
                    WebCredentialStore.remove(providerStorageKey(disconnectedProviderId))
                    credentials = credentials - disconnectedProviderId
                },
                memoryLayer = memoryLayer,
                // The browser has no way to pair or run a compute node: hide the Compute part rather than
                // show a Save & connect that does nothing.
                distributedComputeAvailable = false,
            )
        }
    }
}

internal fun configuredWebProviders(
    credentials: Map<String, String>,
    repositoryCredentials: Map<String, String> = emptyMap(),
    /** MCP tools for the text providers; workspace agents get none. */
    mcpTools: com.hereliesaz.geministrator.mcp.McpToolSource? = null,
): List<AgentProvider> = buildList {
    val gitlabToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITLAB_ID)
    addAll(HostedLlmProviders.configured(credentials, mcpTools))
    credentials.cleanKey(ProviderCatalog.OPENAI_ID)?.let { key ->
        val keyProvider = LlmApiKeyProvider { key }
        add(OpenAiProvider(keyProvider, tools = mcpTools))
        addGitLabWorkspaceProvider(
            providerId = "openai-gitlab-workspace",
            displayName = "OpenAI / GitLab Workspace",
            api = OpenAiResponsesApi(keyProvider),
            gitlabToken = gitlabToken,
        )
    }
    credentials.cleanKey(ProviderCatalog.ANTHROPIC_ID)?.let { key ->
        val keyProvider = LlmApiKeyProvider { key }
        add(AnthropicProvider(keyProvider, tools = mcpTools))
        addGitLabWorkspaceProvider(
            providerId = "anthropic-gitlab-workspace",
            displayName = "Claude / GitLab Workspace",
            api = AnthropicMessagesApi(keyProvider),
            gitlabToken = gitlabToken,
        )
    }
    credentials.cleanKey(ProviderCatalog.GEMINI_ID)?.let { key ->
        val keyProvider = LlmApiKeyProvider { key }
        add(GeminiProvider(keyProvider, tools = mcpTools))
        addGitLabWorkspaceProvider(
            providerId = "gemini-gitlab-workspace",
            displayName = "Gemini / GitLab Workspace",
            api = GeminiGenerateContentApi(keyProvider),
            gitlabToken = gitlabToken,
        )
    }
    credentials.cleanKey(ProviderCatalog.XAI_ID)?.let { key ->
        val keyProvider = LlmApiKeyProvider { key }
        add(XaiProvider(keyProvider, tools = mcpTools))
        addGitLabWorkspaceProvider(
            providerId = "xai-gitlab-workspace",
            displayName = "Grok / GitLab Workspace",
            api = XaiResponsesApi(keyProvider),
            gitlabToken = gitlabToken,
        )
    }
}

private fun MutableList<AgentProvider>.addGitLabWorkspaceProvider(
    providerId: String,
    displayName: String,
    api: TextGenerationApi,
    gitlabToken: String?,
) {
    if (gitlabToken == null) return
    add(
        GitLabWorkspaceAgentProvider(
            id = AgentProviderId(providerId),
            displayName = displayName,
            api = api,
            tokenProvider = GitLabWorkspaceTokenProvider { gitlabToken },
        ),
    )
}

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

internal fun configuredWebRepositoryDiscovery(
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

private suspend fun readWebProviderCredentials(): Map<String, String> = buildMap {
    // Providers the app no longer supports leave no credential behind ("haive.julesApiKey" included).
    ProviderCatalog.RETIRED_PROVIDER_IDS.forEach { WebCredentialStore.remove(providerStorageKey(it)) }
    ProviderCatalog.entries.forEach { entry ->
        WebCredentialStore.read(providerStorageKey(entry.id))?.let { put(entry.id, it) }
    }
}

private suspend fun readWebRepositoryCredentials(): Map<String, String> = buildMap {
    RepositoryServiceCatalog.entries.forEach { entry ->
        WebCredentialStore.read(repositoryStorageKey(entry.id))?.let { put(entry.id, it) }
    }
}

private fun providerStorageKey(providerId: String): String = when (providerId) {
    ProviderCatalog.OPENAI_ID -> OPENAI_API_KEY_STORAGE_KEY
    ProviderCatalog.ANTHROPIC_ID -> ANTHROPIC_API_KEY_STORAGE_KEY
    ProviderCatalog.GEMINI_ID -> GEMINI_API_KEY_STORAGE_KEY
    ProviderCatalog.XAI_ID -> XAI_API_KEY_STORAGE_KEY
    else -> "haive.${providerId}ApiKey"
}

private fun repositoryStorageKey(serviceId: String): String = when (serviceId) {
    RepositoryServiceCatalog.GITHUB_ID -> GITHUB_TOKEN_STORAGE_KEY
    RepositoryServiceCatalog.GITLAB_ID -> GITLAB_TOKEN_STORAGE_KEY
    else -> "haive.${serviceId}Token"
}

private fun Map<String, String>.cleanKey(id: String): String? =
    this[id]?.trim()?.takeIf(String::isNotEmpty)

/** MCP server tokens in the same sealed localStorage vault as the other connection secrets. */
private object WebMcpCredentialStore : com.hereliesaz.geministrator.mcp.McpCredentialVault {
    private fun key(serverId: String) = "aive.mcp.token.$serverId"

    override suspend fun read(serverId: String): String? = WebCredentialStore.read(key(serverId))

    override suspend fun write(serverId: String, token: String) = WebCredentialStore.write(key(serverId), token)

    override suspend fun clear(serverId: String) = WebCredentialStore.remove(key(serverId))
}
