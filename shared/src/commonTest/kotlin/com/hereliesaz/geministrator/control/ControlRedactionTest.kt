package com.hereliesaz.geministrator.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlRedactionTest {
    private val filler = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    @Test
    fun anUnterminatedPemBlockRedactsOnlyItsOwnBodyNotTheRestOfTheText() {
        val begin = "-----BEGIN " + "PRIVATE KEY-----"
        val text = """{"a":"$begin\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC","b":"keep-me-visible"}"""
        val out = ControlRedaction.scrub(text)
        assertFalse("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC" in out, out)
        assertTrue("keep-me-visible" in out, "text after the key must survive: $out")
    }

    @Test
    fun aTerminatedPemBlockIsRemovedWhole() {
        val begin = "-----BEGIN RSA " + "PRIVATE KEY-----"
        val end = "-----END RSA " + "PRIVATE KEY-----"
        val out = ControlRedaction.scrub("before $begin\nQUJDREVGRw==\n$end after")
        assertEquals("before ${ControlRedaction.MASK} after", out)
    }

    @Test
    fun oneRedactorCoversTheShapesTheProviderErrorRedactorKnew() {
        val keys = listOf("x" + "ai-" + filler.take(24), "h" + "f_" + filler.take(24))
        keys.forEach { assertFalse(it in ControlRedaction.scrub("bad key $it"), it) }
        assertEquals("Authorization: Bearer ${ControlRedaction.MASK}", ControlRedaction.scrub("Authorization: Bearer abc123def456ghi789"))
        val fields = ControlRedaction.scrub("""{"password":"hunter22","x-api-key": "zzzz9999"}""")
        assertFalse("hunter22" in fields, fields)
        assertFalse("zzzz9999" in fields, fields)
        assertFalse("dXNlcjpwYXNz" in ControlRedaction.scrub("Authorization: Basic dXNlcjpwYXNz"))
    }

    @Test
    fun ordinaryTextIsUnchanged() {
        val body = """{"error":{"message":"max_tokens: 4096 exceeds limit; reasoning is not supported for this model"}}"""
        assertEquals(body, ControlRedaction.scrub(body))
    }
}
