package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.ApplicationRuntime
import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.RepositoryServiceCatalog
import com.hereliesaz.geministrator.azphalt.SettingsAuthoredWorkflowStore
import com.hereliesaz.geministrator.control.ControlDraftOutcome
import com.hereliesaz.geministrator.control.RuntimeControlHost
import com.hereliesaz.geministrator.control.draftForControl
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.persistence.desktopSqlWorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.llm.AnthropicProvider
import com.hereliesaz.geministrator.providers.llm.GeminiProvider
import com.hereliesaz.geministrator.providers.llm.HostedLlmProviders
import com.hereliesaz.geministrator.providers.llm.LlmApiKeyProvider
import com.hereliesaz.geministrator.providers.llm.OpenAiProvider
import com.hereliesaz.geministrator.providers.llm.XaiProvider
import com.hereliesaz.geministrator.providers.llm.configuredPlanningRuntime
import com.hereliesaz.geministrator.providers.llm.configuredPlanReviewer
import com.hereliesaz.geministrator.providers.llm.configuredQueenModel
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.PlanReviewer
import com.hereliesaz.geministrator.workflow.GitHubActionsExecutorIntegration
import com.hereliesaz.geministrator.workflow.GitHubRestActionsClient
import com.hereliesaz.geministrator.workflow.GitHubRestOpenCodeRunnerClient
import com.hereliesaz.geministrator.workflow.GitHubRestRepositoryOperationClient
import com.hereliesaz.geministrator.workflow.GitHubTokenProvider
import com.hereliesaz.geministrator.workflow.GitLabRestRepositoryOperationClient
import com.hereliesaz.geministrator.workflow.OpenCodeActionsAgentProvider
import com.hereliesaz.geministrator.workflow.RepositoryOperationClient
import com.hereliesaz.geministrator.workflow.RepositoryOperationExecutorIntegration
import com.hereliesaz.geministrator.workflow.RepositoryServiceTokenProvider
import com.hereliesaz.geministrator.workflow.RoutingRepositoryOperationClient
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegration
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An independent JVM process owns The Aive's actual runtime, provider sessions and durable SQLite
 * state. This is not a proxy for the Compose desktop application. Each process takes an OS file
 * lock before opening the database so two orchestrators never dispatch the same work.
 */
