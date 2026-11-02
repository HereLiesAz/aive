package com.hereliesaz.geministrator.agentui

import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Declarative questions an agent asks the keeper (docs/Agent-UI.md).
 *
 * An agent asks for something richer than text by putting an A2UI-shaped request (a flat list of
 * components that refer to each other by id) in a message. The host renders it only from its own
 * fixed catalog: [AgentUiComponent]. Anything else (an unknown component or property, a payload too
 * large or too deep, a broken reference) is rejected and the message is shown as plain text. Nothing
 * in a request is ever executed, fetched or rendered as HTML, a link or an image.
 */
object AgentUi {
    /** The catalog id an agent may name in `beginRendering.catalogId`. */
    const val CATALOG_ID: String = "aive.keeper.v1"

    /** The fence info string that marks a request inside a message. */
    const val FENCE_TAG: String = "a2ui"

    /** A message carrying a request may be at most this long, prose included. */
    const val MAX_MESSAGE_CHARS: Int = 16_000
    const val MAX_COMPONENTS: Int = 64
    /** Containers (Column, Row) nested inside one another, the root counting as one. */
    const val MAX_TREE_DEPTH: Int = 4
    /** Objects and arrays nested inside one another in the JSON payload. */
    const val MAX_JSON_DEPTH: Int = 12
    const val MAX_TEXT_CHARS: Int = 2_000
    const val MAX_OPTIONS: Int = 24
    const val MAX_TABLE_COLUMNS: Int = 8
    const val MAX_TABLE_ROWS: Int = 50

    /** First line of every structured answer sent back to the agent. */
    const val ANSWER_HEADER: String = "The keeper answered your question (A2UI userAction):"

    /** First line of a plain-text answer to a question that could not be shown as a form. */
    const val PLAIN_ANSWER_HEADER: String = "The keeper answered your question:"

    /**
     * What every messaging-capable agent is told about asking the keeper. It travels in the task's
     * prompt context beside its role (labelled [PROMPT_LABEL]).
     */
    const val PROMPT_LABEL: String = "Asking the keeper"

    val promptInstruction: String = """
        When you need a decision or input from the keeper (the person running this work) that plain text cannot ask well, send one message containing a fenced block tagged $FENCE_TAG with an A2UI v0.8 surface, then wait for the reply. Only this subset is rendered; anything else is shown to the keeper as plain text:
        - Messages: one or more {"surfaceUpdate": {"surfaceId", "components": [...]}} and an optional {"beginRendering": {"surfaceId", "root", "catalogId": "$CATALOG_ID"}}. The root defaults to the component with id "root".
        - Each component is {"id": "...", "component": {"<Type>": {...}}}; children refer to ids. Every component must be reachable from the root exactly once.
        - Text: {"text": {"literalString": "..."}, "usageHint": "h1"|"h2"|"h3"|"h4"|"h5"|"caption"|"body"}
        - Column / Row: {"children": {"explicitList": ["id", ...]}}, nested at most $MAX_TREE_DEPTH deep.
        - MultipleChoice: {"selections": {"literalArray": []}, "options": [{"label": {"literalString": "..."}, "value": "..."}], "maxAllowedSelections": 1} (1 for a single choice).
        - TextField: {"label": {"literalString": "..."}, "text": {"literalString": ""}, "textFieldType": "shortText"|"number"}
        - Button (a confirm or other action): {"child": "<id of a Text>", "primary": true, "action": {"name": "..."}}. No action context.
        - Table (read-only): {"columns": {"literalArray": ["..."]}, "rows": [{"literalArray": ["..."]}]}, at most $MAX_TABLE_COLUMNS columns and $MAX_TABLE_ROWS rows.
        No images, links, HTML, data bindings or other components. At most $MAX_COMPONENTS components and $MAX_MESSAGE_CHARS characters. The answer comes back as a message starting "$ANSWER_HEADER" followed by {"userAction": {"name", "surfaceId", "sourceComponentId", "timestamp", "context": {"<input id>": value}}}.
    """.trimIndent()

    private val fence = Regex("(?s)(```|~~~)[ \\t]*$FENCE_TAG[ \\t]*\\r?\\n(.*?)\\r?\\n[ \\t]*\\1")
    private val idPattern = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val pathPattern = Regex("^/[A-Za-z0-9_./-]{0,127}$")
    private val json = Json

