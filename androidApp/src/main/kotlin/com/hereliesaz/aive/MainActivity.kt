package com.hereliesaz.aive

import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.App
import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.ProviderCredentialSetup
import com.hereliesaz.geministrator.RemoteRepositoryDiscoveryClient
import com.hereliesaz.geministrator.RepositoryCredentialSetup
import com.hereliesaz.geministrator.RepositoryServiceCatalog
import com.hereliesaz.geministrator.domain.AgentProviderId
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
import com.hereliesaz.geministrator.providers.llm.TextLlmProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val repositoryHttpClient by lazy { HttpClient(CIO) }
    private val azphaltHost by lazy { AndroidAzphaltHost(this, repositoryHttpClient) }
    private var installedGeminiReady by mutableStateOf(false)
    private val memoryRuntimeDelegate = lazy {
        AndroidMemoryLayerRuntime(
            context = this,
            httpClient = repositoryHttpClient,
            cacheDirectory = cacheDir,
        )
    }
    private val memoryRuntime by memoryRuntimeDelegate
    private val orchestrationRuntimeDelegate = lazy {
        AndroidOrchestrationAgentRuntime(
            installer = AndroidOrchestrationModelInstaller(this, repositoryHttpClient),
            cacheDirectory = cacheDir,
        )
    }
    private val orchestrationRuntime by orchestrationRuntimeDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val providerCredentialStore = AndroidProviderCredentialStore(this)
        val repositoryCredentialStore = AndroidRepositoryCredentialStore(this)
        val initialCredentials = providerCredentialStore.readAll()
        val initialRepositoryCredentials = repositoryCredentialStore.readAll()
        val computeConfigurationStore = SettingsDistributedComputeConfigurationStore()
        val computeCredentialStore = AndroidDistributedComputeCredentialStore(this)
        var initialComputeConfiguration = computeConfigurationStore.read()
        if (initialComputeConfiguration.nodeId.isBlank() || initialComputeConfiguration.displayName.isBlank()) {
            initialComputeConfiguration = initialComputeConfiguration.copy(
                nodeId = initialComputeConfiguration.nodeId.ifBlank { UUID.randomUUID().toString() },
                displayName = initialComputeConfiguration.displayName.ifBlank {
                    listOf(Build.MANUFACTURER, Build.MODEL)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                        .ifBlank { "Android device" }
                },
            )
            computeConfigurationStore.write(initialComputeConfiguration)
        }
        val initialComputeToken = computeCredentialStore.readToken()
        azphaltHost.handleIntent(intent)

        setContent {
            var splashFinished by remember { mutableStateOf(false) }
            var startupReady by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    memoryRuntime
                }
                startupReady = true
            }

            if (!splashFinished || !startupReady) {
                HaiveSplashScreen(onSplashFinished = { splashFinished = true })
            } else {
                var credentials by remember { mutableStateOf(initialCredentials) }
                var repositoryCredentials by remember { mutableStateOf(initialRepositoryCredentials) }
                var configuringProviderId by remember { mutableStateOf<String?>(null) }
                var configuringGeminiApiKey by remember { mutableStateOf(false) }
                var configuringRepositoryServiceId by remember { mutableStateOf<String?>(null) }
                var computeConfiguration by remember { mutableStateOf(initialComputeConfiguration) }
                var computeToken by remember { mutableStateOf(initialComputeToken) }

                val installedGeminiApi = remember(credentials, installedGeminiReady) {
                    if (!installedGeminiReady) {
                        null
                    } else {
                        val fallback = credentials.cleanKey(ProviderCatalog.GEMINI_ID)?.let { key ->
                            GeminiGenerateContentApi(LlmApiKeyProvider { key })
                        }
                        InstalledGeminiTextGenerationApi(this, fallback)
                    }
                }
                val providers = remember(
                    credentials,
                    repositoryCredentials,
                    installedGeminiApi,
                ) {
                    configuredAndroidProviders(
                        credentials = credentials,
                        repositoryCredentials = repositoryCredentials,
                        repositoryHttpClient = repositoryHttpClient,
                        installedGeminiApi = installedGeminiApi,
                    )
                }
                val baseExecutorIntegrations = remember(repositoryCredentials) {
                    configuredAndroidExecutorIntegrations(repositoryCredentials, repositoryHttpClient)
                }
                val computeSession = remember(computeConfiguration, computeToken, baseExecutorIntegrations) {
                    val token = computeToken
                    if (computeConfiguration.configured && token != null) {
                        AndroidDistributedComputeSession(
                            context = this,
                            configuration = computeConfiguration,
                            token = token,
                            baseIntegrations = baseExecutorIntegrations,
                            supportedExecutorKinds = androidDistributedExecutorKinds(repositoryCredentials),
                        )
                    } else {
                        null
                    }
                }
                var computeConnected by remember(computeSession) { mutableStateOf(false) }
                var computeOnlineNodes by remember(computeSession) {
                    mutableStateOf(emptyList<com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor>())
                }
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
                    configuredAndroidRepositoryDiscovery(repositoryCredentials, repositoryHttpClient)
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
                    providerId == ProviderCatalog.GEMINI_ID && !configuringGeminiApiKey -> AndroidGeminiProviderSetup(
                        installedBridgeSupported = InstalledGeminiPreference.isSupported,
                        installedAppDetected = InstalledGeminiTextGenerationApi.isGeminiInstalled(this),
                        accessibilityEnabled = InstalledGeminiTextGenerationApi.isAccessibilityServiceEnabled(this),
                        onUseInstalledGemini = {
                            InstalledGeminiPreference.setEnabled(this, true)
                            installedGeminiReady = InstalledGeminiTextGenerationApi.isAvailable(this)
                            configuringProviderId = null
                            if (!installedGeminiReady) {
                                InstalledGeminiPreference.requestEnable(this)
                            }
                        },
                        onUseApiKey = { configuringGeminiApiKey = true },
                        onCancel = {
                            configuringGeminiApiKey = false
                            configuringProviderId = null
                        },
                    )
                    providerId != null -> ProviderCredentialSetup(
                        providerId = providerId,
                        onSave = { key ->
                            providerCredentialStore.write(providerId, key)
                            credentials = providerCredentialStore.readAll()
                            configuringGeminiApiKey = false
                            configuringProviderId = null
                        },
                        onCancel = {
                            configuringGeminiApiKey = false
                            configuringProviderId = null
                        },
                    )
                    else -> App(
                        providers = providers,
                        executorIntegrations = executorIntegrations,
                        orchestrationRuntime = orchestrationRuntime,
                        persistence = azphaltHost.persistence,
                        azphaltStoreService = azphaltHost.service,
                        azphaltPackageImportRequest = azphaltHost.importRequest,
                        onAzphaltPackageImportHandled = azphaltHost::consumeImport,
                        connectedRepositoryServiceIds = repositoryCredentials.keys,
                        onSearchRepositories = repositoryDiscovery::search,
                        onConfigureRepositoryService = { configuringRepositoryServiceId = it },
                        onDisconnectRepositoryService = { serviceId ->
                            repositoryCredentialStore.clear(serviceId)
                            repositoryCredentials = repositoryCredentialStore.readAll()
                        },
                        onReconfigureProvider = { id ->
                            configuringGeminiApiKey = false
                            configuringProviderId = id
                        },
                        onDisconnectProvider = { disconnectedProviderId ->
                            if (disconnectedProviderId == ProviderCatalog.GEMINI_ID) {
                                InstalledGeminiPreference.setEnabled(this, false)
                                installedGeminiReady = false
                            }
                            providerCredentialStore.clear(disconnectedProviderId)
                            credentials = providerCredentialStore.readAll()
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        azphaltHost.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        installedGeminiReady = InstalledGeminiPreference.isEnabled(this) &&
            InstalledGeminiTextGenerationApi.isAvailable(this)
    }

    override fun onDestroy() {
        azphaltHost.close()
        if (orchestrationRuntimeDelegate.isInitialized()) {
            orchestrationRuntime.close()
        }
        if (memoryRuntimeDelegate.isInitialized()) {
            memoryRuntime.close()
        }
        repositoryHttpClient.close()
        super.onDestroy()
    }
}

@Composable
fun HaiveSplashScreen(onSplashFinished: () -> Unit) {
    var animationStarted by remember { mutableStateOf(false) }

    LaunchedEffect(animationStarted) {
        if (animationStarted) {
            delay(4000)
            onSplashFinished()
        }
    }
    LaunchedEffect(Unit) {
        delay(6000)
        if (!animationStarted) onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1026)),
        contentAlignment = Alignment.Center,
    ) {
        AiveLoadingAnimation(
            modifier = Modifier.size(280.dp),
            onAnimationStarted = { animationStarted = true },
        )
    }
}

