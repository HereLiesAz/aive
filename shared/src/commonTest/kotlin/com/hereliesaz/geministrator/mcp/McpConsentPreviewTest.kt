package com.hereliesaz.geministrator.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpConsentPreviewTest {
    @Test
    fun shortArgumentsAreShownWhole() {
        val arguments = buildJsonObject { put("a", "b") }
        assertEquals(arguments.toString(), mcpArgumentsPreview(arguments))
    }

    @Test
    fun longArgumentsShowTheirSizeAndAFingerprintOfTheTail() {
        val one = buildJsonObject { put("a", "x".repeat(700) + "one") }
        val two = buildJsonObject { put("a", "x".repeat(700) + "two") }
        val preview = mcpArgumentsPreview(one)
        assertTrue("characters in all" in preview)
        assertTrue("${one.toString().length} characters" in preview)
        assertNotEquals(preview, mcpArgumentsPreview(two))
    }

    @Test
    fun destructiveIsJudgedByNameOrHint() {
        assertTrue(mcpLooksDestructive("delete_file", null))
        assertTrue(mcpLooksDestructive("do_it", JsonObject(mapOf("destructiveHint" to JsonPrimitive(true)))))
        assertFalse(mcpLooksDestructive("list_files", null))
        assertFalse(mcpLooksDestructive("undeleted_items_count", null))
    }
}
