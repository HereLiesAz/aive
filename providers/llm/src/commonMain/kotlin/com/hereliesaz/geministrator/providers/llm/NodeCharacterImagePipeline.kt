package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.net.boundedBodyText
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.workflow.NODE_CHARACTER_IMAGE_PIPELINE_SERVICE
import com.hereliesaz.geministrator.workflow.NodeCharacterAssetRecord
import com.hereliesaz.geministrator.workflow.NodeCharacterAssetStore
import com.hereliesaz.geministrator.workflow.NodeCharacterImageStore
import com.hereliesaz.geministrator.workflow.SettingsNodeCharacterAssetStore
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegration
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Image-capable executor for the Node Creature generation workflow.
 *
 * It intentionally performs image-model work only after text roles have supplied explicit visual
 * and rigging contracts. Each generated source character and rig sheet is then checked with vision;
 * rejected sheets are corrected in a bounded loop before any assets can be registered.
 */
class NodeCharacterImagePipelineExecutorIntegration(
    apiKeyProvider: LlmApiKeyProvider,
    private val assetStore: NodeCharacterAssetStore = SettingsNodeCharacterAssetStore(),
    private val api: OpenAiNodeCharacterImageApi = OpenAiNodeCharacterImageApi(apiKeyProvider),
    private val maxVisualAttempts: Int = 3,
    /** The most paid calls (image and vision, every attempt) one dispatch may make; the pipeline's worst case is 12. */
    private val maxCallsPerDispatch: Int = NODE_CHARACTER_CALL_BUDGET_DEFAULT,
    /** Where the two pictures are kept as files; null keeps them in the artifact as data URIs (the web build has no files). */
    private val imageStore: NodeCharacterImageStore? = null,
) : TaskExecutorIntegration {
    init {
        require(maxVisualAttempts >= 1) { "maxVisualAttempts must be at least one" }
        require(maxCallsPerDispatch >= 1) { "maxCallsPerDispatch must be at least one" }
    }

    override val orchestrationToolId: String = NODE_CHARACTER_IMAGE_PIPELINE_SERVICE
    // Every call is paid and a failure may follow billed calls: the workflow's retry would pay for all of them again.
    override val retryDispatchFailures: Boolean = false

    override fun supports(executor: TaskExecutor): Boolean =
        executor is TaskExecutor.ExternalService &&
            executor.service == NODE_CHARACTER_IMAGE_PIPELINE_SERVICE

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val executor = context.executor as TaskExecutor.ExternalService
        val meter = ImageSpendMeter(maxCallsPerDispatch)
        return try {
            val operation = parseOperation(executor.operation)
            when (operation.verb) {
                "generate" -> generate(context, operation, meter)
                "register" -> register(context, operation)
                else -> TaskExecutorExecution(
                    status = TaskRunStatus.Failed,
                    progressMessage = "Unknown node-character image operation '${operation.verb}'",
                )
            }
        } catch (cancelled: CancellationException) {
            // A cancelled run still paid for the calls it made; the workflow keeps the task's usage it was last told.
            throw cancelled
        } catch (failure: Throwable) {
            TaskExecutorExecution(
                status = TaskRunStatus.Failed,
                progressMessage = failure.message?.takeIf(String::isNotBlank)
                    ?: "Node-character image pipeline failed",
                usage = meter.usage(),
            )
        }
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution =
        TaskExecutorExecution(
            status = TaskRunStatus.Failed,
            progressMessage = "Node-character image generation is synchronous and cannot be reconciled",
        )

    private suspend fun generate(
        context: TaskExecutorContext,
        operation: PipelineOperation,
        meter: ImageSpendMeter,
    ): TaskExecutorExecution {
        val role = context.run.roleSnapshot.firstOrNull { it.id.value == operation.roleId }
        val roleLabel = role?.name ?: operation.roleId
        val evidence = dependencyArtifacts(context)
            .mapNotNull { artifact ->
                artifact.textContent
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { "### ${artifact.label}\n$it" }
            }
            .joinToString("\n\n")
            .take(MAX_EVIDENCE_CHARS)

        require(evidence.isNotBlank()) {
            "Character generation for $roleLabel has no upstream text contracts"
        }

        val sourcePrompt = sourceCharacterPrompt(roleLabel, operation.roleId, evidence)
        var source = api.generate(
            prompt = sourcePrompt,
            inputImages = emptyList(),
            action = "generate",
            meter = meter,
        )
        var sourceInspection = api.inspect(
            prompt = sourceInspectionPrompt(roleLabel, sourcePrompt),
            images = listOf(source.dataUri()),
            meter = meter,
        )

        var sourceAttempt = 1
        while (!sourceInspection.pass && sourceAttempt < maxVisualAttempts) {
            sourceAttempt += 1
            source = api.generate(
                prompt = sourceCorrectionPrompt(
                    roleLabel = roleLabel,
                    originalPrompt = sourcePrompt,
                    issues = sourceInspection.issues,
                ),
                inputImages = listOf(source.dataUri()),
                action = "edit",
                meter = meter,
            )
            sourceInspection = api.inspect(
                prompt = sourceInspectionPrompt(roleLabel, sourcePrompt),
                images = listOf(source.dataUri()),
                meter = meter,
            )
        }
        require(sourceInspection.pass) {
            "Source-character visual QC failed after $sourceAttempt attempts: " +
                sourceInspection.issues.joinToString("; ")
        }

        val rigPrompt = rigSheetPrompt(roleLabel, operation.roleId, evidence)
        var rig = api.generate(
            prompt = rigPrompt,
            inputImages = listOf(source.dataUri()),
            action = "auto",
            meter = meter,
        )
        var rigInspection = api.inspect(
            prompt = rigInspectionPrompt(roleLabel, evidence),
            images = listOf(source.dataUri(), rig.dataUri()),
            meter = meter,
        )

        var rigAttempt = 1
        while (!rigInspection.pass && rigAttempt < maxVisualAttempts) {
            rigAttempt += 1
            rig = api.generate(
                prompt = rigCorrectionPrompt(
                    roleLabel = roleLabel,
                    rigPrompt = rigPrompt,
                    issues = rigInspection.issues,
                ),
                inputImages = listOf(source.dataUri(), rig.dataUri()),
                action = "edit",
                meter = meter,
            )
            rigInspection = api.inspect(
                prompt = rigInspectionPrompt(roleLabel, evidence),
                images = listOf(source.dataUri(), rig.dataUri()),
                meter = meter,
            )
        }
        require(rigInspection.pass) {
            "Rig-sheet visual QC failed after $rigAttempt attempts: " +
                rigInspection.issues.joinToString("; ")
        }

        val base = context.taskRun.id.value
        val sourceUri = imageUri("$base:node-character:source", source)
        val rigUri = imageUri("$base:node-character:rig", rig)
        val commonMetadata = mapOf(
            "nodeCharacterRoleId" to operation.roleId,
            "nodeCharacterRoleLabel" to roleLabel,
            "sourceWorkflowId" to operation.sourceWorkflowId,
        )
        val sourceArtifact = ArtifactRef(
            id = ArtifactId("$base:node-character:source"),
            kind = ArtifactKind.Media,
            taskRunId = context.taskRun.id,
            label = "$roleLabel · source character",
            uri = sourceUri,
            mediaType = "image/png",
            metadata = commonMetadata + mapOf(
                "stage" to "source-character",
                "attempts" to sourceAttempt.toString(),
            ),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        val rigArtifact = ArtifactRef(
            id = ArtifactId("$base:node-character:rig"),
            kind = ArtifactKind.Media,
            taskRunId = context.taskRun.id,
            label = "$roleLabel · 2D rig sheet",
            uri = rigUri,
            mediaType = "image/png",
            metadata = commonMetadata + mapOf(
                "stage" to "rig-sheet",
                "attempts" to rigAttempt.toString(),
            ),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        val promptArtifact = ArtifactRef(
            id = ArtifactId("$base:node-character:prompt"),
            kind = ArtifactKind.Specification,
            taskRunId = context.taskRun.id,
            label = "$roleLabel · image generation contract",
            textContent = buildString {
                appendLine("SOURCE CHARACTER")
                appendLine(sourcePrompt)
                appendLine()
                appendLine("2D RIG SHEET")
                appendLine(rigPrompt)
            },
            mediaType = "text/plain",
            metadata = commonMetadata + mapOf("stage" to "generation-prompt"),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        val verificationArtifact = ArtifactRef(
            id = ArtifactId("$base:node-character:verification"),
            kind = ArtifactKind.Verification,
            taskRunId = context.taskRun.id,
            label = "$roleLabel · visual QC",
            textContent = buildString {
                appendLine("PASS")
                appendLine("source attempts=$sourceAttempt")
                appendLine("rig attempts=$rigAttempt")
                appendLine("source review: ${sourceInspection.summary}")
                appendLine("rig review: ${rigInspection.summary}")
            },
            mediaType = "text/plain",
            metadata = commonMetadata + mapOf(
                "stage" to "visual-qc",
                "result" to "pass",
            ),
            createdAtEpochMillis = context.nowEpochMillis,
        )

        return TaskExecutorExecution(
            status = TaskRunStatus.Completed,
            artifacts = listOf(sourceArtifact, rigArtifact, promptArtifact, verificationArtifact),
            progress = 1f,
            progressMessage = "Generated and visually verified $roleLabel",
            usage = meter.usage(),
        )
    }

    /** The reference an artifact keeps for [image]: a stored file when there is a store, else the data URI itself. */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun imageUri(key: String, image: GeneratedNodeImage): String {
        val store = imageStore ?: return image.dataUri()
        return store.save(key, Base64.decode(image.base64Png))
    }

    private suspend fun register(
        context: TaskExecutorContext,
        operation: PipelineOperation,
    ): TaskExecutorExecution {
        val artifacts = dependencyArtifacts(context)
        val metadataMatch: (ArtifactRef) -> Boolean = {
            it.metadata["nodeCharacterRoleId"] == operation.roleId &&
                it.metadata["sourceWorkflowId"] == operation.sourceWorkflowId
        }
        val source = requireNotNull(
            artifacts.firstOrNull {
                metadataMatch(it) && it.kind == ArtifactKind.Media &&
                    it.metadata["stage"] == "source-character"
            },
        ) { "Verified source-character media is missing for ${operation.roleId}" }
        val rig = requireNotNull(
            artifacts.firstOrNull {
                metadataMatch(it) && it.kind == ArtifactKind.Media &&
                    it.metadata["stage"] == "rig-sheet"
            },
        ) { "Verified rig-sheet media is missing for ${operation.roleId}" }
        val prompt = artifacts.firstOrNull {
            metadataMatch(it) && it.metadata["stage"] == "generation-prompt"
        }
        val verification = requireNotNull(
            artifacts.firstOrNull {
                metadataMatch(it) && it.kind == ArtifactKind.Verification &&
                    it.metadata["result"] == "pass"
            },
        ) { "Passing visual verification is missing for ${operation.roleId}" }

        val roleId = RoleDefinitionId(operation.roleId)
        val roleLabel = source.metadata["nodeCharacterRoleLabel"] ?: operation.roleId
        assetStore.put(
            NodeCharacterAssetRecord(
                roleId = roleId,
                roleLabel = roleLabel,
                sourceWorkflowId = WorkflowDefinitionId(operation.sourceWorkflowId),
                sourceCharacterArtifactId = source.id,
                rigSheetArtifactId = rig.id,
                generationPromptArtifactId = prompt?.id,
                verificationArtifactId = verification.id,
                registeredAtEpochMillis = context.nowEpochMillis,
            ),
        )

        val release = ArtifactRef(
            id = ArtifactId("${context.taskRun.id.value}:node-character:registration"),
            kind = ArtifactKind.Release,
            taskRunId = context.taskRun.id,
            label = "$roleLabel · node character registered",
            textContent = "Registered source ${source.id.value} and rig ${rig.id.value} for ${roleId.value}.",
            mediaType = "text/plain",
            metadata = mapOf(
                "nodeCharacterRoleId" to roleId.value,
                "nodeCharacterRoleLabel" to roleLabel,
                "sourceWorkflowId" to operation.sourceWorkflowId,
                "sourceCharacterArtifactId" to source.id.value,
                "rigSheetArtifactId" to rig.id.value,
                "verificationArtifactId" to verification.id.value,
            ),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        return TaskExecutorExecution(
            status = TaskRunStatus.Completed,
            artifacts = listOf(release),
            progress = 1f,
            progressMessage = "Registered $roleLabel node character",
        )
    }

    private fun dependencyArtifacts(context: TaskExecutorContext): List<ArtifactRef> =
        context.task.dependsOn.flatMap { dependencyId ->
            context.run.taskRuns[dependencyId]?.artifacts.orEmpty()
        }

    private fun parseOperation(raw: String?): PipelineOperation {
        val parts = raw.orEmpty().split('|', limit = 3)
        require(parts.size == 3 && parts.all(String::isNotBlank)) {
            "Node-character operation must be verb|sourceWorkflowId|roleId"
        }
        return PipelineOperation(parts[0], parts[1], parts[2])
    }

    private fun sourceCharacterPrompt(
        roleLabel: String,
        roleId: String,
        evidence: String,
    ): String = """
        Draw exactly ONE new Aive Node Creature for the role "$roleLabel" ($roleId).

        This is the assembled source character that will later be cut into a flat 2D puppet rig.
        Follow the supplied role and visual contracts literally. Preserve the established Aive
        family language: polished faceted/polygonal geometry, controlled gradients, crisp highlights,
        coherent material finish, expressive eyes where specified, and compact mascot proportions.
        The character must have a genuinely role-specific central body silhouette and appendage
        language, not a generic sphere with decorative substitutions.

        Output only the single assembled character on a transparent background. No name, text,
        poster layout, border, cast shadow, floor, scenery, alternate pose, exploded anatomy, or
        other character.

        UPSTREAM CONTRACTS
        $evidence
    """.trimIndent()

    private fun sourceInspectionPrompt(roleLabel: String, contract: String): String = """
        Inspect the supplied image as the proposed assembled source character for "$roleLabel".
        Return JSON only: {"pass":true|false,"summary":"short factual result","issues":["specific issue"]}.

        PASS only if it shows exactly one complete character, has a distinct visible central body,
        follows the requested faceted Aive style, contains no labels/scenery/cast shadow, and is
        usable as the sole visual reference for a later 2D puppet-rig extraction. Reject generic
        substitutions, missing anatomy required by the contract, or unrelated props.

        CONTRACT
        $contract
    """.trimIndent()

    private fun sourceCorrectionPrompt(
        roleLabel: String,
        originalPrompt: String,
        issues: List<String>,
    ): String = """
        Edit the supplied source-character image for "$roleLabel". Correct every listed QC failure
        while preserving all conforming character features. Return exactly one complete assembled
        character on transparent background.

        QC FAILURES
        ${issues.joinToString("\n") { "- $it" }}

        ORIGINAL CONTRACT
        $originalPrompt
    """.trimIndent()

    private fun rigSheetPrompt(
        roleLabel: String,
        roleId: String,
        evidence: String,
    ): String = """
        Edit/extract from the supplied "$roleLabel" ($roleId) source-character image to create ONE
        production-ready transparent PNG sprite sheet for direct flat 2D puppet rigging.

        The supplied character image is the sole visual source for anatomy, color, texture, scale,
        shading, and props. Every isolated piece must look cut directly from that character.

        Output only the sliceable parts required by the upstream rigging contract. Preserve the
        complete unique body/core at source-relative size. Eye sockets that belong to the body stay
        in the body. Eye whites contain NO pupils; pupils are separate; upper and lower eyelids are
        separate. Articulated appendages are flat base/mid/tip cutout layers only where specified,
        with a small hidden overlap for rotation. Lower gestation limbs are flat base/mid/bud-tip
        layers. Isolate only real movable props.

        ABSOLUTELY NO hollow sockets, holes, collars, pegs, plugs, recessed tube openings, or
        mechanical connection hardware on detached appendage ends. This is a flat 2D puppet, NOT a
        3D exploded-model diagram. Do not normalize part sizes, enlarge small pieces, shrink the
        body, duplicate identical reusable pieces, add labels/text/grid/borders, add an assembled
        character example, add cast shadows, add scenery, or add parts from another character.
        Leave generous transparent space around every piece for rectangular slicing.

        UPSTREAM CONTRACTS
        $evidence
    """.trimIndent()

    private fun rigInspectionPrompt(roleLabel: String, evidence: String): String = """
        Two images are supplied in order: (1) the assembled source character for "$roleLabel";
        (2) its proposed 2D puppet-rig sheet.

        Inspect the rig sheet against the source and contracts. Return JSON only:
        {"pass":true|false,"summary":"short factual result","issues":["specific issue"]}.

        FAIL for any of these:
        - missing or wrongly scaled main body/core;
        - generic body substitution or lost source texture/style;
        - pupils baked into eye whites;
        - required upper or lower eyelids missing;
        - 3D exploded-model logic;
        - hollow sockets, holes, collars, pegs, plugs, or recessed openings at detached appendage ends;
        - preassembled appendages where the contract requires separate articulated pieces;
        - duplicate identical reusable pieces;
        - wrong source-relative scale;
        - unrelated/invented anatomy or props;
        - another character, assembled-character example, labels, text, grid, border, cast shadow,
          opaque background, or scenery;
        - pieces touching/overlapping so rectangular slicing is unsafe.

        Eye sockets visible in the BODY are valid and must not be mistaken for forbidden appendage
        sockets.

        CONTRACTS
        $evidence
    """.trimIndent()

    private fun rigCorrectionPrompt(
        roleLabel: String,
        rigPrompt: String,
        issues: List<String>,
    ): String = """
        The first supplied image is the authoritative assembled source character for "$roleLabel".
        The second supplied image is a rejected rig sheet. Edit/redraw the rejected rig sheet to
        correct EVERY issue below while preserving all conforming source details. Return only the
        corrected transparent, directly sliceable flat 2D puppet-rig sheet.

        QC FAILURES
        ${issues.joinToString("\n") { "- $it" }}

        RIG CONTRACT
        $rigPrompt
    """.trimIndent()

    private data class PipelineOperation(
        val verb: String,
        val sourceWorkflowId: String,
        val roleId: String,
    )

    private companion object {
        const val MAX_EVIDENCE_CHARS: Int = 48_000
    }
}

data class GeneratedNodeImage(
    val base64Png: String,
    val revisedPrompt: String? = null,
) {
    fun dataUri(): String = "data:image/png;base64,$base64Png"
}

@Serializable
data class NodeCharacterVisualInspection(
    val pass: Boolean = false,
    val summary: String = "",
    val issues: List<String> = emptyList(),
)

class OpenAiNodeCharacterImageApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val responseModel: String = "gpt-6-astra",
    private val imageModel: String = "gpt-image-2.5-sunburst",
    private val baseUrl: String = "https://api.openai.com/v1",
    client: HttpClient = nodeCharacterImageHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val client = client.config { followRedirects = false }

    /**
     * One image from the `image_generation` tool. The defaults are the node-character sheet's (portrait,
     * transparent); the short-film frames ask for a landscape picture on a background.
     */
    suspend fun generate(
        prompt: String,
        inputImages: List<String>,
        action: String,
        size: String = "1024x1536",
        background: String = "transparent",
        meter: ImageSpendMeter? = null,
    ): GeneratedNodeImage {
        val request = ResponsesRequest(
            model = responseModel,
            input = listOf(
                ResponsesInputMessage(
                    role = "user",
                    content = buildList {
                        add(ResponsesInputContent(type = "input_text", text = prompt))
                        inputImages.forEach { image ->
                            add(
                                ResponsesInputContent(
                                    type = "input_image",
                                    imageUrl = image,
                                    detail = "high",
                                ),
                            )
                        }
                    },
                ),
            ),
            tools = listOf(
                ImageGenerationTool(
                    type = "image_generation",
                    model = imageModel,
                    size = size,
                    quality = "high",
                    background = background,
                    outputFormat = "png",
                    action = action,
                ),
            ),
            toolChoice = ImageToolChoice(type = "image_generation"),
        )
        val response = post(request, meter, imageCall = true)
        val image = response.output.firstOrNull {
            it.type == "image_generation_call" && !it.result.isNullOrBlank()
        } ?: error("OpenAI response did not contain an image_generation_call result")
        return GeneratedNodeImage(
            base64Png = requireNotNull(image.result),
            revisedPrompt = image.revisedPrompt,
        )
    }

    suspend fun inspect(
        prompt: String,
        images: List<String>,
        meter: ImageSpendMeter? = null,
    ): NodeCharacterVisualInspection {
        val request = ResponsesRequest(
            model = responseModel,
            input = listOf(
                ResponsesInputMessage(
                    role = "user",
                    content = buildList {
                        add(ResponsesInputContent(type = "input_text", text = prompt))
                        images.forEach { image ->
                            add(
                                ResponsesInputContent(
                                    type = "input_image",
                                    imageUrl = image,
                                    detail = "high",
                                ),
                            )
                        }
                    },
                ),
            ),
        )
        val response = post(request, meter, imageCall = false)
        val text = response.output
            .asSequence()
            .filter { it.type == "message" }
            .flatMap { it.content.asSequence() }
            .firstOrNull { it.type == "output_text" && !it.text.isNullOrBlank() }
            ?.text
            ?: error("OpenAI visual inspection did not contain output text")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val normalized = if (start >= 0 && end > start) text.substring(start, end + 1) else text.trim()
        return runCatching {
            json.decodeFromString(NodeCharacterVisualInspection.serializer(), normalized)
        }.getOrElse { failure ->
            NodeCharacterVisualInspection(
                pass = false,
                summary = "Inspection response was not valid JSON",
                issues = listOf(failure.message ?: text.take(500)),
            )
        }
    }

    private suspend fun post(request: ResponsesRequest, meter: ImageSpendMeter?, imageCall: Boolean): ResponsesResponse {
        val key = apiKeyProvider.getApiKey().trim()
        require(key.isNotEmpty()) { "OpenAI API key is not configured for node-character generation" }
        // The call is counted before it is made: a call that fails on the way may still have been billed.
        meter?.reserveCall()
        val response = try {
            client.post("$baseUrl/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (failure: CancellationException) {
            meter?.recordUnpriced()
            throw failure
        } catch (failure: Throwable) {
            // No answer came back: the provider may still have billed it.
            meter?.recordUnpriced()
            throw failure
        }
        if (response.status.value !in 200..299) {
            // A 4xx refusal was not billed; a 5xx (or anything else) may have been.
            if (response.status.value !in 400..499) meter?.recordUnpriced()
            val body = response.boundedBodyText().redactSecrets().take(1_200)
            error(
                "OpenAI node-character request failed: HTTP ${response.status.value}" +
                    body.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
            )
        }
        val parsed: ResponsesResponse = try {
            response.body()
        } catch (failure: Throwable) {
            meter?.recordUnpriced()
            throw failure
        }
        meter?.record(
            priceProviderId = "openai",
            model = responseModel,
            inputTokens = parsed.usage?.inputTokens,
            outputTokens = parsed.usage?.outputTokens,
            cachedInputTokens = parsed.usage?.inputTokensDetails?.cachedTokens,
            // The image tool bills at the image model's rate, which the price table does not list.
            imagePriced = imageCall,
        )
        return parsed
    }
}

private fun nodeCharacterImageHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 240_000L
        connectTimeoutMillis = 20_000L
        socketTimeoutMillis = 240_000L
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
}

@Serializable
private data class ResponsesRequest(
    val model: String,
    val input: List<ResponsesInputMessage>,
    val tools: List<ImageGenerationTool>? = null,
    @SerialName("tool_choice") val toolChoice: ImageToolChoice? = null,
)

@Serializable
private data class ResponsesInputMessage(
    val role: String,
    val content: List<ResponsesInputContent>,
)

@Serializable
private data class ResponsesInputContent(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val detail: String? = null,
)

@Serializable
private data class ImageGenerationTool(
    // No default: the client's Json does not encode defaults, so a defaulted `type` was silently
    // omitted and OpenAI answered 400 "Missing required parameter: 'tools[0].type'".
    val type: String,
    val model: String,
    val size: String,
    val quality: String,
    val background: String,
    @SerialName("output_format") val outputFormat: String,
    val action: String,
)

@Serializable
private data class ImageToolChoice(
    val type: String,
)

@Serializable
private data class ResponsesResponse(
    val output: List<ResponsesOutputItem> = emptyList(),
    val usage: ResponsesUsage? = null,
)

@Serializable
private data class ResponsesUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("input_tokens_details") val inputTokensDetails: ResponsesInputDetails? = null,
)

@Serializable
private data class ResponsesInputDetails(
    @SerialName("cached_tokens") val cachedTokens: Long? = null,
)

@Serializable
private data class ResponsesOutputItem(
    val type: String? = null,
    val result: String? = null,
    @SerialName("revised_prompt") val revisedPrompt: String? = null,
    val content: List<ResponsesOutputContent> = emptyList(),
)

@Serializable
private data class ResponsesOutputContent(
    val type: String? = null,
    val text: String? = null,
)

/** Paid calls one node-character dispatch may make: three source attempts and three rig attempts, each generated and inspected. */
const val NODE_CHARACTER_CALL_BUDGET_DEFAULT: Int = 12

/**
 * What one dispatch has asked of the image API: a hard cap on calls and the spend they are known to have made. The spend
 * fails closed: a call whose cost cannot be priced (an image call, whose model is not on the price list; a call that
 * failed without an answer) marks the usage as unknown, never free, so a dollar cap stops the run.
 */
class ImageSpendMeter(private val maxCalls: Int) {
    var calls: Int = 0
        private set
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var costUsd = 0.0
    private var costUnknown = false

    fun reserveCall() {
        check(calls < maxCalls) { "The node-character call budget ($maxCalls calls) is used up; nothing more is asked of the image model." }
        calls += 1
    }

    /** A call that may have been billed but reported no usage. */
    fun recordUnpriced() {
        costUnknown = true
    }

    fun record(
        priceProviderId: String,
        model: String,
        inputTokens: Long?,
        outputTokens: Long?,
        cachedInputTokens: Long?,
        imagePriced: Boolean,
    ) {
        this.inputTokens += inputTokens ?: 0L
        this.outputTokens += outputTokens ?: 0L
        val price = com.hereliesaz.geministrator.providers.ModelPriceTable.Default.priceFor(priceProviderId, model)
        if (price == null || inputTokens == null || outputTokens == null) {
            costUnknown = true
        } else {
            costUsd += price.costUsd(inputTokens, outputTokens, cachedInputTokens ?: 0L)
        }
        if (imagePriced) costUnknown = true
    }

    fun usage(): com.hereliesaz.geministrator.domain.TaskUsage? =
        if (calls == 0) {
            null
        } else {
            com.hereliesaz.geministrator.domain.TaskUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = costUsd,
                costUnknown = costUnknown,
            )
        }
}
