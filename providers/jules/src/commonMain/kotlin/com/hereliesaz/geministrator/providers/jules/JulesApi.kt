package com.hereliesaz.geministrator.providers.jules

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject

fun interface JulesApiKeyProvider {
    suspend fun getApiKey(): String
}

interface JulesApi {
    suspend fun listSources(): List<JulesSource>
    suspend fun getSession(sessionName: String): JulesSession
    suspend fun createSession(request: JulesCreateSessionRequest): JulesSession
    suspend fun listActivities(sessionName: String): List<JulesActivity>
    suspend fun sendMessage(sessionName: String, message: String)
    suspend fun approvePlan(sessionName: String)
    suspend fun deleteSession(sessionName: String)
}

class JulesRestApi(
    private val apiKeyProvider: JulesApiKeyProvider,
    private val baseUrl: String = "https://jules.googleapis.com/v1alpha",
    client: HttpClient = HttpClient {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000L
            connectTimeoutMillis = 10_000L
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
    },
) : JulesApi {
    private val client = client.config {
        followRedirects = false
    }

    override suspend fun listSources(): List<JulesSource> {
        val result = mutableListOf<JulesSource>()
        var pageToken: String? = null
        do {
            val response = client.get("$baseUrl/sources") {
                authenticate()
                url {
                    parameters.append("pageSize", "100")
                    pageToken?.let { parameters.append("pageToken", it) }
                }
            }
            response.requireSuccess("list Jules sources")
            val page = response.body<JulesListSourcesResponse>()
            result += page.sources
            pageToken = page.nextPageToken
        } while (!pageToken.isNullOrBlank())
        return result
    }

    override suspend fun getSession(sessionName: String): JulesSession {
        val response = client.get("$baseUrl/$sessionName") { authenticate() }
        response.requireSuccess("read Jules session $sessionName")
        return response.body()
    }

    override suspend fun createSession(request: JulesCreateSessionRequest): JulesSession {
        val response = client.post("$baseUrl/sessions") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        response.requireSuccess("create Jules session")
        return response.body()
    }

    override suspend fun listActivities(sessionName: String): List<JulesActivity> {
        val result = mutableListOf<JulesActivity>()
        var pageToken: String? = null
        do {
            val response = client.get("$baseUrl/$sessionName/activities") {
                authenticate()
                url {
                    parameters.append("pageSize", "100")
                    pageToken?.let { parameters.append("pageToken", it) }
                }
            }
            response.requireSuccess("list Jules activities for $sessionName")
            val page = response.body<JulesListActivitiesResponse>()
            result += page.activities
            pageToken = page.nextPageToken
        } while (!pageToken.isNullOrBlank())
        return result
    }

    override suspend fun sendMessage(sessionName: String, message: String) {
        val response = client.post("$baseUrl/$sessionName:sendMessage") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(JulesSendMessageRequest(prompt = message))
        }
        response.requireSuccess("send Jules message to $sessionName")
    }

    override suspend fun approvePlan(sessionName: String) {
        val response = client.post("$baseUrl/$sessionName:approvePlan") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {})
        }
        response.requireSuccess("approve Jules plan for $sessionName")
    }

    override suspend fun deleteSession(sessionName: String) {
        val response = client.delete("$baseUrl/$sessionName") { authenticate() }
        response.requireSuccess("delete Jules session $sessionName")
    }

    private suspend fun io.ktor.client.request.HttpRequestBuilder.authenticate() {
        val apiKey = apiKeyProvider.getApiKey().trim()
        require(apiKey.isNotEmpty()) { "Jules API key is not configured" }
        header("x-goog-api-key", apiKey)
    }

    private suspend fun HttpResponse.requireSuccess(operation: String) {
        if (status.value in 200..299) return
        val responseBody = bodyAsText().take(500)
        error(
            "Unable to $operation: HTTP ${status.value}" +
                responseBody.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
        )
    }
}
