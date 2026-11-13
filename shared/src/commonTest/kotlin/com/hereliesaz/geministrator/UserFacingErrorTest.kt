package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals

class UserFacingErrorTest {
    @Test
    fun authFailuresMapToKeyRejected() {
        listOf(
            "HTTP 401 Unauthorized",
            "Client request(POST https://api.example/v1) invalid: 403 Forbidden",
            "invalid_api_key: Incorrect API key provided",
            "Authentication failed",
        ).forEach { assertEquals(ErrorKind.KeyRejected, classifyError(it), it) }
    }

    @Test
    fun throttlingMapsToRateLimited() {
        listOf("429 Too Many Requests", "Rate limit reached for requests", "RESOURCE_EXHAUSTED: quota exceeded")
            .forEach { assertEquals(ErrorKind.RateLimited, classifyError(it), it) }
    }

    @Test
    fun aSpentBalanceIsOutOfCreditNotRateLimited() {
        listOf(
            // Seen on Android: a 402 whose body names its "quota" read as a rate limit.
            "Unable to generate compatible chat response: HTTP 402: {\"message\":\"Payment required to access this resource. " +
                "Visit your billing tab.\",\"type\":\"payment_required_error\",\"param\":\"quota\",\"code\":\"payment_required\"}",
            "insufficient_quota: You exceeded your current quota, please check your plan and billing details.",
            "Your credit balance is too low to access the API",
            // Ollama Cloud, from 2026-11-09: some cloud models need credits on the account.
            "this model requires credits; add credits to your account to use it",
        ).forEach { assertEquals(ErrorKind.OutOfCredit, classifyError(it), it) }
    }

    @Test
    fun networkFailuresMapToOfflineOrTimeout() {
        assertEquals(ErrorKind.Offline, classifyError("Unable to resolve host \"api.openai.com\""))
        assertEquals(ErrorKind.Offline, classifyError("java.net.ConnectException: Connection refused"))
        assertEquals(ErrorKind.TimedOut, classifyError("Request timeout has expired"))
        assertEquals(ErrorKind.TimedOut, classifyError("Read timed out"))
    }

    @Test
    fun numbersInsideOtherNumbersAreNotStatusCodes() {
        assertEquals(ErrorKind.Other, classifyError("Task 14290 failed at step 4010"))
    }

    @Test
    fun unknownOrBlankIsOther() {
        assertEquals(ErrorKind.Other, classifyError(null))
        assertEquals(ErrorKind.Other, classifyError("   "))
        assertEquals(ErrorKind.Other, classifyError("Project could not be loaded"))
    }

    @Test
    fun aMissingAgentIsNamedAsSuchNotAsARejectedKey() {
        assertEquals(
            ErrorKind.NoAgent,
            classifyError("No agent provider satisfies required capabilities [RepositoryRead, RepositoryWrite]"),
        )
        assertEquals(ErrorKind.NoAgent, classifyError("OpenCode runs against a linked GitHub repository"))
    }
}
