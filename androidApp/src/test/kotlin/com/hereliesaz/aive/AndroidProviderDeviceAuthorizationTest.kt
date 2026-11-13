package com.hereliesaz.aive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProviderDeviceAuthorizationTest {
    @Test
    fun huggingFaceRequestsPublicClientCodeAndUsesItToExchangeOnlyAfterConsent() = runBlocking {
        val urls = mutableListOf<String>()
        var checks = 0
        val client = HttpClient(MockEngine { request ->
            urls += request.url.toString()
            val response = when (request.url.encodedPath) {
                "/oauth/device" -> """{"device_code":"private-device","user_code":"ABCD-1234","verification_uri":"https://huggingface.co/activate"}"""
                "/oauth/token" -> if (checks++ == 0) """{"error":"authorization_pending"}"""
                                  else """{"access_token":"hf_oauth_authorized","token_type":"bearer"}"""
                else -> error("Unexpected path: ${request.url}")
            }
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val auth = AndroidProviderDeviceAuthorization(client, mapOf("huggingface" to "public-client"))
        assertTrue(auth.configured("huggingface"))
        val challenge = auth.begin("huggingface")
        assertEquals("https://huggingface.co/activate", challenge.verificationUrl)
        assertEquals("ABCD-1234", challenge.userCode)
        assertEquals(null, auth.check("huggingface"))
        assertEquals("hf_oauth_authorized", auth.check("huggingface"))
        assertEquals(listOf(
            "https://huggingface.co/oauth/device",
            "https://huggingface.co/oauth/token",
            "https://huggingface.co/oauth/token",
        ), urls)
        try {
            auth.check("huggingface")
            error("A completed authorization must not be replayed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Start provider sign-in"))
        }
    }

    @Test
    fun pollinationsRequiresAppRegistrationAndRejectsNonUserKeys() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            val content = when (request.url.encodedPath) {
                "/api/device/code" -> """{"device_code":"d-1","user_code":"WXYZ-9876","verification_uri_complete":"https://enter.pollinations.ai/device?user_code=WXYZ-9876"}"""
                "/api/device/token" -> """{"access_token":"pk_not-a-user-key"}"""
                else -> error("Unexpected path: ${request.url}")
            }
            respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val auth = AndroidProviderDeviceAuthorization(client, mapOf("pollinations" to "pk_public"))
        assertTrue(auth.configured("pollinations"))
        assertFalse(auth.configured("huggingface"))
        assertEquals("WXYZ-9876", auth.begin("pollinations").userCode)
        try {
            auth.check("pollinations")
            error("Publishable app key must not be accepted as user authorization")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("unexpected credential type"))
        }
    }

    @Test
    fun unregisteredProviderCannotSendAnyAuthorizationRequest() = runBlocking {
        val client = HttpClient(MockEngine { error("Must never send a request") })
        val auth = AndroidProviderDeviceAuthorization(client, emptyMap())
        try {
            auth.begin("huggingface")
            error("Unregistered app was authorized")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("not registered"))
        }
    }
}
