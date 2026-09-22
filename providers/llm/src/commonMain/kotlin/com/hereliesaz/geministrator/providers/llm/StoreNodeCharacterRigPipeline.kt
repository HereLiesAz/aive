package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.azphalt.AZPHALT_STORE_URL
import com.hereliesaz.geministrator.azphalt.AzphaltRepositoryClient
import com.hereliesaz.geministrator.azphalt.KmpZipAzphaltArchiveReader
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegration
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val STORE_NODE_CHARACTER_RIG_PIPELINE_SERVICE: String = "azphalt-node-character-rig-pipeline"

@OptIn(ExperimentalEncodingApi::class)
class StoreNodeCharacterRigPipelineExecutorIntegration(
    apiKeyProvider: LlmApiKeyProvider,
    private val api: OpenAiNodeCharacterImageApi = OpenAiNodeCharacterImageApi(apiKeyProvider),
    private val repositoryClient: AzphaltRepositoryClient = AzphaltRepositoryClient(
        httpClient = storePipelineHttpClient(),
        repositoryUrl = AZPHALT_STORE_URL,
    ),
    private val archiveReader: KmpZipAzphaltArchiveReader = KmpZipAzphaltArchiveReader(),
    private val maxVisualAttempts: Int = 3,
) : TaskExecutorIntegration {
    init { require(maxVisualAttempts >= 1) }

    override val orchestrationToolId: String = STORE_NODE_CHARACTER_RIG_PIPELINE_SERVICE
    override val retryDispatchFailures: Boolean = true
    private val packageCache = mutableMapOf<String, Map<String, ByteArray>>()

    override fun supports(executor: TaskExecutor): Boolean =
        executor is TaskExecutor.ExternalService &&
            executor.service == STORE_NODE_CHARACTER_RIG_PIPELINE_SERVICE

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val executor = context.executor as TaskExecutor.ExternalService
        return try {
            val op = StoreOperation.parse(executor.operation)
            when (op.verb) {
                "preflight" -> preflight(context, op)
                "slot" -> runSlot(context, op)
                "overview" -> overview(context, op)
                else -> TaskExecutorExecution(
                    TaskRunStatus.Failed,
                    progressMessage = "Unknown store node-character operation",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            TaskExecutorExecution(
                status = TaskRunStatus.Failed,
                progressMessage = failure.message?.takeIf(String::isNotBlank)
                    ?: "Azphalt node-character rig pipeline failed",
            )
        }
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution =
        TaskExecutorExecution(
            TaskRunStatus.Failed,
            progressMessage = "Store node-character rig operations are synchronous",
        )

    //__STORE_PIPELINE_METHODS__
}

private fun storePipelineHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 240_000L
        connectTimeoutMillis = 20_000L
        socketTimeoutMillis = 240_000L
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
}
