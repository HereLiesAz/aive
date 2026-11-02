package com.hereliesaz.geministrator.telemetry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OTLP/HTTP JSON encoding of an `ExportTraceServiceRequest` (opentelemetry-proto, JSON mapping):
 * ids are lowercase hex, enums are integers, 64-bit integers and nanosecond times are strings.
 */
object OtlpJson {
    const val SCOPE_NAME: String = "com.hereliesaz.aive.workflow"
    const val SERVICE_NAME: String = "aive"

    fun traceRequest(
        spans: List<OtelSpan>,
        resource: Map<String, OtelAttributeValue> = defaultResource(),
    ): JsonObject = buildJsonObject {
        putJsonArray("resourceSpans") {
            addJsonObject {
                putJsonObject("resource") {
                    put("attributes", attributes(resource))
                }
                putJsonArray("scopeSpans") {
                    addJsonObject {
                        putJsonObject("scope") { put("name", SCOPE_NAME) }
                        putJsonArray("spans") {
                            spans.forEach { add(span(it)) }
                        }
                    }
                }
            }
        }
    }

    fun defaultResource(): Map<String, OtelAttributeValue> = mapOf(
        "service.name" to OtelAttributeValue.Text(SERVICE_NAME),
    )

    fun span(span: OtelSpan): JsonObject = buildJsonObject {
        put("traceId", span.traceId)
        put("spanId", span.spanId)
        span.parentSpanId?.let { put("parentSpanId", it) }
        put("name", span.name)
        put("kind", span.kind.otlpValue)
        put("startTimeUnixNano", nanos(span.startEpochMillis))
        put("endTimeUnixNano", nanos(span.endEpochMillis))
        put("attributes", attributes(span.attributes))
        if (span.events.isNotEmpty()) {
            putJsonArray("events") {
                span.events.forEach { event ->
                    addJsonObject {
                        put("timeUnixNano", nanos(event.timeEpochMillis))
                        put("name", event.name)
                        put("attributes", attributes(event.attributes))
                    }
                }
            }
        }
        putJsonObject("status") {
            put("code", span.status.otlpValue)
            span.statusMessage?.let { put("message", it) }
        }
    }

    fun attributes(attributes: Map<String, OtelAttributeValue>): JsonArray = buildJsonArray {
        attributes.forEach { (key, value) ->
            addJsonObject {
                put("key", key)
                put("value", anyValue(value))
            }
        }
    }

    private fun anyValue(value: OtelAttributeValue): JsonObject = buildJsonObject {
        when (value) {
            is OtelAttributeValue.Text -> put("stringValue", value.value)
            is OtelAttributeValue.Integer -> put("intValue", value.value.toString())
            is OtelAttributeValue.Decimal -> put("doubleValue", JsonPrimitive(value.value))
            is OtelAttributeValue.Flag -> put("boolValue", value.value)
        }
    }

    private fun nanos(epochMillis: Long): String = (epochMillis * 1_000_000L).toString()
}
