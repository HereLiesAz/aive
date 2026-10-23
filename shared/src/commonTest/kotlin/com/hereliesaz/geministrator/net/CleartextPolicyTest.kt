package com.hereliesaz.geministrator.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleartextPolicyTest {
    @Test
    fun httpsIsAlwaysAllowed() {
        assertTrue(CleartextPolicy.allowsSecret("https://api.example.com/v1"))
    }

    @Test
    fun httpIsAllowedOnlyForLoopbackAndPrivateHosts() {
        listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:8080",
            "http://[::1]:8080/x",
            "http://10.0.2.2:8080",
            "http://172.16.0.5",
            "http://192.168.1.20:1234/v1",
            "http://my-box.local:8000",
        ).forEach { assertTrue(CleartextPolicy.allowsSecret(it), it) }
        listOf(
            "http://api.example.com/v1",
            "http://8.8.8.8",
            "http://172.32.0.1",
            "http://192.169.0.1",
            "http://localhost.evil.com",
            "http://user@evil.com",
            "ftp://localhost",
            "file:///etc/passwd",
        ).forEach { assertFalse(CleartextPolicy.allowsSecret(it), it) }
    }
}
