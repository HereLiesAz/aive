package com.hereliesaz.geministrator.events

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.log.runEventLines
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.hereliesaz.geministrator.telemetry.OtelAttributeValue
import com.hereliesaz.geministrator.telemetry.WorkflowTraceBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one per-model-call record says which model answered and what it was asked, never the prompt. */
class ModelCallRecordTest {
    private val json = SettingsWorkflowPersistence.defaultJson
    private val run = WorkflowRunId("run-1")

    private fun usage(model: String? = null, fingerprint: RequestFingerprint? = null) = ProviderUsageRecorded(
        workflowRunId = run,
        taskDefinitionId = TaskDefinitionId("build"),
        providerId = AgentProviderId("anthropic"),
        providerRunId = ProviderRunId("session-7"),
        inputTokens = 10,
        outputTokens = 5,
        model = model,
        requestSha256 = fingerprint?.sha256,
        requestChars = fingerprint?.chars,
        occurredAtEpochMillis = 100,
    )

    @Test
    fun theFingerprintIsTheSha256AndLengthOfTheTextAndNeverTheText() {
        val fingerprint = fingerprintRequestText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", fingerprint.sha256)
        assertEquals(3, fingerprint.chars)
        assertFalse(fingerprint.toString().contains("abc"))
    }

    @Test
    fun anEventPersistedBeforeTheFieldsExistedStillDecodesWithThemUnknown() {
        val encoded = json.encodeToJsonElement(WorkflowEvent.serializer(), usage("m", fingerprintRequestText("x"))) as JsonObject
        val old = JsonObject(encoded - setOf("model", "requestSha256", "requestChars", "modelConsistent"))
        val decoded = json.decodeFromJsonElement(WorkflowEvent.serializer(), old) as ProviderUsageRecorded
        assertNull(decoded.model)
        assertNull(decoded.modelConsistent)
        assertNull(decoded.requestSha256)
        assertNull(decoded.requestChars)
        assertEquals(10L, decoded.inputTokens)
        // Unknown keys from a newer build are ignored the same way.
        val newer = JsonObject(encoded + ("somethingLater" to JsonPrimitive(1)))
        assertEquals(
            "m",
            (json.decodeFromJsonElement(WorkflowEvent.serializer(), newer) as ProviderUsageRecorded).model,
        )
    }

    @Test
    fun theNewFieldsRoundTrip() {
        val event = usage("claude-sonnet-5-5", fingerprintRequestText("the prompt")).copy(modelConsistent=true)
        val decoded = json.decodeFromString(WorkflowEvent.serializer(), json.encodeToString(WorkflowEvent.serializer(), event))
        assertEquals(event, decoded)
    }

    @Test
    fun theEncodedEventHoldsNoPromptText() {
        val encoded = json.encodeToString(WorkflowEvent.serializer(), usage("m", fingerprintRequestText("SECRET PROMPT")))
        assertFalse(encoded.contains("SECRET PROMPT"))
    }

    @Test
    fun theRunLogLineShowsTheModelAndAShortFingerprint() {
        val fingerprint = fingerprintRequestText("hello")
        val line = runEventLines(listOf(usage("claude-sonnet-5-5", fingerprint)), taskName = { it.value }).single().text
        assertTrue("model claude-sonnet-5-5" in line, line)
        assertTrue("request ${fingerprint.sha256.take(8)}" in line, line)
        assertFalse(fingerprint.sha256 in line, "only a short fingerprint is shown")
        assertTrue("${fingerprint.chars} chars" in line, line)
    }

    @Test
    fun theRunLogLineSaysNothingAboutAModelAProviderDidNotExpose() {
        val line = runEventLines(listOf(usage()), taskName = { it.value }).single().text
        assertFalse("model" in line, line)
        assertFalse("request" in line, line)
    }

    @Test
    fun theChatSpanCarriesTheRequestModelWhenKnownAndOmitsItWhenNot() {
        fun chatAttributes(event: ProviderUsageRecorded) =
            WorkflowTraceBuilder.build(listOf(event)).first { it.name == "chat" }.attributes
        val known = chatAttributes(usage("claude-sonnet-5-5", fingerprintRequestText("p")))
        assertEquals(OtelAttributeValue.Text("claude-sonnet-5-5"), known["gen_ai.request.model"])
        assertFalse(known.keys.any { "prompt" in it })
        assertNull(chatAttributes(usage())["gen_ai.request.model"])
    }
}
