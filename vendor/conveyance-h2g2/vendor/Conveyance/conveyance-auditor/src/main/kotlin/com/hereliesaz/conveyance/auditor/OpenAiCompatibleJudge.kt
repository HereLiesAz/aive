package com.hereliesaz.conveyance.auditor

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * A judge reached over the chat-completions shape, which nearly everyone speaks.
 *
 * One client covers Ollama, OpenAI, Gemini, xAI, Groq, Mistral, DeepSeek and OpenRouter, because
 * they all accept the same request. That is the entire reason this class exists: supporting "any
 * major provider" turned out to be one implementation and a base URL, not eight integrations.
 *
 * It is also what makes the keyless default possible. A model running locally answers on this same
 * shape with no key at all, so the audit works on a machine that has never been given an account.
 *
 * Anthropic is deliberately not reached through here. It has a real SDK in this project, and using
 * a compatibility shim to talk to something with a first-class client would be worse code chosen
 * for symmetry.
 *
 * No dependency is added for this: the JDK's own HTTP client is enough, and a judge is not worth a
 * transitive tree.
 */
class OpenAiCompatibleJudge(
    private val label: String,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String?,
) : Judge {

    override val name: String get() = "$label/$model"

    private val json = ObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
        .build()

    override fun look(png: ByteArray?, prompt: String): String {
        val content = buildList {
            if (png != null) {
                val data = Base64.getEncoder().encodeToString(png)
                add(
                    mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to "data:image/png;base64,$data"),
                    ),
                )
            }
            add(mapOf("type" to "text", "text" to prompt))
        }

        val body = json.writeValueAsString(
            mapOf(
                "model" to model,
                "messages" to listOf(mapOf("role" to "user", "content" to content)),
                "max_tokens" to MAX_TOKENS,
                // A judge that invents differently every run cannot be compared against itself.
                "temperature" to 0,
            ),
        )

        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
            .timeout(Duration.ofSeconds(READ_SECONDS))
            .header("content-type", "application/json")
            .apply { apiKey?.let { header("authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw JudgeUnreachable(
                "$name answered ${response.statusCode()}: ${response.body().take(ERROR_CHARS)}",
            )
        }
        return json.readTree(response.body())
            .path("choices").firstOrNull()
            ?.path("message")?.path("content")?.asText()
            .orEmpty()
    }

    private companion object {
        const val MAX_TOKENS = 4_000
        const val CONNECT_SECONDS = 5L
        const val READ_SECONDS = 300L
        const val ERROR_CHARS = 400
    }
}

/**
 * The judge could not be reached.
 *
 * Distinct from a judgement, and treated differently: a missing judge means the audit did not run,
 * which is not the same as an audit that found nothing wrong.
 */
class JudgeUnreachable(message: String) : RuntimeException(message)