    /**
     * Whether [message] carries a request at all: a fenced `a2ui` block, or a message that is itself
     * an A2UI JSON payload. Cheap; does not validate.
     */
    fun isRequest(message: String): Boolean = extract(message) != null

    /** Parses and validates [message]. */
    fun parse(message: String): AgentUiParse {
        val extracted = extract(message) ?: return AgentUiParse.None
        val lead = extracted.lead.take(MAX_TEXT_CHARS)
        fun invalid(reason: AgentUiRejection, detail: String) =
            AgentUiParse.Invalid(reason, detail, message.take(MAX_MESSAGE_CHARS))
        if (message.length > MAX_MESSAGE_CHARS) {
            return invalid(AgentUiRejection.Oversized, "${message.length} characters, at most $MAX_MESSAGE_CHARS")
        }
        if (jsonDepth(extracted.payload) > MAX_JSON_DEPTH) {
            return invalid(AgentUiRejection.TooDeep, "JSON nested deeper than $MAX_JSON_DEPTH")
        }
        val messages = try {
            parseMessages(extracted.payload)
        } catch (failure: IllegalArgumentException) {
            return invalid(AgentUiRejection.Malformed, failure.message ?: "not JSON")
        }
        return try {
            AgentUiParse.Request(Validator(lead).build(messages))
        } catch (failure: AgentUiInvalid) {
            invalid(failure.reason, failure.detail)
        }
    }

    /**
     * A question as it is shown: its form when valid, otherwise its text. A stored question that no
     * longer reads as a request (cut short, say) is shown as text too.
     */
    fun parseStored(message: String): AgentUiParse = when (val parsed = parse(message)) {
        AgentUiParse.None -> AgentUiParse.Invalid(AgentUiRejection.Malformed, "not a request", message.take(MAX_MESSAGE_CHARS))
        else -> parsed
    }

