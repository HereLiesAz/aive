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


    private suspend fun preflight(context: TaskExecutorContext, op: StoreOperation): TaskExecutorExecution {
        val catalog = decodeCatalog(packagePayload(op))
        val requested = parseRequestedRoles(context.run.objective)
        require(requested.isNotEmpty()) {
            "Enter 1-10 source role names in the Run objective, separated by semicolons or new lines."
        }
        require(requested.size <= MAX_BATCH_SIZE) {
            "This workflow accepts at most $MAX_BATCH_SIZE characters per batch."
        }
        val selected = requested.map(catalog::resolve)
        require(selected.map(StoreCreatureCatalogEntry::id).distinct().size == selected.size) {
            "The same source character was selected more than once."
        }
        val plan = StoreCreatureBatchPlan(op.packageId, op.version, context.project.name, selected)
        val artifact = ArtifactRef(
            id = ArtifactId("${context.taskRun.id.value}:store-rig:plan"),
            kind = ArtifactKind.TaskPlan,
            taskRunId = context.taskRun.id,
            label = "Node Creature batch plan",
            textContent = json.encodeToString(StoreCreatureBatchPlan.serializer(), plan),
            mediaType = "application/json",
            metadata = mapOf("stage" to "batch-plan", "selectedCount" to selected.size.toString()),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        return TaskExecutorExecution(
            status = TaskRunStatus.Completed,
            artifacts = listOf(artifact),
            progress = 1f,
            progressMessage = "Validated ${selected.size} source-backed characters",
        )
    }

    private suspend fun runSlot(context: TaskExecutorContext, op: StoreOperation): TaskExecutorExecution {
        val slot = requireNotNull(op.slot) { "slot requires a 1-based slot number" }
        require(slot in 1..MAX_BATCH_SIZE)
        val plan = dependencyPlan(context)
        if (slot > plan.selected.size) {
            val skip = ArtifactRef(
                id = ArtifactId("${context.taskRun.id.value}:store-rig:skip"),
                kind = ArtifactKind.CommandOutput,
                taskRunId = context.taskRun.id,
                label = "Rig slot $slot skipped",
                textContent = "No character assigned to slot $slot.",
                mediaType = "text/plain",
                metadata = mapOf("stage" to "slot-skip", "slot" to slot.toString()),
                createdAtEpochMillis = context.nowEpochMillis,
            )
            return TaskExecutorExecution(
                TaskRunStatus.Completed,
                artifacts = listOf(skip),
                progress = 1f,
                progressMessage = "Slot $slot unused",
            )
        }

        val entry = plan.selected[slot - 1]
        val payload = packagePayload(StoreOperation("slot", plan.packageId, plan.version, slot))
        val cropBytes = requireNotNull(payload[entry.cropPath]) { "Missing source crop ${entry.cropPath}" }
        val approvedPrompt = requireNotNull(payload[entry.promptPath]) { "Missing prompt ${entry.promptPath}" }
            .decodeToString()
            .trim()
        require(approvedPrompt.isNotBlank())
        val cropUri = pngDataUri(cropBytes)
        val rigPrompt = rigPrompt(entry, approvedPrompt)

        var rig = api.generate(rigPrompt, listOf(cropUri), "auto")
        var inspection = api.inspect(
            inspectionPrompt(entry, approvedPrompt),
            listOf(cropUri, rig.dataUri()),
        )
        var attempt = 1
        while (!inspection.pass && attempt < maxVisualAttempts) {
            attempt += 1
            rig = api.generate(
                correctionPrompt(entry, rigPrompt, inspection.issues),
                listOf(cropUri, rig.dataUri()),
                "edit",
            )
            inspection = api.inspect(
                inspectionPrompt(entry, approvedPrompt),
                listOf(cropUri, rig.dataUri()),
            )
        }
        require(inspection.pass) {
            "Rig-sheet QC failed for ${entry.displayName()} after $attempt attempts: " +
                inspection.issues.joinToString("; ")
        }


        val base = context.taskRun.id.value
        val common = mapOf(
            "catalogEntryId" to entry.id,
            "nodeCharacterRoleLabel" to entry.role,
            "nodeCharacterVariant" to entry.variant.orEmpty(),
            "sourceSheet" to entry.sourceSheet,
            "sourceCell" to entry.sourceCell,
            "slot" to slot.toString(),
        )
        val crop = ArtifactRef(
            id = ArtifactId("$base:store-rig:crop"),
            kind = ArtifactKind.Media,
            taskRunId = context.taskRun.id,
            label = "${entry.displayName()} - exact source crop",
            uri = cropUri,
            mediaType = "image/png",
            metadata = common + mapOf("stage" to "source-crop"),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        val prompt = ArtifactRef(
            id = ArtifactId("$base:store-rig:prompt"),
            kind = ArtifactKind.Specification,
            taskRunId = context.taskRun.id,
            label = "${entry.displayName()} - approved rig prompt",
            textContent = approvedPrompt,
            mediaType = "text/plain",
            metadata = common + mapOf("stage" to "approved-prompt"),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        val rigArtifact = ArtifactRef(
            id = ArtifactId("$base:store-rig:rig"),
            kind = ArtifactKind.Media,
            taskRunId = context.taskRun.id,
            label = "${entry.displayName()} - 2D rig sheet",
            uri = rig.dataUri(),
            mediaType = "image/png",
            metadata = common + mapOf(
                "stage" to "rig-sheet",
                "attempts" to attempt.toString(),
            ),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        val verification = ArtifactRef(
            id = ArtifactId("$base:store-rig:verification"),
            kind = ArtifactKind.Verification,
            taskRunId = context.taskRun.id,
            label = "${entry.displayName()} - rig inspection",
            textContent = buildString {
                appendLine("PASS")
                appendLine("attempts=$attempt")
                appendLine(inspection.summary)
            },
            mediaType = "text/plain",
            metadata = common + mapOf("stage" to "visual-qc", "result" to "pass"),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        return TaskExecutorExecution(
            status = TaskRunStatus.Completed,
            artifacts = listOf(crop, prompt, rigArtifact, verification),
            progress = 1f,
            progressMessage = "Generated and inspected ${entry.displayName()}",
        )
    }

    private fun overview(context: TaskExecutorContext, op: StoreOperation): TaskExecutorExecution {
        val plan = dependencyPlan(context)
        val crops = dependencyArtifacts(context)
            .filter { it.kind == ArtifactKind.Media && it.metadata["stage"] == "source-crop" }
            .sortedBy { it.metadata["slot"]?.toIntOrNull() ?: Int.MAX_VALUE }
        require(crops.size == plan.selected.size) {
            "Overview requires ${plan.selected.size} approved source characters; found ${crops.size}."
        }
        val svg = buildOverviewSvg(
            plan.workflowLabel,
            crops.map {
                OverviewEntry(
                    it.metadata["nodeCharacterRoleLabel"].orEmpty(),
                    it.metadata["nodeCharacterVariant"].orEmpty(),
                    requireNotNull(it.uri),
                )
            },
        )
        val artifact = ArtifactRef(
            id = ArtifactId("${context.taskRun.id.value}:store-rig:overview"),
            kind = ArtifactKind.Media,
            taskRunId = context.taskRun.id,
            label = "${plan.workflowLabel} - Node Creatures",
            uri = "data:image/svg+xml;base64,${Base64.Default.encode(svg.encodeToByteArray())}",
            mediaType = "image/svg+xml",
            metadata = mapOf(
                "stage" to "workflow-creature-overview",
                "packageId" to op.packageId,
                "packageVersion" to op.version,
                "characterCount" to plan.selected.size.toString(),
            ),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        return TaskExecutorExecution(
            TaskRunStatus.Completed,
            artifacts = listOf(artifact),
            progress = 1f,
            progressMessage = "Created overview sheet with ${plan.selected.size} exact creatures",
        )
    }

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
