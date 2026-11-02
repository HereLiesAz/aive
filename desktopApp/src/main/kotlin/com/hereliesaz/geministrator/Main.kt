package com.hereliesaz.geministrator

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.hereliesaz.geministrator.providers.onDeviceTextProviderOrNull
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
import com.hereliesaz.geministrator.workflow.GitHubRestOpenCodeRunnerClient
import com.hereliesaz.geministrator.workflow.GitHubRestRepositoryOperationClient
import com.hereliesaz.geministrator.workflow.GitHubTokenProvider
import com.hereliesaz.geministrator.workflow.OpenCodeActionsAgentProvider
import com.hereliesaz.geministrator.workflow.GitLabRestRepositoryOperationClient
import com.hereliesaz.geministrator.workflow.RepositoryOperationExecutorIntegration
import com.hereliesaz.geministrator.workflow.RepositoryServiceTokenProvider
import com.hereliesaz.geministrator.workflow.RoutingRepositoryOperationClient
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import com.hereliesaz.geministrator.workflow.TestRunnerExecutorIntegration
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.hereliesaz.geministrator.orchestration.OrchestrationQuestion
import com.hereliesaz.geministrator.orchestration.PreferLocalOrchestrationAgentRuntime
import com.hereliesaz.geministrator.providers.llm.TextGenerationOrchestrationAgentRuntime
import com.hereliesaz.geministrator.providers.llm.configuredPlanningApi
import com.hereliesaz.geministrator.providers.llm.memoryTextApi
import com.hereliesaz.geministrator.memory.DesktopOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.HostedMemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore
import com.hereliesaz.geministrator.memory.MemoryMicroAgentPlatform
import com.hereliesaz.geministrator.memory.desktopSqlMemoryBanks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

