package com.hereliesaz.haive

import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.geministrator.App
import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.ProviderCredentialSetup
import com.hereliesaz.geministrator.RemoteRepositoryDiscoveryClient
import com.hereliesaz.geministrator.RepositoryCredentialSetup
import com.hereliesaz.geministrator.RepositoryServiceCatalog
import com.hereliesaz.geministrator.domain.AgentProviderId
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
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val repositoryHttpClient by lazy { HttpClient(CIO) }
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

        // Install the process Memory observer before App creates its provider-session gateway.
        memoryRuntime

        setContent {
            var showSplash by remember { mutableStateOf(true) }
            if (showSplash) {
                HaiveSplashScreen(onSplashFinished = { showSplash = false })
            } else {
                var credentials by remember { mutableStateOf(initialCredentials) }
                var repositoryCredentials by remember { mutableStateOf(initialRepositoryCredentials) }
                var configuringProviderId by remember { mutableStateOf<String?>(null) }
                var configuringRepositoryServiceId by remember { mutableStateOf<String?>(null) }
                val providers = remember(credentials, repositoryCredentials) {
                    configuredAndroidProviders(credentials, repositoryCredentials, repositoryHttpClient)
                }
                val executorIntegrations = remember(repositoryCredentials) {
                    configuredAndroidExecutorIntegrations(repositoryCredentials, repositoryHttpClient)
                }
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
                        orchestrationRuntime = orchestrationRuntime,
                        connectedRepositoryServiceIds = repositoryCredentials.keys,
                        onSearchRepositories = repositoryDiscovery::search,
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
    }

    override fun onDestroy() {
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
    LaunchedEffect(Unit) {
        delay(4000)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1026)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.haive_animation1}")
                    setVideoURI(uri)
                    setOnPreparedListener { mediaPlayer ->
                        mediaPlayer.isLooping = true
                        start()
                    }
                    setOnErrorListener { _, _, _ ->
                        onSplashFinished()
                        true
                    }
                }
            },
            modifier = Modifier.size(280.dp)
        )
    }
}

internal fun configuredAndroidProviders(
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
    credentials.cleanKey(ProviderCatalog.GEMINI_ID)?.let { key ->
        val keyProvider = LlmApiKeyProvider { key }
        add(GeminiProvider(keyProvider))
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

private fun Map<String, String>.cleanKey(id: String): String? =
    this[id]?.trim()?.takeIf(String::isNotEmpty)
