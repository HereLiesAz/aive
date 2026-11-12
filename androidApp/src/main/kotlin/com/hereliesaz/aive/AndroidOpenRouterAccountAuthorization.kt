package com.hereliesaz.aive

import java.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenRouter's documented headless PKCE flow. OpenRouter authenticates the user in their own
 * browser (including Google login where offered). The resulting account-scoped credential is
 * stored by AndroidProviderCredentialStore, never in workflow persistence.
 *
 * The verifier is one-shot and held in memory only. A lost activity or expired code requires
 * restarting authorization. No app client secret, embedded login page, or password interception.
 */
internal class AndroidOpenRouterAccountAuthorization(
    private val client: HttpClient,
    private val random: SecureRandom = SecureRandom(),
) {
    private var verifier: String? = null

    fun begin(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        verifier = secret
        return "https://openrouter.ai/auth?code_challenge=$challenge&code_challenge_method=S256&key_label=Aive"
    }

    suspend fun exchange(code: String): String {
        val secret = requireNotNull(verifier) { "Start sign-in again to obtain a fresh authorization code" }
        require(code.isNotBlank()) { "Authorization code is missing" }
        // A code can be redeemed once. Never accidentally replay it after a network failure.
        verifier = null
        val payload = """{"code":${JsonPrimitive(code.trim())},"code_verifier":${JsonPrimitive(secret)},"code_challenge_method":"S256"}"""
        val response = client.post("https://openrouter.ai/api/v1/auth/keys") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        check(response.status.value in 200..299) {
            "OpenRouter authorization failed (HTTP ${response.status.value}); start sign-in again"
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["key"]
            ?.jsonPrimitive?.content
            ?.takeIf { it.startsWith("sk-or-") }
            ?: error("OpenRouter did not issue a valid account credential")
    }
}