class HeadlessEngine private constructor(
    val runtime: ApplicationRuntime,
    val controlHost: RuntimeControlHost,
    val planner: OrchestrationAgentRuntime?,
    val reviewer: PlanReviewer?,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runtime.close()
        scope.cancel()
        httpClient.close()
        lock.release()
        channel.close()
    }

    companion object {
        /** The root is deliberately different from the desktop app's ~/.aive/workflows.db. */
        fun defaultDirectory(env: Map<String, String> = System.getenv()): File =
            File(env["AIVE_HEADLESS_HOME"]?.takeIf(String::isNotBlank)
                ?: File(System.getProperty("user.home"), ".aive/headless").path).absoluteFile

        suspend fun open(
            directory: File = defaultDirectory(),
            env: Map<String, String> = System.getenv(),
        ): HeadlessEngine {
            require(directory.isDirectory || directory.mkdirs()) { "Cannot create headless state directory" }
            val channel = FileChannel.open(
                File(directory, "engine.lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock() ?: error("Another The Aive headless engine is using this state directory")
            } catch (failure: java.nio.channels.OverlappingFileLockException) {
                channel.close()
                error("Another The Aive headless engine is using this state directory")
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val httpClient = HttpClient(CIO)
            try {
                val providerCredentials = HeadlessCredentials.providers(env)
                val repositoryCredentials = HeadlessCredentials.repositories(env)
                val providers = headlessProviders(providerCredentials, repositoryCredentials, httpClient)
                val integrations = headlessExecutors(repositoryCredentials, httpClient)
                val persistence = desktopSqlWorkflowPersistence(File(directory, "workflows.db"))
                val runtime = ApplicationRuntime.create(
                    providers = providers,
                    scope = scope,
                    persistence = persistence,
                    executorIntegrations = integrations,
                )
                // The queen's model for disputes: the Source pinned on her role, else automatic; none linked means she is away.
                runtime.disputeModelSource = com.hereliesaz.geministrator.queen.DisputeModelSource { roles ->
                    configuredQueenModel(providerCredentials, com.hereliesaz.geministrator.queen.orchestratorPin(roles))?.let { model ->
                        com.hereliesaz.geministrator.DisputeModels(model, com.hereliesaz.geministrator.queen.disputeVoices(model, roles))
                    }
                }
                val planner = configuredPlanningRuntime(providerCredentials)
                val reviewer = configuredPlanReviewer(providerCredentials)
                val authored = SettingsAuthoredWorkflowStore(
                    storageKey = "aive.headless.authored.${directory.canonicalPath.hashCode().toUInt().toString(16)}",
                )
                val draft: (suspend (String) -> ControlDraftOutcome)? =
                    if (planner == null) null else {
                        { prompt: String -> runtime.draftForControl(prompt, planner, reviewer) }
                    }
                val host = RuntimeControlHost(
                    runtime = { runtime },
                    authoredStore = authored,
                    drafter = { draft },
                    appVersion = "headless",
                    settingsView = {
                        buildJsonObject {
                            put("runtime", "headless")
                            put("control", "loopback")
                        }
                    },
                )
                return HeadlessEngine(runtime, host, planner, reviewer, scope, httpClient, channel, lock)
            } catch (failure: Throwable) {
                httpClient.close()
                scope.cancel()
                lock.release()
                channel.close()
                throw failure
            }
        }
    }
}

/** The credentials are injected from the environment, never written to run state or diagnostics. */
internal object HeadlessCredentials {
    private val providerVars = mapOf(
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
        ProviderCatalog.OLLAMA_CLOUD_ID to "OLLAMA_API_KEY",
        ProviderCatalog.ZAI_ID to "ZAI_API_KEY",
    )
    fun providers(env: Map<String, String>): Map<String, String> =
        providerVars.mapNotNull { (id, variable) ->
            env[variable]?.trim()?.takeIf(String::isNotEmpty)?.let { id to it }
        }.toMap()

    fun repositories(env: Map<String, String>): Map<String, String> =
        mapOf(
            RepositoryServiceCatalog.GITHUB_ID to "GITHUB_TOKEN",
            RepositoryServiceCatalog.GITLAB_ID to "GITLAB_TOKEN",
        ).mapNotNull { (id, variable) ->
            env[variable]?.trim()?.takeIf(String::isNotEmpty)?.let { id to it }
        }.toMap()
}

private fun headlessProviders(
    credentials: Map<String, String>,
    repositories: Map<String, String>,
    client: HttpClient,
): List<AgentProvider> = buildList {
    addAll(HostedLlmProviders.configured(credentials))
    credentials[ProviderCatalog.OPENAI_ID]?.let { key -> add(OpenAiProvider(LlmApiKeyProvider { key })) }
    credentials[ProviderCatalog.ANTHROPIC_ID]?.let { key -> add(AnthropicProvider(LlmApiKeyProvider { key })) }
    credentials[ProviderCatalog.GEMINI_ID]?.let { key -> add(GeminiProvider(LlmApiKeyProvider { key })) }
    credentials[ProviderCatalog.XAI_ID]?.let { key -> add(XaiProvider(LlmApiKeyProvider { key })) }
    repositories[RepositoryServiceCatalog.GITHUB_ID]?.let { token ->
        add(OpenCodeActionsAgentProvider(GitHubRestOpenCodeRunnerClient(GitHubTokenProvider { token }, client)))
    }
}

private fun headlessExecutors(
    repositories: Map<String, String>,
    client: HttpClient,
): TaskExecutorIntegrationRegistry {
    val integrations = mutableListOf<TaskExecutorIntegration>()
    val repositoryClients = mutableListOf<RepositoryOperationClient>()
    repositories[RepositoryServiceCatalog.GITHUB_ID]?.let { token ->
        repositoryClients += GitHubRestRepositoryOperationClient(
            tokenProvider = RepositoryServiceTokenProvider { token },
            httpClient = client,
        )
        integrations += GitHubActionsExecutorIntegration(
            GitHubRestActionsClient(httpClient = client, tokenProvider = GitHubTokenProvider { token }),
        )
    }
    repositories[RepositoryServiceCatalog.GITLAB_ID]?.let { token ->
        repositoryClients += GitLabRestRepositoryOperationClient(
            tokenProvider = RepositoryServiceTokenProvider { token },
            httpClient = client,
        )
    }
    if (repositoryClients.isNotEmpty()) {
        integrations += RepositoryOperationExecutorIntegration(RoutingRepositoryOperationClient(repositoryClients))
    }
    return TaskExecutorIntegrationRegistry(integrations)
}
