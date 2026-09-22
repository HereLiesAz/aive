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
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
    private val assetClient: HttpClient = storePipelineHttpClient(),
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
        val input = parseBatchInput(context.run.objective)
        val requested = input.roles
        require(requested.isNotEmpty()) {
            "Enter 1-10 source role names in the Run objective, separated by semicolons or new lines."
        }
        require(requested.size <= MAX_BATCH_SIZE) {
            "This workflow accepts at most $MAX_BATCH_SIZE characters per batch."
        }
        val selected = requested.map { catalog.resolve(it) }
        require(selected.map(StoreCreatureCatalogEntry::id).distinct().size == selected.size) {
            "The same source character was selected more than once."
        }
        val plan = StoreCreatureBatchPlan(
            op.packageId,
            op.version,
            input.workflowLabel ?: context.project.name,
            selected,
        )
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
        val cropBytes = fetchSourceCrop(entry)
        val approvedPrompt = entry.promptText.trim()
        require(approvedPrompt.isNotBlank()) { "Approved prompt is blank for ${entry.displayName()}" }
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


    private suspend fun fetchSourceCrop(entry: StoreCreatureCatalogEntry): ByteArray {
        val response = assetClient.get(entry.cropUrl)
        require(response.status.value in 200..299) {
            "Unable to fetch canonical source crop for ${entry.displayName()}: HTTP ${response.status.value}"
        }
        return response.body()
    }

    private suspend fun packagePayload(op: StoreOperation): Map<String, ByteArray> {
        val key = "${op.packageId}@${op.version}"
        packageCache[key]?.let { return it }
        return archiveReader.read(repositoryClient.download(op.packageId, op.version)).also {
            packageCache[key] = it
        }
    }

    private fun decodeCatalog(payload: Map<String, ByteArray>): StoreCreatureCatalog =
        json.decodeFromString(
            StoreCreatureCatalog.serializer(),
            requireNotNull(payload[CATALOG_PATH]) {
                "Store package is missing $CATALOG_PATH"
            }.decodeToString(),
        ).also { require(it.entries.isNotEmpty()) }

    private fun dependencyPlan(context: TaskExecutorContext): StoreCreatureBatchPlan {
        val artifact = dependencyArtifacts(context).firstOrNull {
            it.kind == ArtifactKind.TaskPlan && it.metadata["stage"] == "batch-plan"
        } ?: error("Node Creature batch plan is missing")
        return json.decodeFromString(
            StoreCreatureBatchPlan.serializer(),
            requireNotNull(artifact.textContent),
        )
    }

    private fun dependencyArtifacts(context: TaskExecutorContext): List<ArtifactRef> =
        context.task.dependsOn.flatMap { context.run.taskRuns[it]?.artifacts.orEmpty() }

    private fun parseBatchInput(objective: String): BatchInput {
        val text = objective.trim()
        if (text.isEmpty()) return BatchInput(null, emptyList())
        val workflowLabel = Regex("(?im)^\\s*workflow\\s*:\\s*(.+?)\\s*$")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val rolesLine = Regex("(?im)^\\s*roles?\\s*:\\s*(.+?)\\s*$")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        val body = rolesLine ?: text
            .lineSequence()
            .filterNot { it.trimStart().startsWith("workflow:", ignoreCase = true) }
            .joinToString(";")
        val roles = body
            .split(Regex("[;,]+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
        return BatchInput(workflowLabel, roles)
    }

    private fun StoreCreatureCatalog.resolve(query: String): StoreCreatureCatalogEntry {
        val needle = normalize(query)
        entries.firstOrNull { normalize(it.id) == needle }?.let { return it }
        entries.firstOrNull { normalize(it.displayName()) == needle }?.let { return it }
        val matches = entries.filter { normalize(it.role) == needle }
        require(matches.isNotEmpty()) {
            "No supplied source character matches '$query'. This workflow never invents stand-ins."
        }
        require(matches.size == 1) {
            "Role '$query' has multiple supplied variants. Choose: " +
                matches.joinToString { it.displayName() }
        }
        return matches.single()
    }


    private fun rigPrompt(entry: StoreCreatureCatalogEntry, approved: String): String = """
        Create the production flat 2D puppet-rig sprite sheet for exactly ONE supplied Aive Node Creature:
        ${entry.displayName()}.

        The attached image is the ONLY visual source. It is the canonical crop from the supplied
        character sheets (${entry.sourceSheet}, cell ${entry.sourceCell}). Never borrow anatomy from
        another character.

        ACCOMPANYING APPROVED ROLE PROMPT:
        $approved

        ACCEPTED RIG-SHEET RULES - THESE OVERRIDE ANY CONFLICT:
        - Direct-use flat 2D cutout puppet sprite sheet, NOT a 3D exploded model.
        - Preserve exact creature identity, unique body silhouette, faceted texture, gradients,
          highlights, translucency, colors, proportions, and source-relative scale.
        - Eye sockets visibly belonging to the BODY stay.
        - Eye whites contain NO pupils; pupils are separate; upper/lower eyelids are separate.
        - Movable appendages use flat overlapping cutout segments; slight hidden overlap is allowed.
        - NEVER add sockets, hollow tube ends, recessed openings, collars, plugs, pegs, or visible
          connection hardware to detached appendage segments.
        - Include only anatomy, terminals, props, and effects present in this crop.
        - One unique reusable piece appears once. No unnecessary duplicates.
        - Actual alpha transparency only; never draw a checkerboard.
        - No cast shadow, floor, scenery, labels, text, borders, diagrams, alternate views, or
          assembled-character example.
        - Generous transparent spacing for rectangular slicing.
        - ONE sprite sheet for this ONE creature only.
    """.trimIndent()

    private fun inspectionPrompt(entry: StoreCreatureCatalogEntry, approved: String): String = """
        Image 1 is the authoritative source crop for ${entry.displayName()}.
        Image 2 is its proposed rig sheet.
        Return JSON only: {"pass":true|false,"summary":"short factual result","issues":["specific issue"]}.

        FAIL for: wrong creature or invented stand-in; multi-character output; missing/wrongly scaled
        body; changed silhouette/palette/faceting/proportions; pupils baked into eye whites; missing
        required eyelids; pseudo-3D exploded-model logic; sockets/hollow ends/holes/collars/plugs/pegs
        on appendage ends; duplicate reusable parts; invented anatomy/props; labels/text/presentation
        board/alternate views/assembled example/cast shadow/scenery/opaque background/checkerboard; or
        touching assets unsafe for rectangular slicing.

        Eye sockets visibly belonging to the BODY are valid.

        APPROVED ROLE PROMPT:
        $approved
    """.trimIndent()

    private fun correctionPrompt(
        entry: StoreCreatureCatalogEntry,
        original: String,
        issues: List<String>,
    ): String = """
        Correct the rejected flat 2D rig sheet for ${entry.displayName()}.
        FIRST image = immutable authoritative source crop. SECOND image = rejected rig sheet.
        Preserve conforming source details and fix EVERY issue. Return only one corrected transparent
        flat 2D puppet-parts sprite sheet.

        QC FAILURES:
        ${issues.joinToString("\n") { "- $it" }}

        ORIGINAL RIG CONTRACT:
        $original
    """.trimIndent()


    private fun buildOverviewSvg(workflowLabel: String, entries: List<OverviewEntry>): String {
        val columns = if (entries.size <= 4) 2 else 3
        val cardWidth = 420
        val cardHeight = 430
        val gutter = 28
        val margin = 48
        val header = 120
        val rows = (entries.size + columns - 1) / columns
        val width = margin * 2 + columns * cardWidth + (columns - 1) * gutter
        val height = header + margin + rows * cardHeight + (rows - 1) * gutter + margin
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">")
            append("<rect width=\"100%\" height=\"100%\" fill=\"#0b0d12\"/>")
            append("<text x=\"$margin\" y=\"58\" fill=\"#fff\" font-family=\"sans-serif\" font-size=\"30\" font-weight=\"700\">${xml(workflowLabel)}</text>")
            append("<text x=\"$margin\" y=\"92\" fill=\"#9da7b8\" font-family=\"sans-serif\" font-size=\"18\">NODE CREATURES - ${entries.size} SOURCE-BACKED CHARACTERS</text>")
            entries.forEachIndexed { index, entry ->
                val col = index % columns
                val row = index / columns
                val x = margin + col * (cardWidth + gutter)
                val y = header + row * (cardHeight + gutter)
                append("<rect x=\"$x\" y=\"$y\" width=\"$cardWidth\" height=\"$cardHeight\" rx=\"22\" fill=\"#141821\" stroke=\"#2c3442\" stroke-width=\"2\"/>")
                append("<image href=\"${xmlAttr(entry.imageUri)}\" x=\"${x + 36}\" y=\"${y + 28}\" width=\"${cardWidth - 72}\" height=\"${cardHeight - 105}\" preserveAspectRatio=\"xMidYMid meet\"/>")
                append("<text x=\"${x + 24}\" y=\"${y + cardHeight - 42}\" fill=\"#fff\" font-family=\"sans-serif\" font-size=\"22\" font-weight=\"700\">${xml(entry.role)}</text>")
                if (entry.variant.isNotBlank()) {
                    append("<text x=\"${x + 24}\" y=\"${y + cardHeight - 16}\" fill=\"#9da7b8\" font-family=\"sans-serif\" font-size=\"14\">${xml(entry.variant)}</text>")
                }
            }
            append("</svg>")
        }
    }

    private fun pngDataUri(bytes: ByteArray): String =
        "data:image/png;base64,${Base64.Default.encode(bytes)}"

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun xml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun xmlAttr(value: String): String = xml(value).replace("\"", "&quot;")

    @Serializable
    private data class StoreCreatureCatalog(
        val version: Int = 1,
        val entries: List<StoreCreatureCatalogEntry> = emptyList(),
    )

    @Serializable
    private data class StoreCreatureCatalogEntry(
        val id: String,
        val role: String,
        val variant: String? = null,
        val sourceSheet: String,
        val sourceCell: String,
        val cropUrl: String,
        val promptText: String,
    ) {
        fun displayName(): String =
            variant?.takeIf(String::isNotBlank)?.let { "$role - $it" } ?: role
    }

    @Serializable
    private data class StoreCreatureBatchPlan(
        val packageId: String,
        val version: String,
        val workflowLabel: String,
        val selected: List<StoreCreatureCatalogEntry>,
    )

    private data class BatchInput(
        val workflowLabel: String?,
        val roles: List<String>,
    )

    private data class OverviewEntry(
        val role: String,
        val variant: String,
        val imageUri: String,
    )

    private data class StoreOperation(
        val verb: String,
        val packageId: String,
        val version: String,
        val slot: Int? = null,
    ) {
        companion object {
            fun parse(raw: String?): StoreOperation {
                val parts = raw.orEmpty().split('|')
                require(parts.size in 3..4) {
                    "operation must be verb|packageId|version[|slot]"
                }
                require(parts.take(3).all(String::isNotBlank))
                return StoreOperation(
                    parts[0],
                    parts[1],
                    parts[2],
                    parts.getOrNull(3)?.toIntOrNull(),
                )
            }
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 10
        const val CATALOG_PATH = "catalog/roles.json"
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }





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
