package com.hereliesaz.geministrator.distributed

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayUrlSecurityTest {
    @Test
    fun bearerTokenTravelsOnlyOverTlsOrLoopback() {
        assertTrue(isSecureRelayUrl("wss://relay.example"))
        assertTrue(isSecureRelayUrl("https://relay.example/base"))
        assertTrue(isSecureRelayUrl("ws://localhost:8080"))
        assertTrue(isSecureRelayUrl("http://127.0.0.1:8080/x"))
        assertTrue(isSecureRelayUrl("ws://[::1]:8080"))
        assertFalse(isSecureRelayUrl("ws://relay.example"))
        assertFalse(isSecureRelayUrl("http://relay.example"))
        assertFalse(isSecureRelayUrl("ws://localhost@evil.example"))
        assertFalse(isSecureRelayUrl("relay.example"))
    }
}
