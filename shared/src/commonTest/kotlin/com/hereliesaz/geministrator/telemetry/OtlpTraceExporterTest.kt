package com.hereliesaz.geministrator.telemetry

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.persistence.WorkflowEventRepository
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtlpTraceExporterTest {
    private val enabled = TraceExportSettings(
        enabled = true,
        endpoint = "https://collector.example.com:4318",
        headers = "x-api-key=abc123, x-tenant=hive",
        enabledAtEpochMillis = 0,
    )

    private fun exporter(
        settings: TraceExportSettings,
        requests: MutableList<HttpRequestData>,
        statuses: List<HttpStatusCode>,
    ): OtlpTraceExporter {
        val http = HttpClient(MockEngine { request ->
            requests += request
            respond("{}", statuses[(requests.size - 1).coerceAtMost(statuses.lastIndex)])
        })
        return OtlpTraceExporter(http, { settings }, OtlpRetryPolicy(maxAttempts = 4, initialDelayMillis = 10))
    }

    @Test
    fun theRequestIsAnOtlpJsonTraceExport() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val spans = WorkflowTraceBuilder.build(TraceSamples.completedRun())

        assertEquals(OtlpExportOutcome.Sent, exporter(enabled, requests, listOf(HttpStatusCode.OK)).export(spans))

        val request = requests.single()
        assertEquals("https://collector.example.com:4318/v1/traces", request.url.toString())
        assertEquals("abc123", request.headers["x-api-key"])
        assertEquals("hive", request.headers["x-tenant"])
        val body = request.body as TextContent
        assertEquals("application/json", body.contentType.toString().substringBefore(';'))

        val json = Json.parseToJsonElement(body.text).jsonObject
        val resource = json["resourceSpans"]!!.jsonArray.single().jsonObject
        val serviceName = resource["resource"]!!.jsonObject["attributes"]!!.jsonArray.single().jsonObject
        assertEquals("service.name", serviceName["key"]!!.jsonPrimitive.content)
        assertEquals("aive", serviceName["value"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        val scope = resource["scopeSpans"]!!.jsonArray.single().jsonObject
        assertEquals(OtlpJson.SCOPE_NAME, scope["scope"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val encoded = scope["spans"]!!.jsonArray.map { it.jsonObject }
        assertEquals(spans.size, encoded.size)

        val root = encoded.single { "parentSpanId" !in it }
        assertEquals(spans.first().traceId, root["traceId"]!!.jsonPrimitive.content)
        assertEquals(1, root["kind"]!!.jsonPrimitive.int)
        // 64-bit numbers are JSON strings in OTLP: nanosecond times and intValue attributes.
        assertTrue(root["startTimeUnixNano"]!!.jsonPrimitive.isString)
        assertEquals("1000000000", root["startTimeUnixNano"]!!.jsonPrimitive.content)
        assertEquals(1, root["status"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val chat = encoded.first { it["name"]!!.jsonPrimitive.content == "chat" }
        assertEquals(3, chat["kind"]!!.jsonPrimitive.int)
        val inputTokens = chat.attribute("gen_ai.usage.input_tokens")
        assertNotNull(inputTokens)
        assertTrue(inputTokens["intValue"]!!.jsonPrimitive.isString)
        assertEquals("100", inputTokens["intValue"]!!.jsonPrimitive.content)
        assertNotNull(chat.attribute("aive.usage.cost_usd")?.get("doubleValue"))
        assertEquals("anthropic", chat.attribute("gen_ai.provider.name")!!["stringValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun retryableFailuresAreRetriedWithBackoff() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val outcome = exporter(
            enabled,
            requests,
            listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests, HttpStatusCode.OK),
        ).export(WorkflowTraceBuilder.build(TraceSamples.completedRun()))
        assertEquals(OtlpExportOutcome.Sent, outcome)
        assertEquals(3, requests.size)
    }

    @Test
    fun aBatchIsDroppedAfterTheLastAttempt() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val outcome = exporter(enabled, requests, listOf(HttpStatusCode.BadGateway))
            .export(WorkflowTraceBuilder.build(TraceSamples.completedRun()))
        assertEquals(OtlpExportOutcome.GaveUp, outcome)
        assertEquals(4, requests.size)
    }

    @Test
    fun aRejectedBatchIsNotRetried() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val outcome = exporter(enabled, requests, listOf(HttpStatusCode.BadRequest))
            .export(WorkflowTraceBuilder.build(TraceSamples.completedRun()))
        assertEquals(OtlpExportOutcome.Rejected, outcome)
        assertEquals(1, requests.size)
    }

    @Test
    fun nothingIsSentWhileExportIsOff() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val outcome = exporter(enabled.copy(enabled = false), requests, listOf(HttpStatusCode.OK))
            .export(WorkflowTraceBuilder.build(TraceSamples.completedRun()))
        assertEquals(OtlpExportOutcome.Disabled, outcome)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun backoffDoublesUpToItsCap() {
        val policy = OtlpRetryPolicy(initialDelayMillis = 1_000, maxDelayMillis = 5_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (1..5).map(policy::delayBefore))
    }

    @Test
    fun endpointsMustBeHttpsExceptOnThisDevice() {
        assertEquals("https://otel.example.com/v1/traces", otlpTracesUrl("https://otel.example.com/"))
        assertEquals("https://otel.example.com/v1/traces", otlpTracesUrl(" https://otel.example.com/v1/traces "))
        assertEquals("http://localhost:4318/v1/traces", otlpTracesUrl("http://localhost:4318"))
        assertEquals("http://127.0.0.1:4318/v1/traces", otlpTracesUrl("http://127.0.0.1:4318"))
        assertNull(otlpTracesUrl(""))
        assertNull(otlpTracesUrl("http://otel.example.com"))
        assertNull(otlpTracesUrl("otel.example.com"))
        assertNull(otlpTracesUrl("ftp://otel.example.com"))
        assertNull(otlpTracesUrl("https://user:pass@otel.example.com"))
        assertNull(otlpTracesUrl("https://otel.example.com/?token=x"))
        assertNull(TraceExportSettings(enabled = false, endpoint = "https://otel.example.com").activeTracesUrl())
    }

    @Test
    fun headersParseAsNameValuePairs() {
        assertEquals(emptyMap<String, String>(), parseOtlpHeaders(""))
        assertEquals(
            mapOf("Authorization" to "Bearer a=b", "x-team" to "hive"),
            parseOtlpHeaders("Authorization=Bearer a=b,\n x-team = hive"),
        )
        assertNull(parseOtlpHeaders("no-separator"))
        assertNull(parseOtlpHeaders("=value"))
        assertNull(parseOtlpHeaders("bad name=value"))
        assertNull(parseOtlpHeaders("Content-Type=text/plain"))
        assertNull(parseOtlpHeaders("x-empty="))
    }

    @Test
    fun aFinishedRunIsExportedOnceAndOnlyIfItEndedAfterExportWasTurnedOn() = runTest {
        val store = TraceExportSettingsStore(MapSettings())
        val export = WorkflowTraceExport(store)
        val events = FixedEvents(TraceSamples.completedRun())
        val finished = WorkflowRun(
            id = TraceSamples.run,
            projectId = ProjectId("project-1"),
            workflowDefinitionId = WorkflowDefinitionId("starter"),
            objective = "objective",
            status = WorkflowRunStatus.Completed,
            taskRuns = emptyMap(),
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 4_300,
        )

        // Off by default.
        assertEquals(TraceExportSettings(), store.load())
        assertNull(export.spansIfDue(finished, events))

        store.save(enabled.copy(enabledAtEpochMillis = 5_000))
        assertNull(export.spansIfDue(finished, events), "a run that ended before export was turned on is not sent")

        store.save(enabled.copy(enabledAtEpochMillis = 4_000))
        assertNull(export.spansIfDue(finished.copy(status = WorkflowRunStatus.Running), events))
        val spans = export.spansIfDue(finished, events)
        assertNotNull(spans)
        assertEquals(6, spans.size)
        assertNull(export.spansIfDue(finished, events), "a run is exported at most once")
        assertFalse(store.isExported(TraceSamples.run.value), "not recorded before delivery")
        export.batchFinished(spans, OtlpExportOutcome.GaveUp)
        assertFalse(store.isExported(TraceSamples.run.value), "a batch that gave up leaves the run to be sent again")
        assertNotNull(export.spansIfDue(finished, events))
        export.batchFinished(spans, OtlpExportOutcome.Sent)
        assertTrue(store.isExported(TraceSamples.run.value))
        assertFalse(store.isExported("run-other"))
    }

    private fun JsonObject.attribute(key: String): JsonObject? =
        this["attributes"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["key"]!!.jsonPrimitive.content == key }
            ?.get("value")
            ?.jsonObject

    private class FixedEvents(private val events: List<WorkflowEvent>) : WorkflowEventRepository {
        override suspend fun append(event: WorkflowEvent) = Unit
        override suspend fun forRun(workflowRunId: WorkflowRunId): List<WorkflowEvent> =
            events.filter { it.workflowRunId == workflowRunId }
    }
}
