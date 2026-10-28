package com.hereliesaz.geministrator.providers.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorBodyRedactionTest {
    @Test
    fun echoedAuthorizationHeaderIsRedacted() {
        val redacted = "Authorization: Bearer abc123def456ghi789".redactSecrets()
        assertFalse("abc123def456ghi789" in redacted)
        assertTrue("Bearer [redacted]" in redacted)
    }

    @Test
    fun credentialFieldsAreRedacted() {
        val body = """{"api_key":"k-1234567890","password":"hunter22","x-api-key": "zzzz9999"} token=qwertyuiop"""
        val redacted = body.redactSecrets()
        listOf("k-1234567890", "hunter22", "zzzz9999", "qwertyuiop").forEach { assertFalse(it in redacted, it) }
    }

    @Test
    fun knownKeyPrefixesAreRedacted() {
        // Fake keys, assembled from their prefix and filler so no literal in the source looks like
        // a credential to a secret scanner.
        val filler = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val keys = listOf(
            "sk-" + "ant-api03-" + filler.take(22),
            "gh" + "p_" + filler.take(26),
            "gl" + "pat-" + filler.take(20),
            "AI" + "za" + "Sy" + filler.take(33),
            "x" + "ai-" + filler.take(24),
            "h" + "f_" + filler.take(24),
        )
        keys.forEach { key -> assertFalse(key in "invalid key $key".redactSecrets(), key) }
    }

    @Test
    fun ordinaryErrorTextIsUnchanged() {
        val body = """{"error":{"message":"max_tokens: 4096 exceeds limit; reasoning is not supported for this model"}}"""
        assertEquals(body, body.redactSecrets())
    }
}
