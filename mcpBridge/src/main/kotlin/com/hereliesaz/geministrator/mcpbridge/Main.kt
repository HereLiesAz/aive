package com.hereliesaz.geministrator.mcpbridge

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sends requests to the app's control API over loopback HTTP. The token is read from its file on
 * every call (so "a new token" needs no restart) and goes only in the Authorization header: never
 * into a log, a tool result or an error message. The address must be loopback, so the token can
 * never be sent anywhere else.
 */
class HttpControlTransport(
    private val baseUrl: String,
    private val token: () -> String?,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
) : ControlTransport {
    init {
        val host = URI(baseUrl).host
        require(host == "127.0.0.1" || host == "localhost" || host == "[::1]" || host == "::1") {
            "The control API address must be on this computer (127.0.0.1)"
        }
    }

    override fun send(method: String, path: String, body: String?): ControlReply {
        val secret = token() ?: throw ControlUnreachable(
            "No control token found. Start The Aive with AIVE_CONTROL_API=on, or set AIVE_CONTROL_TOKEN_FILE.",
        )
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/control/v1" + path))
            .timeout(Duration.ofMinutes(5)) // a draft asks a provider
            .header("Authorization", "Bearer $secret")
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            ControlReply(response.statusCode(), response.body())
        } catch (e: java.io.IOException) {
            throw ControlUnreachable("The Aive is not reachable at $baseUrl. Is it running with its control API on?")
        }
    }
}

/** Where the bridge finds the app: environment first, then the discovery file the app writes. */
object BridgeConfig {
    fun load(env: Map<String, String> = System.getenv(), home: File = File(System.getProperty("user.home"))): HttpControlTransport {
        val dir = File(home, ".aive/control")
        val discovery = runCatching { Json.parseToJsonElement(File(dir, "control.json").readText()) as JsonObject }.getOrNull()
        val url = env["AIVE_CONTROL_URL"]?.takeIf(String::isNotBlank)
            ?: (discovery?.get("url") as? JsonPrimitive)?.content
            ?: "http://127.0.0.1:41242"
        val tokenFile = env["AIVE_CONTROL_TOKEN_FILE"]?.takeIf(String::isNotBlank)
            ?: (discovery?.get("tokenFile") as? JsonPrimitive)?.content
            ?: File(dir, "control-token").path
        val inline = env["AIVE_CONTROL_TOKEN"]?.takeIf(String::isNotBlank)
        return HttpControlTransport(url, token = { inline ?: runCatching { File(tokenFile).readText().trim() }.getOrNull()?.takeIf(String::isNotEmpty) })
    }
}

fun main() {
    val server = McpServer(BridgeConfig.load(), serverVersion = "1")
    val out = System.out
    // Standard output carries only protocol messages; anything else would corrupt the stream.
    System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        if (line.isNotBlank()) server.handle(line)?.let { reply ->
            synchronized(out) { out.write((reply + "\n").toByteArray(Charsets.UTF_8)); out.flush() }
        }
    }
}
