package com.hereliesaz.geministrator.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpToolFilterTest {
    private fun offer(name: String) = McpToolOffer(name, "d", JsonObject(emptyMap()), "s", "S", name)

    private class Fake(override val offers: List<McpToolOffer>) : McpToolbox {
        val called = mutableListOf<String>()
        override suspend fun call(offerName: String, arguments: JsonObject): McpToolOutcome {
            called += offerName
            return McpToolOutcome(offerName, "s", "S", offerName, "ok", false)
        }
    }

    @Test
    fun noContractLeavesTheWholeToolbox() {
        val box = Fake(listOf(offer("a"), offer("b")))
        assertEquals(box, box.restrictedTo(null))
    }

    @Test
    fun anEmptyAllowedSetLeavesNoTool() {
        assertNull(Fake(listOf(offer("a"))).restrictedTo(emptyList()))
        assertNull(Fake(listOf(offer("a"))).restrictedTo(listOf("zzz")))
    }

    @Test
    fun onlyAllowedOffersAreOfferedAndOthersAreRefusedWithoutReachingTheServer() = runTest {
        val box = Fake(listOf(offer("read"), offer("delete")))
        val filtered = box.restrictedTo(listOf("read"))!!
        assertEquals(listOf("read"), filtered.offers.map { it.name })
        val refused = filtered.call("delete", JsonObject(emptyMap()))
        assertTrue(refused.isError)
        assertTrue(refused.text.startsWith(TOOL_NOT_ALLOWED))
        assertTrue(box.called.isEmpty())
        assertEquals("ok", filtered.call("read", JsonObject(emptyMap())).text)
        assertEquals(listOf("read"), box.called)
    }
}
