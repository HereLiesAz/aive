package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.providers.llm.AiModeBrowser
import com.hereliesaz.geministrator.providers.llm.AiModePage
import com.hereliesaz.geministrator.providers.llm.GoogleAiModeUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Where a Chromium-based browser usually lives, by operating system. Pure, so it is tested without one installed. */
internal object ChromiumLocator {
    /** Names looked up on `PATH`. */
    val PATH_NAMES = listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "microsoft-edge-stable", "chrome", "msedge")

    /** Absolute places an installer puts one, for [os] (`windows`, `mac` or anything else). */
    fun wellKnown(os: String, environment: Map<String, String>): List<String> = when (os) {
        "mac" -> listOf(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
        )
        "windows" -> listOfNotNull(
            environment["ProgramFiles"]?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
            environment["ProgramFiles(x86)"]?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
            environment["LOCALAPPDATA"]?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
            environment["ProgramFiles"]?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
            environment["ProgramFiles(x86)"]?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
        )
        else -> listOf("/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser", "/snap/bin/chromium", "/usr/bin/microsoft-edge")
    }

    fun osName(property: String = System.getProperty("os.name").orEmpty()): String = property.lowercase().let {
        when {
            it.contains("win") -> "windows"
            it.contains("mac") || it.contains("darwin") -> "mac"
            else -> "linux"
        }
    }

    /** The installed browser to drive, or null. An override in `AIVE_CHROME` wins; PATH next; then the usual places. */
    fun find(environment: Map<String, String> = System.getenv()): File? {
        environment["AIVE_CHROME"]?.takeIf(String::isNotBlank)?.let(::File)?.takeIf { it.isFile && it.canExecute() }?.let { return it }
        CliLocator.locateBinary(PATH_NAMES, environment)?.let { return it }
        return wellKnown(osName(), environment).map(::File).firstOrNull { it.isFile && it.canExecute() }
    }

    /** The debugging port from the `DevToolsActivePort` file the browser writes (first line), or null while it is not there yet. */
    fun portFrom(fileText: String?): Int? = fileText?.lineSequence()?.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    /** The page's WebSocket debugger address in the browser's `/json/new` answer, or null. */
    fun socketFrom(json: String): String? = runCatching {
        Json.parseToJsonElement(json).jsonObject["webSocketDebuggerUrl"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.startsWith("ws://127.0.0.1:") || it.startsWith("ws://localhost:") }
}

/**
 * The browser view behind Google AI Mode on the desktop: the keeper's own Chromium-based browser (Chrome, Edge, Chromium, Brave),
 * started headless with a throw-away profile on a debugging port that listens on this machine only, driven over the DevTools
 * protocol to read the page text, then closed and its profile deleted. No login, no cookies kept, nothing installed by the app.
 */
internal class DesktopAiModeBrowser(private val browser: File) : AiModeBrowser {
    private val http = HttpClient.newHttpClient()

    override suspend fun <T> withPage(url: String, read: suspend (AiModePage) -> T): T = withContext(Dispatchers.IO) {
        val profile = Files.createTempDirectory("aive-aimode-")
        val process = ProcessBuilder(
            browser.absolutePath,
            "--headless=new",
            "--remote-debugging-port=0",
            "--remote-debugging-address=127.0.0.1",
            "--user-data-dir=${profile.toAbsolutePath()}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-background-networking",
            "--user-agent=$USER_AGENT",
            "about:blank",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        var socket: WebSocket? = null
        try {
            val port = waitForPort(profile.toFile(), process)
            val opened = http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/new?" + URLEncoder.encode(url, Charsets.UTF_8)))
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val target = ChromiumLocator.socketFrom(opened.body()) ?: throw GoogleAiModeUnavailable("The browser did not open the page.")
            val page = CdpPage()
            socket = http.newWebSocketBuilder().buildAsync(URI(target), page.listener).get()
            page.socket = socket
            read(page)
        } finally {
            runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "") }
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            runCatching { profile.toFile().deleteRecursively() }
        }
    }

    private suspend fun waitForPort(profile: File, process: Process): Int {
        repeat(60) {
            ChromiumLocator.portFrom(File(profile, "DevToolsActivePort").takeIf { it.isFile }?.readText())?.let { return it }
            if (!process.isAlive) throw GoogleAiModeUnavailable("The browser closed before it was ready.")
            delay(250)
        }
        throw GoogleAiModeUnavailable("The browser did not start in time.")
    }

    /** One page over the DevTools protocol: each `text()` is one `Runtime.evaluate` of the page's visible text. */
    private class CdpPage : AiModePage {
        var socket: WebSocket? = null
        private val ids = AtomicInteger()
        private val waiting = ConcurrentHashMap<Int, CompletableFuture<String>>()
        private val buffer = StringBuilder()

        val listener = object : WebSocket.Listener {
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val message = buffer.toString()
                    buffer.setLength(0)
                    runCatching {
                        val obj: JsonObject = Json.parseToJsonElement(message).jsonObject
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@runCatching
                        val value = obj["result"]?.jsonObject?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()
                        waiting.remove(id)?.complete(value)
                    }
                }
                webSocket.request(1)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                waiting.values.forEach { it.completeExceptionally(error) }
            }
        }

        override suspend fun text(): String {
            val id = ids.incrementAndGet()
            val reply = CompletableFuture<String>().also { waiting[id] = it }
            val command = buildJsonObject {
                put("id", id)
                put("method", "Runtime.evaluate")
                put("params", buildJsonObject {
                    put("expression", "document.body ? document.body.innerText : ''")
                    put("returnByValue", true)
                })
            }
            val sock = socket ?: throw GoogleAiModeUnavailable("The browser page is not open.")
            sock.sendText(command.toString(), true).await()
            return reply.await()
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
