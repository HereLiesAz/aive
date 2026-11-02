package com.hereliesaz.geministrator.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Streamable HTTP's `x-mcp-header` rule: a tool may mark primitive parameters, reachable from its
 * input schema's root through `properties` alone, to be mirrored as `Mcp-Param-{name}` headers. A
 * client must mirror them, and must drop a tool whose annotations break the rule.
 */
internal object McpParamHeaders {
    private const val ANNOTATION = "x-mcp-header"
    private val TOKEN = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
    private val PRIMITIVE_TYPES = setOf("string", "integer", "boolean")

    /** Schema keywords whose values are data, not subschemas. */
    private val DATA_KEYWORDS = setOf("enum", "const", "default", "examples")

    /** Why [schema]'s header annotations are invalid, or null when they are fine (or absent). */
    fun problem(schema: JsonObject): String? {
        val names = mutableSetOf<String>()
        return visit(schema, reachable = true, names)
    }

    private fun visit(node: JsonElement, reachable: Boolean, names: MutableSet<String>): String? {
        when (node) {
            is JsonArray -> node.forEach { child -> visit(child, false, names)?.let { return it } }
            is JsonObject -> {
                node[ANNOTATION]?.let { annotation ->
                    if (!reachable) return "$ANNOTATION is only allowed on a property reached through properties"
                    val name = (annotation as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (name == null || !TOKEN.matches(name)) return "$ANNOTATION must be an HTTP header token"
                    if (!names.add(name.lowercase())) return "$ANNOTATION $name is repeated"
                    val type = (node["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (type == null || type !in PRIMITIVE_TYPES) return "$ANNOTATION $name is on a parameter that is not a string, integer or boolean"
                }
                node.forEach { (key, value) ->
                    when {
                        key == ANNOTATION || key in DATA_KEYWORDS -> Unit
                        key == "properties" && value is JsonObject -> value.values.forEach { property ->
                            visit(property, reachable, names)?.let { return it }
                        }
                        value is JsonObject || value is JsonArray -> visit(value, false, names)?.let { return it }
                    }
                }
            }
            else -> Unit
        }
        return null
    }

    /** The `Mcp-Param-*` headers for a call of a tool with [schema] and [arguments]. */
    fun headersFor(schema: JsonObject, arguments: JsonObject): Map<String, String> = buildMap {
        collect(schema, arguments, this)
    }

    private fun collect(schema: JsonObject, value: JsonElement?, out: MutableMap<String, String>) {
        val properties = schema["properties"] as? JsonObject ?: return
        val instance = value as? JsonObject
        properties.forEach { (key, child) ->
            val childSchema = child as? JsonObject ?: return@forEach
            val childValue = instance?.get(key)
            val header = (childSchema[ANNOTATION] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (header != null) {
                val primitive = childValue as? JsonPrimitive
                if (primitive != null && primitive != JsonNull) {
                    out[McpProtocol.HEADER_PARAM_PREFIX + header] = McpJsonRpc.encodeHeaderValue(primitive.content)
                }
            }
            collect(childSchema, childValue, out)
        }
    }
}