internal fun configuredAndroidProviders(
    credentials: Map<String, String>,
    repositoryCredentials: Map<String, String> = emptyMap(),
    repositoryHttpClient: HttpClient? = null,
    installedGeminiApi: TextGenerationApi? = null,
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
        addGitLabWorkspaceProvider(
            providerId = "anthropic-gitlab-workspace",
            displayName = "Claude / GitLab Workspace",
            api = AnthropicMessagesApi(keyProvider),
            gitlabToken = gitlabToken,
            httpClient = repositoryHttpClient,
        )
    }

    val geminiKey = credentials.cleanKey(ProviderCatalog.GEMINI_ID)
    if (installedGeminiApi != null) {
        add(
            TextLlmProvider(
                id = AgentProviderId(ProviderCatalog.GEMINI_ID),
                displayName = "Gemini",
                api = installedGeminiApi,
            ),
        )
    } else if (geminiKey != null) {
        add(GeminiProvider(LlmApiKeyProvider { geminiKey }))
    }
    geminiKey?.let { key ->
        val keyProvider = LlmApiKeyProvider { key }
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

internal fun configuredAndroidRepositoryDiscovery(
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

private fun androidDistributedExecutorKinds(
    repositoryCredentials: Map<String, String>,
): Set<String> = buildSet {
    if (repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITHUB_ID) != null) {
        add("github-action")
        add("repository-operation")
    }
    if (repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITLAB_ID) != null) {
        add("repository-operation")
    }
}

private fun Map<String, String>.cleanKey(id: String): String? =
    this[id]?.trim()?.takeIf(String::isNotEmpty)
