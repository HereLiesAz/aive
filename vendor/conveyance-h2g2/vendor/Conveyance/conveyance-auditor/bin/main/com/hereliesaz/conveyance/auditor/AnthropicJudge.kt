package com.hereliesaz.conveyance.auditor

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.TextBlockParam
import java.util.Base64

/**
 * Claude, through its own SDK.
 *
 * Anthropic gets a native client rather than being reached through the compatible shape, because it
 * has one and using a shim would be worse code chosen for tidiness.
 */
class AnthropicJudge(
    private val client: AnthropicClient = AnthropicOkHttpClient.fromEnv(),
    private val model: Model = Model.of(DEFAULT_MODEL),
) : Judge {

    override val name: String get() = "anthropic/${model.asString()}"

    override fun look(png: ByteArray?, prompt: String): String {
        val blocks = buildList {
            if (png != null) {
                add(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .data(Base64.getEncoder().encodeToString(png))
                                    // Required on the wire. The builder accepts its absence and the
                                    // API rejects it, so omitting this compiles and then 400s.
                                    .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                                    .build(),
                            )
                            .build(),
                    ),
                )
            }
            add(ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build()))
        }

        val response = client.messages().create(
            MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .addUserMessageOfBlockParams(blocks)
                .build(),
        )
        return response.content()
            .mapNotNull { it.text().orElse(null) }
            .joinToString("\n") { it.text() }
    }

    private companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
        const val MAX_TOKENS = 8_000L
    }
}
