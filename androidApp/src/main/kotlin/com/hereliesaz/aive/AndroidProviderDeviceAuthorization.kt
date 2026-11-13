package com.hereliesaz.aive

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import com.hereliesaz.geministrator.net.boundedBodyText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Public-client device authorization. No Google credentials are handled here: the provider
 * owns its browser login and any Google identity federation. Credentials never enter workflow
 * persistence and are saved by AndroidProviderCredentialStore only after user authorization.
 *
 * Aive's app-owned public client IDs must be configured at build time. Never substitute
 * another application's client ID or a user's personal API key for one.
 */
internal class AndroidProviderDeviceAuthorization(
    private val client: HttpClient,
    private val clientIds: Map<String, String>,
) {
    internal data class Challenge(val verificationUrl: String, val userCode: String)
    private data class Session(val deviceCode: String, val clientId: String, val tokenUrl: String)
    private val sessions = mutableMapOf<String, Session>()
    private val json = Json { ignoreUnknownKeys = true }

    fun configured(providerId: String): Boolean = clientIds[providerId]?.isNotBlank() == true

    suspend fun begin(providerId: String): Challenge {
        val clientId = clientIds[providerId]?.takeIf(String::isNotBlank)
            ?: error("Aive's public OAuth client is not registered for $providerId")
        val (deviceUrl, tokenUrl) = when (providerId) {
            "huggingface" -> "https://huggingface.co/oauth/device" to "https://huggingface.co/oauth/token"
            "pollinations" -> "https://enter.pollinations.ai/api/device/code" to "https://enter.pollinations.ai/api/device/token"
            else -> error("Unsupported device authorization provider")
        }
        // Only one live challenge per provider. Starting another revokes the local pending flow.
        sessions.remove(providerId)
        val response = client.post(deviceUrl) {
            if (providerId == "pollinations") {
                contentType(ContentType.Application.Json)
                setBody("""{"client_id":${kotlinx.serialization.json.JsonPrimitive(clientId)}}""")
            } else {
                setBody(FormDataContent(Parameters.build {
                    append("client_id", clientId)
                    append("scope", "inference-api")
                }))
            }
        }
        check(response.status.value in 200..299) {
            "Could not start $providerId account authorization (HTTP ${response.status.value})"
        }
        val payload = json.parseToJsonElement(response.boundedBodyText()).jsonObject
        val deviceCode = payload["device_code"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: error("Authorization response omitted device code")
        val userCode = payload["user_code"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: error("Authorization response omitted user code")
        val expectedOrigin = when (providerId) {
            "huggingface" -> "https://huggingface.co/"
            "pollinations" -> "https://enter.pollinations.ai/"
            else -> error("Unsupported device authorization provider")
        }
        val url = (payload["verification_uri_complete"] ?: payload["verification_uri"])
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith(expectedOrigin) }
            ?: error("Provider returned an untrusted verification URL")
        sessions[providerId] = Session(deviceCode, clientId, tokenUrl)
        return Challenge(url, userCode)
    }

    /** A user-triggered status check. Pending authorization returns null, not a fake key. */
    suspend fun check(providerId: String): String? {
        val session = sessions[providerId] ?: error("Start provider sign-in before checking")
        val response = client.post(session.tokenUrl) {
            if (providerId == "pollinations") {
                contentType(ContentType.Application.Json)
                setBody("""{"device_code":${kotlinx.serialization.json.JsonPrimitive(session.deviceCode)}}""")
            } else {
                setBody(FormDataContent(Parameters.build {
                    append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    append("device_code", session.deviceCode)
                    append("client_id", session.clientId)
                }))
            }
        }
        val payload = json.parseToJsonElement(response.boundedBodyText()).jsonObject
        val providerError = payload["error"]?.jsonPrimitive?.contentOrNull
        if (providerError == "authorization_pending" || providerError == "slow_down") return null
        if (providerError != null || response.status.value !in 200..299) {
            sessions.remove(providerId)
            error("Authorization failed or expired; start again (${providerError ?: "HTTP ${response.status.value}"})")
        }
        val key = payload["access_token"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: error("Provider returned no access token")
        if (providerId == "pollinations" && !key.startsWith("sk_")) {
            sessions.remove(providerId)
            error("Pollinations returned an unexpected credential type")
        }
        sessions.remove(providerId)
        return key
    }
}
