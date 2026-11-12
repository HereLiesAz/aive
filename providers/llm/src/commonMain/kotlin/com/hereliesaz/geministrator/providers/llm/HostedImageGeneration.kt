package com.hereliesaz.geministrator.providers.llm

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
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hosted text-to-image models are not agent chat models or downloaded Azphalt assets.
 * Their capabilities are explicit so a workflow that requires source-image editing,
 * transparency, or visual inspection will never route to a text-only renderer.
 */
data class HostedImageModel(
    val id: String,
    val provider: String,
    val name: String,
    val anonymous: Boolean,
    val acceptsReferenceImages: Boolean = false,
    val supportsTransparentBackground: Boolean = false,
    val supportsEditing: Boolean = false,
)

object HostedImageModels {
    const val SERVICE = "hosted-image-generation"
    val available = listOf(
        HostedImageModel("aihorde|auto", "aihorde", "AI Horde (community workers)", true),
        HostedImageModel("fal|fal-ai/flux/schnell", "fal", "FLUX.1 Schnell (fal)", false),
    )

    fun resolve(operation: String?): HostedImageModel? =
        available.firstOrNull { it.id == operation }

    fun supports(model: HostedImageModel, editing: Boolean, transparency: Boolean): Boolean =
        (!editing || model.supportsEditing) &&
            (!transparency || model.supportsTransparentBackground)
}

/**
 * Hosted image rendering. The task objective is the prompt. Operations must be exact catalog
 * model IDs; arbitrary endpoint URLs and model paths are intentionally not accepted.
 *
 * Horde jobs are durable remote tasks (dispatch -> reconcile -> cancel). fal's Schnell
 * endpoint completes synchronously. Neither advertises editing or alpha transparency.
 */
