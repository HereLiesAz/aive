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

    @Test
    fun aStatusOrCodeDecidesBeforeAWordInTheBody() {
        // A word in the body must not outrank the status the provider sent.
        assertEquals(ErrorKind.RateLimited, classifyError("Unable to generate: HTTP 429: slow down, your billing is fine"))
        assertEquals(ErrorKind.ServerError, classifyError("Unable to generate: HTTP 500: the quota service failed"))
        assertEquals(ErrorKind.BadRequest, classifyError("Unable to generate: HTTP 400: invalid_request, field billing_address is missing"))
        assertEquals(ErrorKind.KeyRejected, classifyError("Unable to generate: HTTP 401: see your billing page for a new key"))
        // A code that says the balance is spent still wins over the 429 it rides on.
        assertEquals(ErrorKind.OutOfCredit, classifyError("HTTP 429: {\"code\":\"insufficient_quota\"}"))
    }

    @Test
    fun wordsAloneStillClassifyWhenThereIsNoStatus() {
        assertEquals(ErrorKind.OutOfCredit, classifyError("Fix the billing page"))
        assertEquals(ErrorKind.RateLimited, classifyError("You exceeded your quota"))
        assertEquals(ErrorKind.ServerError, classifyError("The model is overloaded"))
    }
}
