package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.DesktopControlApi
import com.hereliesaz.geministrator.mcpbridge.ControlUnreachable
import com.hereliesaz.geministrator.mcpbridge.HttpControlTransport
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reuse the authenticated JVM control HTTP implementation, but never the desktop application's port or state. */
class HeadlessControlServer private constructor(private val delegate: DesktopControlApi) : AutoCloseable {
    val address: String get() = delegate.baseUrl

    override fun close() = delegate.stop()

    companion object {
        const val DEFAULT_PORT: Int = 41243

        fun start(
            engine: HeadlessEngine,
            directory: File = HeadlessEngine.defaultDirectory(),
            port: Int = System.getenv("AIVE_HEADLESS_PORT")?.toIntOrNull()?.takeIf { it in 1024..65535 }
                ?: DEFAULT_PORT,
        ): HeadlessControlServer {
            val server = DesktopControlApi(dir = File(directory, "control"), port = port)
            server.attachHost(engine.controlHost)
            server.start()
            return HeadlessControlServer(server)
        }
    }
}

/** The client discovers only the headless engine. Never silently falls back to a desktop app. */
object HeadlessConnection {
    fun transport(
        directory: File = HeadlessEngine.defaultDirectory(),
        env: Map<String, String> = System.getenv(),
    ): HttpControlTransport {
        val discoveryFile = File(directory, "control/control.json")
        val discovered = runCatching {
            Json.parseToJsonElement(discoveryFile.readText()) as? JsonObject
        }.getOrNull()
        val url = env["AIVE_HEADLESS_CONTROL_URL"]?.takeIf(String::isNotBlank)
            ?: (discovered?.get("url") as? JsonPrimitive)?.contentOrNull
            ?: throw ControlUnreachable("The Aive headless engine is not running. Start 'aive serve'.")
        val tokenFile = env["AIVE_HEADLESS_TOKEN_FILE"]?.takeIf(String::isNotBlank)
            ?: (discovered?.get("tokenFile") as? JsonPrimitive)?.contentOrNull
            ?: File(directory, "control/control-token").absolutePath
        return HttpControlTransport(url, token = {
            runCatching { File(tokenFile).readText().trim() }.getOrNull()?.takeIf(String::isNotEmpty)
        })
    }
}