class HostedImageGenerationExecutorIntegration(
    private val falKey: () -> String? = { null },
    private val hordeKey: () -> String? = { null },
    client: HttpClient = HttpClient {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 240_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 240_000
        }
    },
) : TaskExecutorIntegration {
    private val http = client
    private val json = Json { ignoreUnknownKeys = true }
    override val orchestrationToolId = HostedImageModels.SERVICE
    // Remote image generation may bill or queue work. Never auto-resubmit an unknown outcome.
    override val retryDispatchFailures: Boolean = false

    override fun supports(executor: TaskExecutor): Boolean =
        executor is TaskExecutor.ExternalService &&
            executor.service == HostedImageModels.SERVICE &&
            HostedImageModels.resolve(executor.operation) != null &&
            (HostedImageModels.resolve(executor.operation)?.anonymous == true ||
                !falKey().isNullOrBlank())

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val model = HostedImageModels.resolve(
            (context.executor as? TaskExecutor.ExternalService)?.operation,
        ) ?: return failed("Unknown hosted image model")
        val prompt = context.task.objective.trim()
        if (prompt.isBlank()) return failed("Image prompt is empty")
        return try {
            when (model.provider) {
                "aihorde" -> submitHorde(prompt)
                "fal" -> submitFal(context, model, prompt)
                else -> failed("Unsupported image provider")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed("Image generation request failed: ${failure.message.orEmpty().take(240)}")
        }
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val id = context.taskRun.externalRunId
            ?.takeIf { it.startsWith("horde:") }?.removePrefix("horde:")
            ?: return failed("No recoverable image generation request ID")
        if (!id.matches(Regex("[a-zA-Z0-9-]{1,100}"))) return failed("Invalid image request ID")
        return try {
            val check = http.get("https://aihorde.net/api/v2/generate/check/$id")
            val state = check.objectBody()
            if (state["faulted"]?.jsonPrimitive?.booleanOrNull == true) return failed("AI Horde image generation failed")
            if (state["done"]?.jsonPrimitive?.booleanOrNull != true) {
                return TaskExecutorExecution(TaskRunStatus.Running, externalRunId = "horde:$id", progressMessage = "Waiting for AI Horde workers")
            }
            val response = http.get("https://aihorde.net/api/v2/generate/status/$id").objectBody()
            val first = (response["generations"] as? JsonArray)?.firstOrNull() as? JsonObject
            val url = first?.get("img")?.jsonPrimitive?.contentOrNull
            if (url.isNullOrBlank()) failed("AI Horde completed without an image")
            else completed(context, url, "aihorde", "auto")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed("Image status request failed: ${failure.message.orEmpty().take(240)}")
        }
    }

    override suspend fun cancel(context: TaskExecutorContext) {
        val id = context.taskRun.externalRunId
            ?.takeIf { it.startsWith("horde:") }?.removePrefix("horde:") ?: return
        if (id.matches(Regex("[a-zA-Z0-9-]{1,100}"))) {
            http.delete("https://aihorde.net/api/v2/generate/status/$id") {
                header("apikey", hordeKey()?.takeIf(String::isNotBlank) ?: "0000000000")
                header("Client-Agent", "Aive:1.0:github.com/HereLiesAz/aive")
            }
        }
    }

    private suspend fun submitHorde(prompt: String): TaskExecutorExecution {
        val response = http.post("https://aihorde.net/api/v2/generate/async") {
            header("apikey", hordeKey()?.takeIf(String::isNotBlank) ?: "0000000000")
            header("Client-Agent", "Aive:1.0:github.com/HereLiesAz/aive")
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":${JsonPrimitive(prompt)},"params":{"n":1,"width":512,"height":512},"nsfw":false,"r2":true}""")
        }.objectBody()
        val id = response["id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,100}")) }
            ?: return failed("AI Horde did not return a generation ID")
        return TaskExecutorExecution(TaskRunStatus.Running, externalRunId = "horde:$id", progressMessage = "Queued with AI Horde")
    }

    private suspend fun submitFal(
        context: TaskExecutorContext,
        model: HostedImageModel,
        prompt: String,
    ): TaskExecutorExecution {
        val key = falKey()?.trim()?.takeIf(String::isNotEmpty) ?: return failed("fal API key is missing")
        val response = http.post("https://fal.run/fal-ai/flux/schnell") {
            header(HttpHeaders.Authorization, "Key $key")
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":${JsonPrimitive(prompt)},"num_images":1,"output_format":"png"}""")
        }.objectBody()
        val first = (response["images"] as? JsonArray)?.firstOrNull() as? JsonObject
        val url = first?.get("url")?.jsonPrimitive?.contentOrNull
        return if (url.isNullOrBlank()) failed("fal returned no image")
        else completed(context, url, model.provider, model.id)
    }

    private fun completed(context: TaskExecutorContext, url: String, provider: String, model: String): TaskExecutorExecution {
        if (!url.startsWith("https://") && !url.startsWith("data:image/")) {
            return failed("Image provider returned an unsupported image URI")
        }
        val artifact = ArtifactRef(
            id = ArtifactId("${context.taskRun.id.value}:hosted-image"),
            kind = ArtifactKind.Media,
            taskRunId = context.taskRun.id,
            label = "Generated image",
            uri = url,
            mediaType = "image/png",
            metadata = mapOf("provider" to provider, "model" to model),
            createdAtEpochMillis = context.nowEpochMillis,
        )
        return TaskExecutorExecution(
            TaskRunStatus.Completed,
            artifacts = listOf(artifact),
            progress = 1f,
            progressMessage = "Image generated by $provider",
        )
    }

    private fun failed(message: String) =
        TaskExecutorExecution(TaskRunStatus.Failed, progressMessage = message)

    private suspend fun io.ktor.client.statement.HttpResponse.objectBody(): JsonObject {
        val body = bodyAsText()
        check(status.value in 200..299) {
            "Provider returned HTTP ${status.value}"
        }
        return json.parseToJsonElement(body).jsonObject
    }
}
