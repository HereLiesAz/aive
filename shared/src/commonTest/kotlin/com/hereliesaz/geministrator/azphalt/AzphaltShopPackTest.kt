package com.hereliesaz.geministrator.azphalt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzphaltShopPackTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun summary(id: String, kind: String, pack: String? = null, types: String = "[]"): AzphaltPackageSummary {
        val packField = pack?.let { ""","pack":$it""" }.orEmpty()
        return json.decodeFromString(
            """{"id":"$id","name":"$id","version":"1.0.0","kind":"$kind","types":$types$packField}""",
        )
    }

    @Test
    fun packNameReadsAStringOrAnObjectAndIgnoresAbsentOrNull() {
        assertEquals("Starter", summary("a", "mcp", "\"Starter\"").packName())
        assertEquals("Starter", summary("b", "mcp", """{"name":"Starter"}""").packName())
        assertEquals("starter-id", summary("c", "mcp", """{"id":"starter-id"}""").packName())
        assertNull(summary("d", "mcp", "null").packName())
        assertNull(summary("e", "mcp").packName())
        assertNull(summary("f", "mcp", "\"  \"").packName())
    }

    @Test
    fun packMembersOfAnyShownKindAppearButOtherToolsDoNot() {
        val list = listOf(
            summary("wf", "workflow"),
            summary("role", "role"),
            summary("tool-member", "mcp", "\"Starter\""),
            summary("tool-alone", "mcp"),
        )
        assertEquals(listOf("wf", "role", "tool-member"), list.forShop().map { it.id })
    }

    @Test
    fun neverShownKindsAreHiddenEvenInAPack() {
        val hidden = listOf("app", "script", "composable", "font", "editor").map { summary("h-$it", it, "\"Starter\"") }
        assertTrue(hidden.forShop().isEmpty())
        assertFalse(summary("fontAsset", "asset", types = """["font"]""").isShownInShop())
        assertTrue(summary("modelAsset", "asset", types = """["onnx"]""").isShownInShop())
    }

    @Test
    fun aPackRecordItselfIsNotListedItsMembersAre() {
        val list = listOf(summary("the-pack", "pack"), summary("member", "workflow", "\"The Pack\""))
        assertEquals(listOf("member"), list.forShop().map { it.id })
    }
}