fun main() {
    // File choosers are Swing; without this they draw in Swing's own Metal theme instead of the
    // operating system's, which clashes with both the app and the desktop.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    AzphaltMotion.platformReduceMotion = shouldReduceMotion(
        animatorDurationScale = null,
        systemPrefersReduced = desktopPrefersReducedMotion(),
    )
    val providerCredentialStore = DesktopProviderCredentialStore()
    val projectFileService = DesktopProjectFileService()
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
    // The compute node's signing key, read from (or created in) the OS credential store once.
    val computeNodeSigner = lazy {
        runCatching {
            DesktopComputeNodeSigner.loadOrCreate(DesktopDistributedComputeCredentialStore.forNodeIdentity())
        }.getOrNull()
    }
    val httpClient = HttpClient(CIO)
    // MCP servers the keeper connects (docs/architecture/MCP_HOST.md); local ones run as processes.
    val mcpConsent = com.hereliesaz.geministrator.mcp.PromptingMcpConsentGate()
    val mcpElicitation = com.hereliesaz.geministrator.mcp.PromptingMcpElicitationGate()
    val mcpHost = com.hereliesaz.geministrator.mcp.McpHost(
        store = com.hereliesaz.geministrator.mcp.McpServerStore(),
        vault = DesktopMcpCredentialStore(),
        transports = com.hereliesaz.geministrator.mcp.DefaultMcpTransportFactory(
            httpClient,
            com.hereliesaz.geministrator.mcp.DesktopMcpStdioLauncher,
        ),
        consent = mcpConsent,
        stdioSupported = true,
        elicitation = mcpElicitation,
    )
    // Code execution with MCP: a script sandbox over the same tools and consent, off until the
    // keeper turns it on (docs/architecture/MCP_HOST.md, "Code execution").
    val codeExecution = com.hereliesaz.geministrator.mcp.CodeExecutionToolSource(
        inner = mcpHost,
        runtime = com.hereliesaz.geministrator.mcp.QuickJsScriptSandbox(),
        settings = com.hereliesaz.geministrator.mcp.CodeExecutionSettings(),
    )
    val plannerInstaller = DesktopPlannerModelInstaller(httpClient)
    val localPlanner = DesktopOrchestrationAgentRuntime(plannerInstaller)
    val orchestrationSpecialistInstaller = DesktopOrchestrationSpecialistInstaller(httpClient)
    val orchestrationSpecialistExecutor = DesktopLocalOrchestrationModelExecutor(orchestrationSpecialistInstaller)
    val orchestrationDecisionModel = DesktopOrchestrationDecisionModel(orchestrationSpecialistInstaller::decisionModelRoot)
    // Memory: SQLite under ~/.aive/memory, programmatic stages by default; local (installed epoch-8
    // clerks) or hosted stages on request. Attached before App builds the runtime, whose session
    // gateway captures the observer.
    val memoryEngines = HostedMemoryEngineProvider(desktopMemoryPlatform())
    val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val memoryModelInstaller = DesktopMemoryModelInstaller(httpClient)
    val memorySessions = DesktopOrtMemorySessionManager()
    // Small text tasks on the memory clerks' installed instruct model, when the keeper turns it on.
    val onDeviceTextGenerator = DesktopOnDeviceTextGenerator(memoryModelInstaller, memorySessions)
    val onDeviceSmallTaskPreference = OnDeviceSmallTaskPreference()
    val memoryLineage = com.hereliesaz.geministrator.memory.MemoryLineage.createDefault()
    val memoryLayer = MemoryLayerController(
        // One SQLite database per workflow run's memory bank; lineage and projects in Settings.
        banks = kotlinx.coroutines.runBlocking { desktopSqlMemoryBanks(memoryLineage) },
        settingsStore = MemoryLayerSettingsStore.createDefault(),
        lineage = memoryLineage,
        procedureCatalog = com.hereliesaz.geministrator.memory.procedural.desktopSqlProcedureCatalog(memoryLineage),
        engineProvider = DesktopMemoryEngineProvider(memoryEngines, memoryModelInstaller, memorySessions),
        scope = memoryScope,
        localModels = DesktopMemoryLocalModels(memoryModelInstaller, memoryScope),
    ).also { it.attach() }
    val entireRecorder = EntireRecorder()
    val desktopEntire = DesktopEntire()
    val testRunner = LocalTestRunnerClient()
    // Agent2Agent server (docs/A2A.md): off until turned on in the Keeper's room; loopback only.
    val a2aServer = DesktopA2aServer()

    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "The Aive",
                state = rememberWindowState(width = 1180.dp, height = 760.dp),
            ) {
                var credentials by remember { mutableStateOf(initialProviderCredentials) }
                var repositoryCredentials by remember { mutableStateOf(initialRepositoryCredentials) }
                var suppressedEnvironmentRepositoryServiceIds by remember { mutableStateOf(emptySet<String>()) }
                var configuringProviderId by remember { mutableStateOf<String?>(null) }
                var configuringRepositoryServiceId by remember { mutableStateOf<String?>(null) }
                var computeConfiguration by remember { mutableStateOf(initialComputeConfiguration) }
                var computeToken by remember { mutableStateOf(initialComputeToken) }
                var onDeviceSmallTasksEnabled by remember { mutableStateOf(onDeviceSmallTaskPreference.enabled) }
                val providers = remember(credentials, repositoryCredentials, onDeviceSmallTasksEnabled) {
                    configuredDesktopProviders(credentials, repositoryCredentials, httpClient, entireRecorder, codeExecution) +
                        listOfNotNull(onDeviceTextProviderOrNull(onDeviceSmallTasksEnabled, onDeviceTextGenerator))
                }
                val baseExecutorIntegrations = remember(repositoryCredentials, credentials) {
                    val configured = configuredDesktopExecutorIntegrations(repositoryCredentials, httpClient)
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
                val computeSession = remember(computeConfiguration, computeToken, baseExecutorIntegrations) {
                    val token = computeToken
                    val signer = if (computeConfiguration.configured && token != null) {
                        computeNodeSigner.value
                    } else {
                        null
                    }
                    if (signer != null && token != null) {
                        DesktopDistributedComputeSession(
                            configuration = computeConfiguration,
                            token = token,
                            baseIntegrations = baseExecutorIntegrations,
                            supportedExecutorKinds = desktopDistributedExecutorKinds(repositoryCredentials),
                            signer = signer,
                        )
                    } else {
                        null
                    }
                }
                var computeConnected by remember(computeSession) { mutableStateOf(false) }
                var computeOnlineNodes by remember(computeSession) { mutableStateOf(emptyList<ComputeNodeDescriptor>()) }
                var computeError by remember(computeSession) { mutableStateOf<String?>(null) }
                var computeEnrolledNodes by remember(computeSession) {
                    mutableStateOf(emptyList<com.hereliesaz.geministrator.distributed.EnrolledComputeNode>())
                }
                var computeRevoked by remember(computeSession) { mutableStateOf(false) }
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
                    launch {
                        session.client.enrolledNodes.collectLatest { computeEnrolledNodes = it }
                    }
                    launch {
                        session.client.revoked.collectLatest { computeRevoked = it }
                    }
                }
                // The test runner runs repository code on this computer, so it is added only here,
                // never to the base set that pool leases from other devices run on.
                val executorIntegrations = remember(computeSession, baseExecutorIntegrations) {
                    (computeSession?.executorIntegrations ?: baseExecutorIntegrations)
                        .withIntegration(TestRunnerExecutorIntegration(testRunner))
                }
                val uiScope = rememberCoroutineScope()
                var localPlannerStatus by remember {
                    mutableStateOf<LocalPlannerStatus>(
                        if (plannerInstaller.installed() != null) LocalPlannerStatus.Installed else LocalPlannerStatus.NotInstalled,
                    )
                }
                // Linked cloud LLM plans; an installed local planner goes first and falls back to it.
                val planningRuntime = remember(credentials, localPlannerStatus) {
                    val cloud = configuredPlanningApi(credentials)?.let(::TextGenerationOrchestrationAgentRuntime)
                    if (DesktopPlannerModel.ENABLED && localPlannerStatus == LocalPlannerStatus.Installed) {
                        PreferLocalOrchestrationAgentRuntime(
                            local = localPlanner,
                            localReady = { plannerInstaller.installed() != null },
                            cloud = cloud,
                        )
                    } else {
                        cloud
                    }
                }
                LaunchedEffect(credentials) {
                    memoryEngines.hostedTextGenerator = { providerId, model, prompt ->
                        val api = memoryTextApi(credentials, providerId, model)
                            ?: error("Memory provider ${providerId ?: "(default)"} is not configured")
                        api.generate(prompt).text
                    }
                }
                val localPlannerSetting = LocalPlannerSetting(
                    status = localPlannerStatus,
                    downloadSize = DesktopPlannerModel.downloadSizeLabel,
                    onInstall = install@{
                        if (localPlannerStatus is LocalPlannerStatus.Installing) return@install
                        localPlannerStatus = LocalPlannerStatus.Installing("Starting…")
                        uiScope.launch {
                            localPlannerStatus = try {
                                plannerInstaller.install { line -> localPlannerStatus = LocalPlannerStatus.Installing(line) }
                                LocalPlannerStatus.Installed
                            } catch (cancelled: CancellationException) {
                                localPlannerStatus = LocalPlannerStatus.NotInstalled
                                throw cancelled
                            } catch (failure: Throwable) {
                                LocalPlannerStatus.Failed(failure.message ?: failure::class.simpleName.orEmpty())
                            }
                        }
                    },
                    onRemove = {
                        uiScope.launch {
                            localPlanner.releaseModel()
                            plannerInstaller.remove()
                            localPlannerStatus = LocalPlannerStatus.NotInstalled
                        }
                    },
                )
                var localOrchestrationStatus by remember {
                    mutableStateOf<LocalOrchestrationSpecialistStatus>(
                        if (orchestrationSpecialistInstaller.allReleasedInstalled()) {
                            LocalOrchestrationSpecialistStatus.Installed
                        } else {
                            LocalOrchestrationSpecialistStatus.NotInstalled
                        },
                    )
                }
                val orchestrationUtilities = remember(localOrchestrationStatus) {
                    DesktopOrchestrationSpecialists.utilities(
                        orchestrationSpecialistInstaller,
                        orchestrationSpecialistExecutor,
                        orchestrationDecisionModel,
                    )
                }
                val localOrchestrationSetting = LocalOrchestrationSpecialistSetting(
                    status = localOrchestrationStatus,
                    releasedRoles = DesktopOrchestrationSpecialists.releasedRoleCount(),
                    totalRoles = OrchestrationQuestion.entries.size,
                    onInstall = install@{
                        if (localOrchestrationStatus is LocalOrchestrationSpecialistStatus.Installing) return@install
                        localOrchestrationStatus = LocalOrchestrationSpecialistStatus.Installing("Starting…")
                        uiScope.launch {
                            localOrchestrationStatus = try {
                                orchestrationSpecialistInstaller.installReleased { line ->
                                    localOrchestrationStatus = LocalOrchestrationSpecialistStatus.Installing(line)
                                }
                                LocalOrchestrationSpecialistStatus.Installed
                            } catch (cancelled: CancellationException) {
                                localOrchestrationStatus = LocalOrchestrationSpecialistStatus.NotInstalled
                                throw cancelled
                            } catch (failure: Throwable) {
                                LocalOrchestrationSpecialistStatus.Failed(failure.message ?: failure::class.simpleName.orEmpty())
                            }
                        }
                    },
                    onRemove = {
                        uiScope.launch {
                            localOrchestrationStatus = try {
                                // reset() waits for a running generation; keep it off the UI thread.
                                withContext(Dispatchers.IO) {
                                    orchestrationSpecialistExecutor.reset()
                                    orchestrationDecisionModel.close()
                                }
                                orchestrationSpecialistInstaller.removeReleased()
                                LocalOrchestrationSpecialistStatus.NotInstalled
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                LocalOrchestrationSpecialistStatus.Failed(failure.message ?: failure::class.simpleName.orEmpty())
                            }
                        }
                    },
                )
                // A linked GitHub account lets agents retrieve relevant files from the project's repository.
                val contextChunkSources = remember(repositoryCredentials) {
                    // Local Git folders always; GitHub repositories once an account is linked.
                    listOf(com.hereliesaz.geministrator.retrieval.LocalRepositoryChunkSource(com.hereliesaz.geministrator.retrieval.GitCliRepositoryReader)) +
                        repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITHUB_ID)?.let { token ->
                            listOf(
                                com.hereliesaz.geministrator.retrieval.GitHubRepositoryChunkSource(
                                    GitHubTokenProvider { token },
                                    httpClient,
                                ),
                            )
                        }.orEmpty()
                }
                val repositoryDiscovery = remember(repositoryCredentials) {
                    configuredDesktopRepositoryDiscovery(repositoryCredentials, httpClient)
                }

                var a2aEnabled by remember { mutableStateOf(a2aServer.enabledPreference) }
                var a2aStatus by remember { mutableStateOf<A2aServerStatus>(A2aServerStatus.Off) }
                var a2aTokenTemporary by remember { mutableStateOf(false) }
                DisposableEffect(a2aEnabled) {
                    val started = a2aEnabled
                    if (started) {
                        a2aStatus = try {
                            a2aServer.start()
                            A2aServerStatus.Listening(a2aServer.baseUrl)
                        } catch (failure: Exception) {
                            A2aServerStatus.Failed(failure.message ?: failure::class.simpleName.orEmpty())
                        }
                        a2aTokenTemporary = a2aServer.tokenIsTemporary
                    } else {
                        a2aStatus = A2aServerStatus.Off
                    }
                    onDispose { if (started) a2aServer.stop() }
                }
                val a2aPendingLaunches by a2aServer.core.pendingLaunches.collectAsState()
                val a2aServerSetting = A2aServerSetting(
                    enabled = a2aEnabled,
                    agentCardUrl = a2aServer.agentCardUrl.takeIf { a2aStatus is A2aServerStatus.Listening },
                    status = a2aStatus,
                    tokenIsTemporary = a2aTokenTemporary,
                    onEnabledChange = { enabled ->
                        a2aServer.enabledPreference = enabled
                        a2aEnabled = enabled
                    },
                    onCopyToken = {
                        a2aServer.currentToken()?.let { token ->
                            runCatching {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(token), null)
                            }
                        }
                    },
                    onResetToken = {
                        a2aServer.resetToken()
                        a2aTokenTemporary = a2aServer.tokenIsTemporary
                    },
                )

                val repositoryServiceId = configuringRepositoryServiceId
                val providerId = configuringProviderId
                when {
                    repositoryServiceId != null -> RepositoryCredentialSetup(
                        serviceId = repositoryServiceId,
                        onSave = { credential ->
                            repositoryCredentialStore.write(repositoryServiceId, credential)
                            suppressedEnvironmentRepositoryServiceIds -= repositoryServiceId
                            repositoryCredentials = readDesktopRepositoryCredentials(
                                repositoryCredentialStore,
                                suppressedEnvironmentRepositoryServiceIds,
                            )
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
                        entire = desktopEntire,
                        mcpHost = mcpHost,
                        mcpConsent = mcpConsent,
                        codeExecution = codeExecution,
                        mcpElicitation = mcpElicitation,
                        providers = providers,
                        executorIntegrations = executorIntegrations,
                        orchestrationUtilities = orchestrationUtilities,
                        projectFileService = projectFileService,
                        availableRepositorySources = RepositorySource.entries.toSet(),
                        onPickLocalRepository = { pickLocalGitFolder() },
                        connectedRepositoryServiceIds = repositoryCredentials.keys,
                        contextChunkSources = contextChunkSources,
                        onSearchRepositories = repositoryDiscovery::search,
                        onConfigureRepositoryService = { configuringRepositoryServiceId = it },
                        onDisconnectRepositoryService = { serviceId ->
                            repositoryCredentialStore.clear(serviceId)
                            suppressedEnvironmentRepositoryServiceIds += serviceId
                            repositoryCredentials = readDesktopRepositoryCredentials(
                                repositoryCredentialStore,
                                suppressedEnvironmentRepositoryServiceIds,
                            )
                        },
                        onReconfigureProvider = { configuringProviderId = it },
                        onConnectFreeProvider = { freeProviderId ->
                            providerCredentialStore.write(freeProviderId, ProviderCatalog.ANONYMOUS_CREDENTIAL)
                            credentials = readDesktopProviderCredentials(providerCredentialStore)
                        },
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
                            enrolledNodes = computeEnrolledNodes,
                            revoked = computeRevoked,
                        ),
                        onRevokeComputeNode = { nodeId ->
                            computeSession?.let { session ->
                                uiScope.launch {
                                    runCatching { session.client.revokeNode(nodeId) }
                                        .onFailure { computeError = it.message }
                                }
                            }
                        },
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
                        orchestrationRuntime = planningRuntime,
                        localPlannerSetting = localPlannerSetting.takeIf { DesktopPlannerModel.ENABLED },
                        a2aServerSetting = a2aServerSetting,
                        externalWorkflowLaunchRequests = a2aPendingLaunches,
                        onExternalWorkflowLaunchHandled = { requestId, failure ->
                            // Read now: right after a launch the runtime shows its new run.
                            val runIdAfter = a2aServer.shownRunId()
                            uiScope.launch { a2aServer.core.launchHandled(requestId, failure, runIdAfter) }
                        },
                        onRuntimeStateChanged = { state ->
                            val shown = (state as? ApplicationRuntimeState.Live)?.presentation?.run?.id?.value
                            uiScope.launch { a2aServer.core.runtimeShowsRun(shown) }
                        },
                        onRuntimeAvailable = a2aServer::attachRuntime,
                        localOrchestrationSpecialistSetting = localOrchestrationSetting,
                        onDeviceSmallTaskSetting = OnDeviceSmallTaskSetting(
                            enabled = onDeviceSmallTasksEnabled,
                            modelInstalled = onDeviceTextGenerator.isReady(),
                            onEnabledChange = { enabled ->
                                onDeviceSmallTaskPreference.enabled = enabled
                                onDeviceSmallTasksEnabled = enabled
                            },
                        ),
                        memoryLayer = memoryLayer,
                        setupSecretStore = object : SetupSecretStore {
                            // Only stored credentials: keys from environment variables stay there.
                            override fun read(): SetupSecrets = SetupSecrets(
                                providerCredentials = providerCredentialStore.readAll(),
                                repositoryCredentials = repositoryCredentialStore.readAll(),
                                distributedComputeToken = computeCredentialStore.readToken(),
                            )

                            override fun write(secrets: SetupSecrets) {
                                secrets.providerCredentials
                                    .filterKeys { ProviderCatalog.entry(it) != null }
                                    .forEach { (id, key) -> providerCredentialStore.write(id, key) }
                                secrets.repositoryCredentials
                                    .filterKeys { RepositoryServiceCatalog.entry(it) != null }
                                    .forEach { (id, token) -> repositoryCredentialStore.write(id, token) }
                                secrets.distributedComputeToken?.let(computeCredentialStore::writeToken)
                                credentials = readDesktopProviderCredentials(providerCredentialStore)
                                repositoryCredentials = readDesktopRepositoryCredentials(
                                    repositoryCredentialStore,
                                    suppressedEnvironmentRepositoryServiceIds,
                                )
                                computeConfiguration = computeConfigurationStore.read()
                                computeToken = computeCredentialStore.readToken()
                            }
                        },
                    )
                }
            }
        }
    } finally {
        testRunner.close()
        runCatching { a2aServer.stop() }
        memoryLayer.detach()
        memoryScope.cancel()
        memorySessions.close()
        orchestrationSpecialistExecutor.reset()
        orchestrationDecisionModel.close()
        localPlanner.close()
        // Stops local MCP server processes.
        kotlinx.coroutines.runBlocking { mcpHost.close() }
        httpClient.close()
    }
}

