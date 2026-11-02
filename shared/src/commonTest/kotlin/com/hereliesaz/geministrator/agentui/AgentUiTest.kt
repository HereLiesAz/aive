package com.hereliesaz.geministrator.agentui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentUiTest {
    private val components = """
        {"id": "root", "component": {"Column": {"children": {"explicitList": ["title", "plan", "env", "replicas", "costs", "buttons"]}}}},
        {"id": "title", "component": {"Text": {"text": {"literalString": "Deploy?"}, "usageHint": "h2"}}},
        {"id": "plan", "component": {"MultipleChoice": {"selections": {"literalArray": ["small"]}, "options": [{"label": {"literalString": "Small"}, "value": "small"}, {"label": {"literalString": "Large"}, "value": "large"}], "maxAllowedSelections": 1}}},
        {"id": "env", "component": {"TextField": {"label": {"literalString": "Environment"}, "text": {"literalString": "staging"}}}},
        {"id": "replicas", "component": {"TextField": {"label": {"literalString": "Replicas"}, "textFieldType": "number"}}},
        {"id": "costs", "component": {"Table": {"columns": {"literalArray": ["Plan", "Cost"]}, "rows": [{"literalArray": ["Small", "5"]}, {"literalArray": ["Large", "20"]}]}}},
        {"id": "buttons", "component": {"Row": {"children": {"explicitList": ["yes", "no"]}}}},
        {"id": "yes", "component": {"Button": {"child": "yes-label", "primary": true, "action": {"name": "confirm"}}}},
        {"id": "yes-label", "component": {"Text": {"text": {"literalString": "Deploy"}}}},
        {"id": "no", "component": {"Button": {"child": "no-label", "action": {"name": "decline"}}}},
        {"id": "no-label", "component": {"Text": {"text": {"literalString": "Not now"}}}}
    """.trimIndent()

    private fun fenced(messages: String, lead: String = "I need a decision before deploying.") =
        "$lead\n```a2ui\n$messages\n```"

    private val valid = fenced(
        """
        {"surfaceUpdate": {"surfaceId": "deploy", "components": [$components]}}
        {"beginRendering": {"surfaceId": "deploy", "root": "root", "catalogId": "aive.keeper.v1"}}
        """.trimIndent(),
    )

    private fun request(message: String = valid): AgentUiRequest =
        assertIs<AgentUiParse.Request>(AgentUi.parse(message)).request

    private fun rejection(message: String): AgentUiParse.Invalid = assertIs<AgentUiParse.Invalid>(AgentUi.parse(message))

    private fun oneComponent(component: String): String = fenced(
        """{"surfaceUpdate": {"surfaceId": "s", "components": [{"id": "root", "component": $component}]}}""",
    )

    @Test
    fun theWholeCatalogIsAccepted() {
        val request = request()
        assertEquals("deploy", request.surfaceId)
        assertEquals("root", request.rootId)
        assertEquals("I need a decision before deploying.", request.lead)
        assertEquals(11, request.components.size)
        assertEquals(AgentUiComponent.Text("title", "Deploy?", AgentUiTextStyle.Heading), request.components["title"])
        val plan = assertIs<AgentUiComponent.Choice>(request.components["plan"])
        assertEquals(1, plan.maxSelections)
        assertEquals(listOf("small"), plan.initial)
        assertEquals(listOf(AgentUiOption("Small", "small"), AgentUiOption("Large", "large")), plan.options)
        assertEquals(
            AgentUiComponent.Field("replicas", "Replicas", AgentUiFieldKind.Number, ""),
            request.components["replicas"],
        )
        assertEquals(
            AgentUiComponent.Table("costs", listOf("Plan", "Cost"), listOf(listOf("Small", "5"), listOf("Large", "20"))),
            request.components["costs"],
        )
        assertEquals(listOf("yes", "no"), request.actions().map { it.id })
        assertEquals(listOf("plan", "env", "replicas"), request.inputs().map { it.id })
    }

    @Test
    fun aBareJsonArrayIsARequestAndRootDefaultsToRoot() {
        val request = request("""[{"surfaceUpdate": {"surfaceId": "s", "components": [{"id": "root", "component": {"Text": {"text": {"literalString": "Hi"}}}}]}}]""")
        assertEquals("root", request.rootId)
        assertEquals("", request.lead)
    }

    @Test
    fun ordinaryTextIsNotARequest() {
        assertEquals(AgentUiParse.None, AgentUi.parse("I finished the change; tests pass."))
        assertEquals(AgentUiParse.None, AgentUi.parse("```kotlin\nval x = 1\n```"))
    }

    @Test
    fun anUnknownComponentIsRejectedAndShownAsText() {
        val message = oneComponent("""{"Image": {"url": {"literalString": "https://example.com/x.png"}}}""")
        val invalid = rejection(message)
        assertEquals(AgentUiRejection.UnknownComponent, invalid.reason)
        assertEquals(message, invalid.text)
    }

    @Test
    fun anUnknownPropertyIsRejected() {
        val invalid = rejection(oneComponent("""{"Text": {"text": {"literalString": "x"}, "onClick": "fetch('x')"}}"""))
        assertEquals(AgentUiRejection.UnsupportedProperty, invalid.reason)
    }

    @Test
    fun anOversizedMessageIsRejected() {
        val padding = "x".repeat(AgentUi.MAX_MESSAGE_CHARS)
        assertEquals(AgentUiRejection.Oversized, rejection(fenced("{}", lead = padding)).reason)
    }

    @Test
    fun aDeeplyNestedPayloadIsRejected() {
        val deep = "[".repeat(40) + "]".repeat(40)
        assertEquals(AgentUiRejection.TooDeep, rejection(fenced(deep)).reason)
    }

    @Test
    fun containersNestedTooDeepAreRejected() {
        // root > c1 > c2 > c3 > c4: five containers, one more than allowed.
        val chain = (0..4).joinToString(",") { level ->
            val id = if (level == 0) "root" else "c$level"
            val child = if (level == 4) "leaf" else "c${level + 1}"
            """{"id": "$id", "component": {"Column": {"children": {"explicitList": ["$child"]}}}}"""
        }
        val leaf = """{"id": "leaf", "component": {"Text": {"text": {"literalString": "deep"}}}}"""
        val message = fenced("""{"surfaceUpdate": {"surfaceId": "s", "components": [$chain, $leaf]}}""")
        assertEquals(AgentUiRejection.TooDeep, rejection(message).reason)
    }

    @Test
    fun tooManyComponentsAreRejected() {
        val ids = (1..AgentUi.MAX_COMPONENTS).map { "t$it" }
        val texts = ids.joinToString(",") { """{"id": "$it", "component": {"Text": {"text": {"literalString": "x"}}}}""" }
        val root = """{"id": "root", "component": {"Column": {"children": {"explicitList": [${ids.joinToString(",") { "\"$it\"" }}]}}}}"""
        val message = fenced("""{"surfaceUpdate": {"surfaceId": "s", "components": [$root, $texts]}}""")
        assertEquals(AgentUiRejection.TooManyComponents, rejection(message).reason)
    }

    @Test
    fun brokenReferencesAreRejected() {
        val missing = fenced("""{"surfaceUpdate": {"surfaceId": "s", "components": [{"id": "root", "component": {"Column": {"children": {"explicitList": ["ghost"]}}}}]}}""")
        assertEquals(AgentUiRejection.BadReference, rejection(missing).reason)

        val cycle = fenced(
            """{"surfaceUpdate": {"surfaceId": "s", "components": [
                {"id": "root", "component": {"Column": {"children": {"explicitList": ["a"]}}}},
                {"id": "a", "component": {"Column": {"children": {"explicitList": ["root"]}}}}
            ]}}""",
        )
        assertEquals(AgentUiRejection.BadReference, rejection(cycle).reason)

        val orphan = fenced(
            """{"surfaceUpdate": {"surfaceId": "s", "components": [
                {"id": "root", "component": {"Text": {"text": {"literalString": "a"}}}},
                {"id": "stray", "component": {"Text": {"text": {"literalString": "b"}}}}
            ]}}""",
        )
        assertEquals(AgentUiRejection.BadReference, rejection(orphan).reason)

        val noRoot = fenced("""{"surfaceUpdate": {"surfaceId": "s", "components": [{"id": "top", "component": {"Text": {"text": {"literalString": "a"}}}}]}}""")
        assertEquals(AgentUiRejection.BadReference, rejection(noRoot).reason)
    }

    @Test
    fun whatTheSubsetLeavesOutIsRejected() {
        // Shown text needs a literal: there is no data model.
        assertEquals(
            AgentUiRejection.UnsupportedProperty,
            rejection(oneComponent("""{"Text": {"text": {"path": "/user/name"}}}""")).reason,
        )
        // Secrets and long text are not asked for.
        assertEquals(
            AgentUiRejection.UnsupportedProperty,
            rejection(oneComponent("""{"TextField": {"label": {"literalString": "Password"}, "textFieldType": "obscured"}}""")).reason,
        )
        // An action carries no context of its own.
        val withContext = fenced(
            """{"surfaceUpdate": {"surfaceId": "s", "components": [
                {"id": "root", "component": {"Button": {"child": "l", "action": {"name": "go", "context": [{"key": "k", "value": {"path": "/x"}}]}}}},
                {"id": "l", "component": {"Text": {"text": {"literalString": "Go"}}}}
            ]}}""",
        )
        assertEquals(AgentUiRejection.UnsupportedProperty, rejection(withContext).reason)
        // Only surfaceUpdate and beginRendering are understood.
        assertEquals(
            AgentUiRejection.UnsupportedMessage,
            rejection(fenced("""{"dataModelUpdate": {"surfaceId": "s", "contents": []}}""")).reason,
        )
        // Another catalog is not this host's.
        val foreignCatalog = fenced(
            """{"surfaceUpdate": {"surfaceId": "s", "components": [{"id": "root", "component": {"Text": {"text": {"literalString": "a"}}}}]}}
            {"beginRendering": {"surfaceId": "s", "root": "root", "catalogId": "https://example.com/catalog.json"}}""",
        )
        assertEquals(AgentUiRejection.UnsupportedProperty, rejection(foreignCatalog).reason)
        assertEquals(AgentUiRejection.Malformed, rejection(fenced("{not json")).reason)
    }

    @Test
    fun theAnswerIsAnA2uiUserAction() {
        val request = request()
        val confirm = request.actions().first { it.name == "confirm" }
        val message = AgentUi.encodeAnswer(
            request,
            confirm,
            mapOf(
                "plan" to AgentUiValue.Choices(listOf("large")),
                "replicas" to AgentUiValue.Text(" 3 "),
            ),
            timestamp = "2026-11-02T10:00:00Z",
        )
        val lines = message.lines()
        assertEquals(AgentUi.ANSWER_HEADER, lines.first())
        val userAction = Json.parseToJsonElement(lines.drop(1).joinToString("\n")).jsonObject["userAction"] as JsonObject
        assertEquals(JsonPrimitive("confirm"), userAction["name"])
        assertEquals(JsonPrimitive("deploy"), userAction["surfaceId"])
        assertEquals(JsonPrimitive("yes"), userAction["sourceComponentId"])
        assertEquals(JsonPrimitive("2026-11-02T10:00:00Z"), userAction["timestamp"])
        val context = userAction["context"] as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("large"))), context["plan"])
        // An untouched input answers with its initial value.
        assertEquals(JsonPrimitive("staging"), context["env"])
        assertEquals(JsonPrimitive(3L), context["replicas"])
        assertEquals(setOf("plan", "env", "replicas"), context.keys)
    }

    @Test
    fun anEmptyNumberAnswersNull() {
        val request = request()
        val message = AgentUi.encodeAnswer(request, request.actions().last(), emptyMap(), "t")
        val userAction = Json.parseToJsonElement(message.substringAfter('\n')).jsonObject["userAction"] as JsonObject
        assertEquals(JsonNull, (userAction["context"] as JsonObject)["replicas"])
        assertEquals(JsonPrimitive("decline"), userAction["name"])
    }

    @Test
    fun anAnswerThatDoesNotFitItsQuestionIsRefused() {
        val request = request()
        val confirm = request.actions().first()
        assertFailsWith<IllegalArgumentException> {
            AgentUi.encodeAnswer(request, confirm, mapOf("plan" to AgentUiValue.Choices(listOf("huge"))), "t")
        }
        assertFailsWith<IllegalArgumentException> {
            AgentUi.encodeAnswer(request, confirm, mapOf("plan" to AgentUiValue.Choices(listOf("small", "large"))), "t")
        }
        assertFailsWith<IllegalArgumentException> {
            AgentUi.encodeAnswer(request, confirm, mapOf("replicas" to AgentUiValue.Text("three")), "t")
        }
        assertFailsWith<IllegalArgumentException> {
            AgentUi.encodeAnswer(request, confirm, mapOf("title" to AgentUiValue.Text("x")), "t")
        }
        assertFailsWith<IllegalArgumentException> {
            AgentUi.encodeAnswer(request, AgentUiComponent.Action("forged", "yes-label", "confirm", true), emptyMap(), "t")
        }
    }

    @Test
    fun aPlainAnswerCarriesItsHeader() {
        assertEquals("${AgentUi.PLAIN_ANSWER_HEADER}\nUse staging.", AgentUi.encodePlainAnswer("  Use staging. "))
    }

    @Test
    fun aStoredQuestionThatNoLongerReadsAsARequestIsShownAsText() {
        assertIs<AgentUiParse.Invalid>(AgentUi.parseStored("just words"))
        assertIs<AgentUiParse.Request>(AgentUi.parseStored(valid))
    }

    @Test
    fun aQuestionKeyIsStableAndDistinct() {
        assertEquals(AgentUi.questionKey(valid), AgentUi.questionKey(valid))
        assertNotEquals(AgentUi.questionKey(valid), AgentUi.questionKey("$valid "))
        assertEquals(16, AgentUi.questionKey("").length)
    }

    @Test
    fun thePromptNamesTheCatalogAndTheAnswerShape() {
        assertTrue(AgentUi.CATALOG_ID in AgentUi.promptInstruction)
        assertTrue(AgentUi.ANSWER_HEADER in AgentUi.promptInstruction)
        listOf("Text", "Column", "Row", "MultipleChoice", "TextField", "Button", "Table").forEach {
            assertTrue(it in AgentUi.promptInstruction, it)
        }
    }
}
