package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals

class MacKeychainTest {
    @Test
    fun quotingEscapesOnlyBackslashAndDoubleQuote() {
        assertEquals("\"plain-key_123\"", MacKeychain.quote("plain-key_123"))
        assertEquals("\"a\\\"b\"", MacKeychain.quote("a\"b"))
        assertEquals("\"a\\\\b\"", MacKeychain.quote("a\\b"))
        assertEquals("\"with space\"", MacKeychain.quote("with space"))
    }
}
