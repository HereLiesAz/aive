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

    @Test
    fun theHostIsReadFromTheAuthorityAloneNotFromAFragmentQueryOrPath() {
        listOf(
            // The client connects to evil.example; the private-looking part is a fragment, query or path.
            "http://evil.example#@192.168.0.1/v1",
            "http://evil.example?@10.0.0.1",
            "http://evil.example/@127.0.0.1",
            "http://evil.example\\@192.168.0.1/v1",
            "http://evil.com#.local",
            "http://evil.com?x=.local",
            "http://evil.com/.local",
            "http://evil.com:8080#localhost",
            "http://10.0.0.1.evil.com",
            "http://",
            "http://@",
        ).forEach { assertFalse(CleartextPolicy.allowsSecret(it), it) }
        listOf(
            // Userinfo before the host is not the host.
            "http://user:pass@192.168.0.1:8080/v1",
            "http://evil.example@localhost/v1",
            "http://my-box.local#section",
            "http://127.0.0.1?debug=1",
            "http://[::1]#x",
        ).forEach { assertTrue(CleartextPolicy.allowsSecret(it), it) }
    }
}