internal fun configuredDesktopProviders(
    credentials: Map<String, String>,
    repositoryCredentials: Map<String, String> = emptyMap(),
    repositoryHttpClient: HttpClient? = null,
    entireRecorder: EntireRecorder? = null,
    /** MCP tools for the text providers; workspace agents get none. */
    mcpTools: com.hereliesaz.geministrator.mcp.McpToolSource? = null,
): List<AgentProvider> = buildList {
    val gitlabToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITLAB_ID)
    val githubToken = repositoryCredentials.cleanKey(RepositoryServiceCatalog.GITHUB_ID)
    addAll(HostedLlmProviders.configured(credentials, mcpTools))
    // OpenCode on GitHub Actions: the automatic coding agent for GitHub repositories. Free Zen
    // model, no key; needs only the linked GitHub token.
    if (githubToken != null && repositoryHttpClient != null) {
        add(
            OpenCodeActionsAgentProvider(
                GitHubRestOpenCodeRunnerClient(GitHubTokenProvider { githubToken }, repositoryHttpClient),
            ),
        )
    }
    credentials.cleanKey(ProviderCatalog.OPENAI_ID)?.let { key ->
        val keyProvider = LlmApiKeyProvider { key }
        add(OpenAiProvider(keyProvider, tools = mcpTools))
        add(
            LocalWorkspaceAgentProvider(
                id = AgentProviderId("openai-local-workspace"),
                displayName = "OpenAI / Local Workspace",
                api = OpenAiResponsesApi(keyProvider),
                entire = entireRecorder,
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
        add(AnthropicProvider(keyProvider, tools = mcpTools))
        add(
            LocalWorkspaceAgentProvider(
                id = AgentProviderId("anthropic-local-workspace"),
                displayName = "Claude / Local Workspace",
                api = AnthropicMessagesApi(keyProvider),
                entire = entireRecorder,
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
        add(GeminiProvider(keyProvider, tools = mcpTools))
        add(
            LocalWorkspaceAgentProvider(
                id = AgentProviderId("gemini-local-workspace"),
                displayName = "Gemini / Local Workspace",
                api = GeminiGenerateContentApi(keyProvider),
                entire = entireRecorder,
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
        add(XaiProvider(keyProvider, tools = mcpTools))
        add(
            LocalWorkspaceAgentProvider(
                id = AgentProviderId("xai-local-workspace"),
                displayName = "Grok / Local Workspace",
                api = XaiResponsesApi(keyProvider),
                entire = entireRecorder,
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

private fun readDesktopRepositoryCredentials(
    store: DesktopRepositoryCredentialStore,
    suppressedEnvironmentServiceIds: Set<String> = emptySet(),
): Map<String, String> = buildMap {
    environmentRepositoryCredentials().forEach { (serviceId, credential) ->
        if (serviceId !in suppressedEnvironmentServiceIds) put(serviceId, credential)
    }
    store.readAll().forEach { (serviceId, credential) -> put(serviceId, credential) }
}

private fun environmentProviderCredentials(): Map<String, String> = buildMap {
    listOf(
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

/**
 * The JVM exposes no portable OS reduced-motion preference, so desktop honours an explicit
 * switch instead: the `AIVE_REDUCE_MOTION` environment variable or the `aive.reduceMotion`
 * system property, set to `1` or `true`.
 */
private fun desktopPrefersReducedMotion(): Boolean {
    val value = (System.getenv("AIVE_REDUCE_MOTION") ?: System.getProperty("aive.reduceMotion"))
        ?.trim()
        ?.lowercase()
    return value == "1" || value == "true"
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

private fun desktopMemoryPlatform(): MemoryMicroAgentPlatform {
    val os = System.getProperty("os.name", "").lowercase()
    return when {
        "win" in os -> MemoryMicroAgentPlatform.Windows
        "mac" in os -> MemoryMicroAgentPlatform.MacOS
        else -> MemoryMicroAgentPlatform.Linux
    }
}
