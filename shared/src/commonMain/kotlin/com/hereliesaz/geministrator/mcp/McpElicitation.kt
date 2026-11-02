package com.hereliesaz.geministrator.mcp

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * MCP elicitation (docs/architecture/MCP_HOST.md, Elicitation): a server asks the keeper for input
 * in the middle of a tool call. Everything a server sends here is untrusted: it is shown as the
 * server's words, labelled with the server's name, and never followed as an instruction.
 */
enum class McpElicitationAction(val wire: String) {
    Accept("accept"),
    Decline("decline"),
    Cancel("cancel"),
}

/** The keeper's answer: `ElicitResult`. [content] only for an accepted form. */
data class McpElicitationResponse(
    val action: McpElicitationAction,
    val content: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("action", action.wire)
        if (action == McpElicitationAction.Accept && content != null) put("content", content)
    }

    companion object {
        val Cancel = McpElicitationResponse(McpElicitationAction.Cancel)
        val Decline = McpElicitationResponse(McpElicitationAction.Decline)
    }
}

/** One choice of an enum field: the [value] sent back and the [label] shown. */
data class McpElicitationChoice(val value: String, val label: String)

enum class McpElicitationFieldKind { Text, Number, Integer, Flag, SingleChoice, MultiChoice }

/** One field of a form elicitation: a primitive property of the flat `requestedSchema`. */
data class McpElicitationField(
    val key: String,
    val title: String,
    val description: String?,
    val kind: McpElicitationFieldKind,
    val required: Boolean,
    /** `email`, `uri`, `date` or `date-time` for text; null otherwise. */
    val format: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val choices: List<McpElicitationChoice> = emptyList(),
    val minItems: Int? = null,
    val maxItems: Int? = null,
    /** The schema's default, as the form starts. */
    val initial: McpFieldInput? = null,
    /** The name looks like it asks for a secret: the dialog warns on this field. */
    val looksSecret: Boolean = false,
)

/** What the keeper entered in a field. */
sealed interface McpFieldInput {
    data class Text(val text: String) : McpFieldInput
    data class Flag(val on: Boolean) : McpFieldInput
    data class Choices(val values: Set<String>) : McpFieldInput
}

/** Why a field's input cannot be sent; the dialog words it. */
enum class McpFieldProblem {
    Required, TooShort, TooLong, NotANumber, NotAnInteger, BelowMinimum, AboveMaximum,
    NotAChoice, TooFewChoices, TooManyChoices, BadEmail, BadUri, BadDate, BadDateTime,
}

/** A server's request for input, ready to show. */
sealed interface McpElicitationRequest {
    val serverId: String
    val serverName: String
    val serverLocation: String
    val toolName: String

    /** The server's explanation, shortened. Untrusted text, shown as the server's. */
    val message: String

    /** The workflow run (or, outside one, the task run) the call belongs to. */
    val runKey: String

    /** Form mode: fields to fill, sent to the server when the keeper submits. */
    data class Form(
        override val serverId: String,
        override val serverName: String,
        override val serverLocation: String,
        override val toolName: String,
        override val message: String,
        override val runKey: String,
        val fields: List<McpElicitationField>,
    ) : McpElicitationRequest {
        /** The form's starting values, from the schema's defaults. */
        fun initialInputs(): Map<String, McpFieldInput> = fields.associate { field ->
            field.key to (field.initial ?: when (field.kind) {
                McpElicitationFieldKind.Flag -> McpFieldInput.Flag(false)
                McpElicitationFieldKind.MultiChoice -> McpFieldInput.Choices(emptySet())
                else -> McpFieldInput.Text("")
            })
        }

        /** Problems with [inputs], by field key; empty when the form can be sent. */
        fun problems(inputs: Map<String, McpFieldInput>): Map<String, McpFieldProblem> =
            fields.mapNotNull { field -> problemOf(field, inputs[field.key])?.let { field.key to it } }.toMap()

        /** The typed `content` for [inputs], or null when [problems] is not empty. */
        fun content(inputs: Map<String, McpFieldInput>): JsonObject? {
            if (problems(inputs).isNotEmpty()) return null
            return buildJsonObject {
                fields.forEach { field -> valueOf(field, inputs[field.key])?.let { put(field.key, it) } }
            }
        }
    }

