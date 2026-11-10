package com.hereliesaz.geministrator.net

import kotlin.test.Test
import kotlin.test.assertEquals

class ChildProcessEnvironmentTest {
    @Test
    fun onlyTheBasicsPassAndNamesMatchWithoutRegardToCase() {
        val inherited = mapOf(
            "PATH" to "/usr/bin", "Path" to "C:\\x", "HOME" to "/home/k", "HTTPS_PROXY" to "http://p:1",
            "OPENAI_API_KEY" to "a", "GITHUB_TOKEN" to "b", "GIT_DIR" to "/c", "AWS_SECRET_ACCESS_KEY" to "d",
        )
        assertEquals(setOf("PATH", "Path", "HOME", "HTTPS_PROXY"), ChildProcessEnvironment.allowed(inherited).keys)
        assertEquals(setOf("PATH", "Path", "HOME", "HTTPS_PROXY", "GITHUB_TOKEN"), ChildProcessEnvironment.allowed(inherited, listOf("github_token")).keys)
    }
}
