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
        val keys = listOf(
            "sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUV",
            "ghp_ABCDEFGHIJKLMNOPQRSTUVWX12",
            "glpat-ABCDEFGHIJKLMNOPQRST",
            "AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ0123456",
            "xai-ABCDEFGHIJKLMNOPQRSTUVWX",
            "hf_ABCDEFGHIJKLMNOPQRSTUVWX",
        )
        keys.forEach { key -> assertFalse(key in "invalid key $key".redactSecrets(), key) }
    }

    @Test
    fun ordinaryErrorTextIsUnchanged() {
        val body = """{"error":{"message":"max_tokens: 4096 exceeds limit; reasoning is not supported for this model"}}"""
        assertEquals(body, body.redactSecrets())
    }
}