    /**
     * URL mode: the server wants the keeper to visit [url] in a browser (to sign in elsewhere,
     * pay, enter a secret). The URL is shown in full and opened only on the keeper's explicit
     * action, never automatically, and never fetched by The Aive.
     */
    data class Link(
        override val serverId: String,
        override val serverName: String,
        override val serverLocation: String,
        override val toolName: String,
        override val message: String,
        override val runKey: String,
        val url: String,
        /** The host, for the dialog to set apart from the rest of the URL. */
        val host: String,
        /** Punycode, cleartext or a user-info part: the dialog warns. */
        val suspicious: Boolean,
    ) : McpElicitationRequest

    companion object {
        const val MAX_MESSAGE_CHARS = 2_000
        const val MAX_FIELDS = 32
        const val MAX_TEXT_CHARS = 10_000
        const val MAX_URL_CHARS = 4_000

        private val SECRET_NAME = Regex(
            "pass(word|wd|phrase|code)?|secret|token|api[ _-]?key|private[ _-]?key|credential|\\bpin\\b|otp|2fa|cvv|cvc|card[ _-]?number|ssn",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Reads `elicitation/create` params. Null when the request cannot be shown faithfully
         * (a nested or unknown schema, an unopenable URL, an unknown mode): the caller answers
         * `cancel` without asking.
         */
        fun parse(
            params: JsonObject,
            serverId: String,
            serverName: String,
            serverLocation: String,
            toolName: String,
            runKey: String,
        ): McpElicitationRequest? {
            val message = (params.mcpString("message") ?: "").take(MAX_MESSAGE_CHARS)
            return when (params.mcpString("mode") ?: "form") {
                "form" -> {
                    val schema = params["requestedSchema"] as? JsonObject ?: return null
                    if (schema.mcpString("type")?.let { it != "object" } == true) return null
                    val properties = schema["properties"] as? JsonObject ?: return null
                    if (properties.size > MAX_FIELDS) return null
                    val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
                    val fields = properties.map { (key, value) ->
                        fieldOf(key, value as? JsonObject ?: return null, key in required) ?: return null
                    }
                    Form(serverId, serverName, serverLocation, toolName, message, runKey, fields)
                }
                "url" -> {
                    val raw = params.mcpString("url")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL_CHARS } ?: return null
                    val url = runCatching { Url(raw) }.getOrNull() ?: return null
                    if (url.protocol != URLProtocol.HTTPS && url.protocol != URLProtocol.HTTP) return null
                    if (!raw.startsWith("https://", ignoreCase = true) && !raw.startsWith("http://", ignoreCase = true)) return null
                    val host = url.host.takeIf(String::isNotBlank) ?: return null
                    val authority = raw.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                    val suspicious = url.protocol != URLProtocol.HTTPS ||
                        host.split('.').any { it.startsWith("xn--", ignoreCase = true) } ||
                        host.any { it.code > 0x7E } ||
                        '@' in authority
                    Link(serverId, serverName, serverLocation, toolName, message, runKey, raw, host, suspicious)
                }
                else -> null
            }
        }

        private fun fieldOf(key: String, schema: JsonObject, required: Boolean): McpElicitationField? {
            val title = (schema.mcpString("title")?.takeIf(String::isNotBlank) ?: key).take(200)
            val description = schema.mcpString("description")?.take(1_000)
            val looksSecret = SECRET_NAME.containsMatchIn(key) || SECRET_NAME.containsMatchIn(title)
            val default = schema["default"]
            return when (schema.mcpString("type")) {
                "string" -> {
                    val choices = choicesOf(schema)
                    if (choices != null) {
                        if (choices.isEmpty()) return null
                        McpElicitationField(
                            key, title, description, McpElicitationFieldKind.SingleChoice, required,
                            choices = choices,
                            initial = (default as? JsonPrimitive)?.takeIf { it.isString }?.content
                                ?.takeIf { value -> choices.any { it.value == value } }
                                ?.let { McpFieldInput.Choices(setOf(it)) },
                            looksSecret = looksSecret,
                        )
                    } else {
                        McpElicitationField(
                            key, title, description, McpElicitationFieldKind.Text, required,
                            format = schema.mcpString("format")?.takeIf { it in setOf("email", "uri", "date", "date-time") },
                            minLength = (schema["minLength"] as? JsonPrimitive)?.intOrNull,
                            maxLength = (schema["maxLength"] as? JsonPrimitive)?.intOrNull,
                            initial = (default as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(MAX_TEXT_CHARS)?.let { McpFieldInput.Text(it) },
                            looksSecret = looksSecret,
                        )
                    }
                }
                "number", "integer" -> McpElicitationField(
                    key, title, description,
                    if (schema.mcpString("type") == "integer") McpElicitationFieldKind.Integer else McpElicitationFieldKind.Number,
                    required,
                    minimum = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull,
                    maximum = (schema["maximum"] as? JsonPrimitive)?.doubleOrNull,
                    initial = (default as? JsonPrimitive)?.takeIf { !it.isString && it.doubleOrNull != null }?.content?.let { McpFieldInput.Text(it) },
                    looksSecret = looksSecret,
                )
                "boolean" -> McpElicitationField(
                    key, title, description, McpElicitationFieldKind.Flag, required,
                    initial = (default as? JsonPrimitive)?.booleanOrNull?.let { McpFieldInput.Flag(it) },
                    looksSecret = looksSecret,
                )
                "array" -> {
                    val items = schema["items"] as? JsonObject ?: return null
                    val choices = choicesOf(items)?.takeIf { it.isNotEmpty() } ?: return null
                    val defaults = (default as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        ?.filter { value -> choices.any { it.value == value } }
                    McpElicitationField(
                        key, title, description, McpElicitationFieldKind.MultiChoice, required,
                        choices = choices,
                        minItems = (schema["minItems"] as? JsonPrimitive)?.intOrNull,
                        maxItems = (schema["maxItems"] as? JsonPrimitive)?.intOrNull,
                        initial = defaults?.let { McpFieldInput.Choices(it.toSet()) },
                        looksSecret = looksSecret,
                    )
                }
                else -> null
            }
        }

        /** `enum` (with legacy `enumNames`), or `oneOf`/`anyOf` of `{const, title}`; null when neither. */
        private fun choicesOf(schema: JsonObject): List<McpElicitationChoice>? {
            (schema["enum"] as? JsonArray)?.let { values ->
                val names = (schema["enumNames"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
                return values.mapIndexedNotNull { index, element ->
                    val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapIndexedNotNull null
                    McpElicitationChoice(value, (names?.getOrNull(index) ?: value).take(200))
                }
            }
            val titled = (schema["oneOf"] ?: schema["anyOf"]) as? JsonArray ?: return null
            return titled.mapNotNull { element ->
                val option = element as? JsonObject ?: return@mapNotNull null
                val value = option.mcpString("const") ?: return@mapNotNull null
                McpElicitationChoice(value, (option.mcpString("title") ?: value).take(200))
            }
        }

        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val URI = Regex("^[A-Za-z][A-Za-z0-9+.-]*:\\S+$")
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})$")

        internal fun problemOf(field: McpElicitationField, input: McpFieldInput?): McpFieldProblem? = when (field.kind) {
            McpElicitationFieldKind.Text -> {
                val text = (input as? McpFieldInput.Text)?.text.orEmpty()
                when {
                    text.isEmpty() -> if (field.required) McpFieldProblem.Required else null
                    text.length > MAX_TEXT_CHARS || field.maxLength?.let { text.length > it } == true -> McpFieldProblem.TooLong
                    field.minLength?.let { text.length < it } == true -> McpFieldProblem.TooShort
                    field.format == "email" && !EMAIL.matches(text) -> McpFieldProblem.BadEmail
                    field.format == "uri" && !URI.matches(text) -> McpFieldProblem.BadUri
                    field.format == "date" && !DATE.matches(text) -> McpFieldProblem.BadDate
                    field.format == "date-time" && !DATE_TIME.matches(text) -> McpFieldProblem.BadDateTime
                    else -> null
                }
            }
            McpElicitationFieldKind.Number, McpElicitationFieldKind.Integer -> {
                val text = (input as? McpFieldInput.Text)?.text?.trim().orEmpty()
                val number = text.toDoubleOrNull()
                when {
                    text.isEmpty() -> if (field.required) McpFieldProblem.Required else null
                    number == null || number.isNaN() || number.isInfinite() -> McpFieldProblem.NotANumber
                    field.kind == McpElicitationFieldKind.Integer && text.toLongOrNull() == null -> McpFieldProblem.NotAnInteger
                    field.minimum?.let { number < it } == true -> McpFieldProblem.BelowMinimum
                    field.maximum?.let { number > it } == true -> McpFieldProblem.AboveMaximum
                    else -> null
                }
            }
            McpElicitationFieldKind.Flag -> null
            McpElicitationFieldKind.SingleChoice -> {
                val chosen = (input as? McpFieldInput.Choices)?.values.orEmpty()
                when {
                    chosen.isEmpty() -> if (field.required) McpFieldProblem.Required else null
                    chosen.size > 1 || chosen.any { value -> field.choices.none { it.value == value } } -> McpFieldProblem.NotAChoice
                    else -> null
                }
            }
            McpElicitationFieldKind.MultiChoice -> {
                val chosen = (input as? McpFieldInput.Choices)?.values.orEmpty()
                when {
                    chosen.any { value -> field.choices.none { it.value == value } } -> McpFieldProblem.NotAChoice
                    chosen.isEmpty() && field.required && (field.minItems ?: 1) > 0 -> McpFieldProblem.Required
                    chosen.isNotEmpty() && field.minItems?.let { chosen.size < it } == true -> McpFieldProblem.TooFewChoices
                    field.maxItems?.let { chosen.size > it } == true -> McpFieldProblem.TooManyChoices
                    else -> null
                }
            }
        }

        internal fun valueOf(field: McpElicitationField, input: McpFieldInput?): JsonElement? = when (field.kind) {
            McpElicitationFieldKind.Text -> (input as? McpFieldInput.Text)?.text?.takeIf(String::isNotEmpty)?.let { JsonPrimitive(it) }
            McpElicitationFieldKind.Number, McpElicitationFieldKind.Integer -> (input as? McpFieldInput.Text)?.text?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { text -> text.toLongOrNull()?.let { JsonPrimitive(it) } ?: text.toDoubleOrNull()?.let { JsonPrimitive(it) } }
            McpElicitationFieldKind.Flag -> JsonPrimitive((input as? McpFieldInput.Flag)?.on ?: false)
            McpElicitationFieldKind.SingleChoice -> (input as? McpFieldInput.Choices)?.values?.firstOrNull()?.let { JsonPrimitive(it) }
            McpElicitationFieldKind.MultiChoice -> (input as? McpFieldInput.Choices)?.values
                ?.takeIf { it.isNotEmpty() }
                ?.let { chosen -> JsonArray(field.choices.filter { it.value in chosen }.map { JsonPrimitive(it.value) }) }
        }
    }
}

/** Asks the keeper for what a server requested. Never throws for the keeper's refusal. */
fun interface McpElicitationGate {
    suspend fun elicit(request: McpElicitationRequest): McpElicitationResponse
}

/**
 * The elicitation dialog the app shows: one request at a time in [pending], answered with
 * [answer]. An unanswered request is cancelled after [timeoutMillis], so a run never waits on a
 * window nobody is looking at.
 */
class PromptingMcpElicitationGate(
    private val timeoutMillis: Long = 10 * 60_000L,
) : McpElicitationGate {
    private val queue = Mutex()
    private val current = MutableStateFlow<CompletableDeferred<McpElicitationResponse>?>(null)
    private val _pending = MutableStateFlow<McpElicitationRequest?>(null)

    /** The request waiting for the keeper, or null. */
    val pending: StateFlow<McpElicitationRequest?> = _pending.asStateFlow()

    override suspend fun elicit(request: McpElicitationRequest): McpElicitationResponse = queue.withLock {
        val deferred = CompletableDeferred<McpElicitationResponse>()
        current.value = deferred
        _pending.value = request
        try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() } ?: McpElicitationResponse.Cancel
        } finally {
            current.value = null
            _pending.value = null
        }
    }

    /** The keeper's answer to [pending]; ignored when nothing is waiting. */
    fun answer(response: McpElicitationResponse) {
        current.value?.complete(response)
    }
}