    /** A stable identity for a question's text, so a replayed question that was answered stays answered. */
    fun questionKey(message: String): String {
        var hash = 0xcbf29ce484222325uL
        message.forEach { char ->
            hash = hash xor char.code.toULong()
            hash *= 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    /**
     * The keeper's answer to [request], as an A2UI `userAction` the agent receives through the
     * ordinary messaging path. [values] holds the inputs' values by component id; every input is
     * included, an untouched one with its initial value.
     *
     * @throws IllegalArgumentException when [action] is not one of the request's actions or a value
     * does not fit its input.
     */
    fun encodeAnswer(
        request: AgentUiRequest,
        action: AgentUiComponent.Action,
        values: Map<String, AgentUiValue>,
        timestamp: String,
    ): String {
        require(request.components[action.id] == action) { "Action ${action.id} is not part of the question" }
        values.keys.forEach { id ->
            val component = request.components[id]
            require(component is AgentUiComponent.Choice || component is AgentUiComponent.Field) {
                "$id is not an input of the question"
            }
        }
        val context = buildJsonObject {
            request.inputs().forEach { input ->
                when (input) {
                    is AgentUiComponent.Choice -> {
                        val chosen = when (val value = values[input.id]) {
                            null -> AgentUiValue.Choices(input.initial)
                            is AgentUiValue.Choices -> value
                            is AgentUiValue.Text -> throw IllegalArgumentException("${input.id} takes choices")
                        }
                        val allowed = input.options.map(AgentUiOption::value).toSet()
                        require(chosen.values.all { it in allowed }) { "${input.id} has an unknown choice" }
                        require(chosen.values.distinct().size <= input.maxSelections) {
                            "${input.id} takes at most ${input.maxSelections}"
                        }
                        put(input.id, buildJsonArray { chosen.values.distinct().forEach { add(JsonPrimitive(it)) } })
                    }
                    is AgentUiComponent.Field -> {
                        val text = when (val value = values[input.id]) {
                            null -> input.initial
                            is AgentUiValue.Text -> value.text
                            is AgentUiValue.Choices -> throw IllegalArgumentException("${input.id} takes text")
                        }.trim()
                        require(text.length <= MAX_TEXT_CHARS) { "${input.id} is too long" }
                        when (input.kind) {
                            AgentUiFieldKind.ShortText -> put(input.id, text)
                            AgentUiFieldKind.Number -> {
                                if (text.isEmpty()) {
                                    put(input.id, JsonNull)
                                } else {
                                    put(input.id, numberPrimitive(text) ?: throw IllegalArgumentException("${input.id} is not a number"))
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        val userAction = buildJsonObject {
            put(
                "userAction",
                buildJsonObject {
                    put("name", action.name)
                    put("surfaceId", request.surfaceId)
                    put("sourceComponentId", action.id)
                    put("timestamp", timestamp)
                    put("context", context)
                },
            )
        }
        return "$ANSWER_HEADER\n$userAction"
    }

    /** A plain-text answer to a question that could not be shown as a form. */
    fun encodePlainAnswer(text: String): String = "$PLAIN_ANSWER_HEADER\n${text.trim()}"

    /** Whether [text] is a number a Number field accepts. */
    fun isNumber(text: String): Boolean = numberPrimitive(text.trim()) != null

    private fun numberPrimitive(text: String): JsonPrimitive? {
        if (!Regex("^-?(0|[1-9][0-9]{0,17})(\\.[0-9]{1,12})?$").matches(text)) return null
        text.toLongOrNull()?.let { return JsonPrimitive(it) }
        val double = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return JsonPrimitive(double)
    }

    private class Extracted(val payload: String, val lead: String)

    private fun extract(message: String): Extracted? {
        fence.find(message)?.let { match ->
            val lead = (message.substring(0, match.range.first) + "\n" + message.substring(match.range.last + 1)).trim()
            return Extracted(match.groupValues[2].trim(), lead)
        }
        val trimmed = message.trim()
        val startsLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[")
        return if (startsLikeJson && "\"surfaceUpdate\"" in trimmed) Extracted(trimmed, "") else null
    }

    /** The deepest nesting of objects and arrays in [payload], ignoring brackets inside strings. */
    internal fun jsonDepth(payload: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        payload.forEach { char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > deepest) deepest = depth
                    }
                    '}', ']' -> depth--
                }
            }
        }
        return deepest
    }

    /**
     * The payload's messages: one or more JSON values one after another (an object, an array of
     * objects, or A2UI's JSON Lines, a message spanning lines or not), flattened to message objects.
     */
    private fun parseMessages(payload: String): List<JsonObject> {
        val elements = topLevelValues(payload).flatMap { value ->
            val element = runCatching { json.parseToJsonElement(value) }.getOrElse {
                throw IllegalArgumentException("not JSON")
            }
            if (element is JsonArray) element.toList() else listOf(element)
        }
        require(elements.isNotEmpty()) { "no messages" }
        return elements.map { element ->
            element as? JsonObject ?: throw IllegalArgumentException("a message is not an object")
        }
    }

    /** Cuts [payload] into its top-level JSON objects and arrays; anything else between them is malformed. */
    private fun topLevelValues(payload: String): List<String> {
        val values = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        payload.forEachIndexed { index, char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when {
                char == '"' -> {
                    require(depth > 0) { "text outside a JSON value" }
                    inString = true
                }
                char == '{' || char == '[' -> {
                    if (depth == 0) start = index
                    depth++
                }
                char == '}' || char == ']' -> {
                    require(depth > 0) { "unbalanced brackets" }
                    depth--
                    if (depth == 0) values += payload.substring(start, index + 1)
                }
                depth == 0 -> require(char.isWhitespace() || char == ',') { "text outside a JSON value" }
            }
        }
        require(depth == 0 && !inString) { "unclosed JSON value" }
        return values
    }

    private class AgentUiInvalid(val reason: AgentUiRejection, val detail: String) : Exception(detail)

    private fun fail(reason: AgentUiRejection, detail: String): Nothing = throw AgentUiInvalid(reason, detail)

    private class Validator(private val lead: String) {
        private val components = linkedMapOf<String, AgentUiComponent>()

        fun build(messages: List<JsonObject>): AgentUiRequest {
            var surfaceId: String? = null
            var root: String? = null
            var sawUpdate = false
            fun surface(id: String) {
                if (surfaceId == null) surfaceId = id
                if (surfaceId != id) fail(AgentUiRejection.InvalidValue, "more than one surface")
            }
            messages.forEach { message ->
                message.forEach { (kind, body) ->
                    val content = body as? JsonObject ?: fail(AgentUiRejection.Malformed, "$kind is not an object")
                    when (kind) {
                        "surfaceUpdate" -> {
                            sawUpdate = true
                            strictKeys(content, "surfaceUpdate", required = setOf("surfaceId", "components"))
                            surface(id(content["surfaceId"], "surfaceId"))
                            val list = content["components"] as? JsonArray
                                ?: fail(AgentUiRejection.Malformed, "components is not a list")
                            list.forEach { entry -> component(entry) }
                        }
                        "beginRendering" -> {
                            strictKeys(content, "beginRendering", required = setOf("surfaceId", "root"), optional = setOf("catalogId"))
                            surface(id(content["surfaceId"], "surfaceId"))
                            root = id(content["root"], "root")
                            content["catalogId"]?.let { catalog ->
                                if (string(catalog, "catalogId", max = 256) != CATALOG_ID) {
                                    fail(AgentUiRejection.UnsupportedProperty, "catalogId must be $CATALOG_ID")
                                }
                            }
                        }
                        else -> fail(AgentUiRejection.UnsupportedMessage, kind)
                    }
                }
            }
            if (!sawUpdate) fail(AgentUiRejection.Malformed, "no surfaceUpdate")
            val rootId = root ?: "root"
            if (rootId !in components) fail(AgentUiRejection.BadReference, "root $rootId is not a component")
            checkTree(rootId)
            return AgentUiRequest(
                surfaceId = surfaceId!!,
                rootId = rootId,
                components = components.toMap(),
                lead = lead,
            )
        }

        private fun component(entry: JsonElement) {
            if (components.size >= MAX_COMPONENTS) fail(AgentUiRejection.TooManyComponents, "more than $MAX_COMPONENTS")
            val obj = entry as? JsonObject ?: fail(AgentUiRejection.Malformed, "a component is not an object")
            strictKeys(obj, "component", required = setOf("id", "component"))
            val id = id(obj["id"], "id")
            if (id in components) fail(AgentUiRejection.BadReference, "$id is defined twice")
            val wrapper = obj["component"] as? JsonObject ?: fail(AgentUiRejection.Malformed, "$id has no component")
            if (wrapper.size != 1) fail(AgentUiRejection.Malformed, "$id must name exactly one type")
            val (type, value) = wrapper.entries.single()
            val props = value as? JsonObject ?: fail(AgentUiRejection.Malformed, "$id: $type is not an object")
            components[id] = when (type) {
                "Text" -> {
                    strictKeys(props, type, required = setOf("text"), optional = setOf("usageHint"))
                    val hint = props["usageHint"]?.let { string(it, "usageHint", max = 16) }
                    val style = when (hint) {
                        null, "body" -> AgentUiTextStyle.Body
                        "h1", "h2", "h3", "h4", "h5" -> AgentUiTextStyle.Heading
                        "caption" -> AgentUiTextStyle.Caption
                        else -> fail(AgentUiRejection.InvalidValue, "$id: usageHint $hint")
                    }
                    AgentUiComponent.Text(id, literal(props["text"], "$id.text"), style)
                }
                "Column", "Row" -> {
                    strictKeys(props, type, required = setOf("children"), optional = setOf("alignment", "distribution"))
                    props["alignment"]?.let { string(it, "alignment", max = 32) }
                    props["distribution"]?.let { string(it, "distribution", max = 32) }
                    val children = props["children"] as? JsonObject ?: fail(AgentUiRejection.Malformed, "$id.children")
                    strictKeys(children, "$id.children", required = setOf("explicitList"))
                    val list = (children["explicitList"] as? JsonArray ?: fail(AgentUiRejection.Malformed, "$id.children.explicitList"))
                        .map { id(it, "$id child") }
                    if (type == "Column") AgentUiComponent.Column(id, list) else AgentUiComponent.Row(id, list)
                }
                "MultipleChoice" -> {
                    strictKeys(
                        props,
                        type,
                        required = setOf("selections", "options"),
                        optional = setOf("maxAllowedSelections", "variant", "filterable"),
                    )
                    val options = (props["options"] as? JsonArray ?: fail(AgentUiRejection.Malformed, "$id.options")).map { option ->
                        val o = option as? JsonObject ?: fail(AgentUiRejection.Malformed, "$id option")
                        strictKeys(o, "$id option", required = setOf("label", "value"))
                        AgentUiOption(literal(o["label"], "$id option label"), string(o["value"], "$id option value", max = 256))
                    }
                    if (options.isEmpty() || options.size > MAX_OPTIONS) {
                        fail(AgentUiRejection.InvalidValue, "$id needs 1 to $MAX_OPTIONS options")
                    }
                    if (options.map(AgentUiOption::value).toSet().size != options.size) {
                        fail(AgentUiRejection.InvalidValue, "$id repeats an option value")
                    }
                    val max = props["maxAllowedSelections"]?.let { int(it, "$id.maxAllowedSelections") } ?: options.size
                    if (max < 1) fail(AgentUiRejection.InvalidValue, "$id.maxAllowedSelections")
                    props["variant"]?.let {
                        val variant = string(it, "variant", max = 16)
                        if (variant != "checkbox" && variant != "chips") fail(AgentUiRejection.InvalidValue, "$id.variant")
                    }
                    props["filterable"]?.let { bool(it, "$id.filterable") }
                    val selections = boundArray(props["selections"], "$id.selections")
                    val allowed = options.map(AgentUiOption::value).toSet()
                    AgentUiComponent.Choice(
                        id = id,
                        options = options,
                        maxSelections = minOf(max, options.size),
                        initial = selections.filter { it in allowed }.distinct().take(minOf(max, options.size)),
                    )
                }
                "TextField" -> {
                    strictKeys(props, type, required = setOf("label"), optional = setOf("text", "textFieldType"))
                    val kind = when (val fieldType = props["textFieldType"]?.let { string(it, "textFieldType", max = 16) }) {
                        null, "shortText" -> AgentUiFieldKind.ShortText
                        "number" -> AgentUiFieldKind.Number
                        else -> fail(AgentUiRejection.UnsupportedProperty, "$id.textFieldType $fieldType")
                    }
                    val initial = props["text"]?.let { optionalLiteral(it, "$id.text") }.orEmpty()
                    if (kind == AgentUiFieldKind.Number && initial.isNotEmpty() && !isNumber(initial)) {
                        fail(AgentUiRejection.InvalidValue, "$id.text is not a number")
                    }
                    AgentUiComponent.Field(id, literal(props["label"], "$id.label"), kind, initial)
                }
                "Button" -> {
                    strictKeys(props, type, required = setOf("child", "action"), optional = setOf("primary"))
                    val action = props["action"] as? JsonObject ?: fail(AgentUiRejection.Malformed, "$id.action")
                    strictKeys(action, "$id.action", required = setOf("name"))
                    AgentUiComponent.Action(
                        id = id,
                        childId = id(props["child"], "$id.child"),
                        name = id(action["name"], "$id.action.name"),
                        primary = props["primary"]?.let { bool(it, "$id.primary") } ?: false,
                    )
                }
                "Table" -> {
                    strictKeys(props, type, required = setOf("columns", "rows"))
                    val columns = boundArray(props["columns"], "$id.columns")
                    if (columns.isEmpty() || columns.size > MAX_TABLE_COLUMNS) {
                        fail(AgentUiRejection.InvalidValue, "$id needs 1 to $MAX_TABLE_COLUMNS columns")
                    }
                    val rows = (props["rows"] as? JsonArray ?: fail(AgentUiRejection.Malformed, "$id.rows")).map { row ->
                        boundArray(row, "$id row").also {
                            if (it.size != columns.size) fail(AgentUiRejection.InvalidValue, "$id row width")
                        }
                    }
                    if (rows.size > MAX_TABLE_ROWS) fail(AgentUiRejection.InvalidValue, "$id has more than $MAX_TABLE_ROWS rows")
                    AgentUiComponent.Table(id, columns, rows)
                }
                else -> fail(AgentUiRejection.UnknownComponent, type)
            }
        }

        /** Every component is reachable from [rootId] exactly once, without cycles, not too deep. */
        private fun checkTree(rootId: String) {
            val seen = mutableSetOf<String>()
            fun visit(id: String, depth: Int) {
                val component = components[id] ?: fail(AgentUiRejection.BadReference, "$id does not exist")
                if (!seen.add(id)) fail(AgentUiRejection.BadReference, "$id is used more than once")
                when (component) {
                    is AgentUiComponent.Column, is AgentUiComponent.Row -> {
                        if (depth >= MAX_TREE_DEPTH) fail(AgentUiRejection.TooDeep, "containers nested deeper than $MAX_TREE_DEPTH")
                        component.childIds().forEach { visit(it, depth + 1) }
                    }
                    is AgentUiComponent.Action -> {
                        if (components[component.childId] !is AgentUiComponent.Text) {
                            fail(AgentUiRejection.BadReference, "${component.id}.child must be a Text")
                        }
                        visit(component.childId, depth)
                    }
                    is AgentUiComponent.Text, is AgentUiComponent.Choice,
                    is AgentUiComponent.Field, is AgentUiComponent.Table -> Unit
                }
            }
            visit(rootId, 0)
            val orphan = components.keys.firstOrNull { it !in seen }
            if (orphan != null) fail(AgentUiRejection.BadReference, "$orphan is not reachable from the root")
        }

        private fun strictKeys(obj: JsonObject, where: String, required: Set<String>, optional: Set<String> = emptySet()) {
            obj.keys.firstOrNull { it !in required && it !in optional }?.let {
                fail(AgentUiRejection.UnsupportedProperty, "$where.$it")
            }
            required.firstOrNull { it !in obj }?.let { fail(AgentUiRejection.Malformed, "$where needs $it") }
        }

        private fun string(element: JsonElement?, where: String, max: Int = MAX_TEXT_CHARS): String {
            val primitive = element as? JsonPrimitive
            if (primitive == null || primitive is JsonNull || !primitive.isString) {
                fail(AgentUiRejection.InvalidValue, "$where is not a string")
            }
            val text = primitive.content
            if (text.length > max) fail(AgentUiRejection.Oversized, "$where is longer than $max")
            return text
        }

        private fun id(element: JsonElement?, where: String): String {
            val text = string(element, where, max = 64)
            if (!idPattern.matches(text)) fail(AgentUiRejection.InvalidValue, "$where is not a valid id")
            return text
        }

        private fun int(element: JsonElement?, where: String): Int {
            val primitive = element as? JsonPrimitive
            val value = primitive?.takeIf { !it.isString && it !is JsonNull }?.content?.toIntOrNull()
            return value ?: fail(AgentUiRejection.InvalidValue, "$where is not a whole number")
        }

        private fun bool(element: JsonElement?, where: String): Boolean {
            val primitive = element as? JsonPrimitive
            return primitive?.takeIf { !it.isString }?.booleanOrNull ?: fail(AgentUiRejection.InvalidValue, "$where is not true or false")
        }

        /** A bound value that must carry a literal string: shown text needs no data model. */
        private fun literal(element: JsonElement?, where: String): String =
            optionalLiteral(element ?: fail(AgentUiRejection.Malformed, "$where is missing"), where)
                ?: fail(AgentUiRejection.UnsupportedProperty, "$where needs a literalString")

        private fun optionalLiteral(element: JsonElement, where: String): String? {
            val obj = element as? JsonObject ?: fail(AgentUiRejection.Malformed, "$where is not a bound value")
            strictKeys(obj, where, required = emptySet(), optional = setOf("literalString", "path"))
            obj["path"]?.let { path(it, "$where.path") }
            return obj["literalString"]?.let { string(it, "$where.literalString") }
        }

        private fun boundArray(element: JsonElement?, where: String): List<String> {
            val obj = element as? JsonObject ?: fail(AgentUiRejection.Malformed, "$where is not a bound list")
            strictKeys(obj, where, required = emptySet(), optional = setOf("literalArray", "path"))
            obj["path"]?.let { path(it, "$where.path") }
            val list = obj["literalArray"] ?: return emptyList()
            val array = list as? JsonArray ?: fail(AgentUiRejection.Malformed, "$where.literalArray is not a list")
            if (array.size > MAX_OPTIONS.coerceAtLeast(MAX_TABLE_COLUMNS)) fail(AgentUiRejection.Oversized, "$where is too long")
            return array.map { string(it, "$where item", max = 512) }
        }

        private fun path(element: JsonElement, where: String) {
            if (!pathPattern.matches(string(element, where, max = 128))) {
                fail(AgentUiRejection.InvalidValue, "$where is not a data path")
            }
        }
    }
}

/** The outcome of reading a message for a question. */
sealed interface AgentUiParse {
    /** The message asks nothing: ordinary agent chatter. */
    data object None : AgentUiParse

    /** A question the host renders from its catalog. */
    data class Request(val request: AgentUiRequest) : AgentUiParse

    /** A question that cannot be shown as a form; [text] is shown as plain text instead. */
    data class Invalid(val reason: AgentUiRejection, val detail: String, val text: String) : AgentUiParse
}

enum class AgentUiRejection {
    Oversized,
    TooDeep,
    Malformed,
    UnsupportedMessage,
    UnknownComponent,
    UnsupportedProperty,
    InvalidValue,
    TooManyComponents,
    BadReference,
}

/** A validated question: [components] by id, rendered from [rootId], after the agent's [lead] prose. */
data class AgentUiRequest(
    val surfaceId: String,
    val rootId: String,
    val components: Map<String, AgentUiComponent>,
    val lead: String = "",
) {
    /** The inputs whose values the answer carries, in definition order. */
    fun inputs(): List<AgentUiComponent> =
        components.values.filter { it is AgentUiComponent.Choice || it is AgentUiComponent.Field }

    /** The question's actions. When it has none, the host offers one Send ([DEFAULT_ACTION]). */
    fun actions(): List<AgentUiComponent.Action> = components.values.filterIsInstance<AgentUiComponent.Action>()

    companion object {
        /** The action name the host's own Send uses when a question names no action. */
        const val DEFAULT_ACTION: String = "submit"
    }
}

/** The host catalog: the only things a question can be made of. */
sealed interface AgentUiComponent {
    val id: String

    data class Text(override val id: String, val text: String, val style: AgentUiTextStyle) : AgentUiComponent

    data class Column(override val id: String, val children: List<String>) : AgentUiComponent

    data class Row(override val id: String, val children: List<String>) : AgentUiComponent

    /** A MultipleChoice: a single choice when [maxSelections] is 1. */
    data class Choice(
        override val id: String,
        val options: List<AgentUiOption>,
        val maxSelections: Int,
        val initial: List<String>,
    ) : AgentUiComponent

    /** A short text or number TextField. */
    data class Field(
        override val id: String,
        val label: String,
        val kind: AgentUiFieldKind,
        val initial: String,
    ) : AgentUiComponent

    /** A Button: a confirm or other answer, labelled by the Text [childId]. */
    data class Action(
        override val id: String,
        val childId: String,
        val name: String,
        val primary: Boolean,
    ) : AgentUiComponent

    /** A read-only table of plain text. */
    data class Table(override val id: String, val columns: List<String>, val rows: List<List<String>>) : AgentUiComponent
}

internal fun AgentUiComponent.childIds(): List<String> = when (this) {
    is AgentUiComponent.Column -> children
    is AgentUiComponent.Row -> children
    is AgentUiComponent.Text, is AgentUiComponent.Choice, is AgentUiComponent.Field,
    is AgentUiComponent.Action, is AgentUiComponent.Table -> emptyList()
}

data class AgentUiOption(val label: String, val value: String)

enum class AgentUiTextStyle { Heading, Body, Caption }

enum class AgentUiFieldKind { ShortText, Number }

/** A value the keeper gave one input. */
sealed interface AgentUiValue {
    data class Text(val text: String) : AgentUiValue
    data class Choices(val values: List<String>) : AgentUiValue
}

/**
 * True when the task's agent is waiting on the keeper's answer to a question it asked
 * (docs/Agent-UI.md): one of the waits that make a creature wait on you.
 */
fun TaskRun.awaitsKeeperAnswer(): Boolean =
    keeperQuestion != null &&
        (status == TaskRunStatus.Planning || status == TaskRunStatus.Running || status == TaskRunStatus.Verifying)
