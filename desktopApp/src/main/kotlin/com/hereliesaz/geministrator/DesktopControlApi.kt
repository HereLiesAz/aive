package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.control.ControlApi
import com.hereliesaz.geministrator.control.ControlCaller
import com.hereliesaz.geministrator.control.ControlHost
import com.hereliesaz.geministrator.control.ControlHttpResponse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking

/**
 * The control API's desktop server (docs/MCP.md): [ControlApi] over HTTP on loopback only
 * (127.0.0.1), for the standalone MCP bridge. Off unless the keeper turns it on, with the
 * `AIVE_CONTROL_API=on` environment variable or the preference `enabled` in the node
 * `com/hereliesaz/haive/control-api`.
 *
 * The bearer token is made once per install (32 random bytes, base64url) and kept in a file in
 * [dir] that only this user can read (mode 0600 in a 0700 folder; the user's own profile folder on
 * Windows). The token is never logged, never shown, and never written to `control.json`, the
 * discovery file the bridge reads to find the address.
 */
internal class DesktopControlApi(
    val dir: File = defaultDir(),
    /** The configured port; 0 picks a free one (tests). */
    val port: Int = configuredPort(),
    preferencesNode: () -> Preferences = { Preferences.userRoot().node(PREFERENCES_NODE) },
) {
    private val preferences by lazy(preferencesNode)
    private val hostRef = AtomicReference<ControlHost?>(null)
    private val token = AtomicReference<String?>(null)

    @Volatile
    private var http: HttpServer? = null
    private var executor: ExecutorService? = null

    val tokenFile: File get() = File(dir, TOKEN_FILE)
    val discoveryFile: File get() = File(dir, DISCOVERY_FILE)
    val boundPort: Int get() = http?.address?.port ?: port
    val baseUrl: String get() = "http://127.0.0.1:$boundPort"
    val running: Boolean get() = http != null

    private val api = ControlApi(
        host = object : ControlHostDelegate() {
            override fun target(): ControlHost = hostRef.get() ?: throw com.hereliesaz.geministrator.control.ControlException(
                com.hereliesaz.geministrator.control.ControlErrorCode.Unavailable,
                "The app has not finished starting; try again shortly",
            )
        },
        token = { token.get() },
    )

    fun attachHost(value: ControlHost?) = hostRef.set(value)

    val enabled: Boolean
        get() {
            val env = System.getenv("AIVE_CONTROL_API")?.trim()?.lowercase()
            if (env in setOf("1", "true", "on", "yes")) return true
            if (env in setOf("0", "false", "off", "no")) return false
            return runCatching { preferences.getBoolean(ENABLED_KEY, false) }.getOrDefault(false)
        }

    /** Starts listening; false when it is off or the port is taken. Never throws. */
    @Synchronized
    fun startIfEnabled(): Boolean {
        if (!enabled) return false
        return runCatching { start(); true }.getOrElse { false }
    }

    @Synchronized
    fun start() {
        if (http != null) return
        token.set(loadOrCreateToken())
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "aive-control").apply { isDaemon = true } }
        server.executor = pool
        server.createContext("/") { exchange -> serve(exchange) }
        server.start()
        http = server
        executor = pool
        writeDiscovery()
    }

    @Synchronized
    fun stop() {
        http?.stop(0)
        http = null
        executor?.shutdownNow()
        executor = null
        runCatching { discoveryFile.delete() }
    }

    private fun loadOrCreateToken(): String {
        secureDir()
        runCatching { tokenFile.readText().trim() }.getOrNull()?.takeIf { it.length >= 32 }?.let { return it }
        val fresh = DesktopA2aServer.newToken()
        writePrivate(tokenFile, fresh)
        return fresh
    }

    private fun writeDiscovery() {
        // The address and where the token lives; never the token.
        writePrivate(
            discoveryFile,
            """{"url":"$baseUrl","tokenFile":${jsonString(tokenFile.absolutePath)}}""",
        )
    }

    private fun secureDir() {
        dir.mkdirs()
        runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    }

    private fun writePrivate(file: File, text: String) {
        val tmp = File(dir, file.name + ".tmp")
        runCatching { Files.deleteIfExists(tmp.toPath()) }
        val posix = runCatching {
            Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }.isSuccess
        if (!posix) {
            tmp.createNewFile()
            tmp.setReadable(false, false); tmp.setWritable(false, false)
            tmp.setReadable(true, true); tmp.setWritable(true, true)
        }
        tmp.writeText(text)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun serve(exchange: HttpExchange) {
        try {
            val bytes = exchange.requestBody.readNBytes(ControlApi.MAX_BODY_BYTES + 1)
            val response = if (bytes.size > ControlApi.MAX_BODY_BYTES) {
                ControlHttpResponse(413, """{"ok":false,"error":{"code":"too_large","message":"Request body is too large"}}""")
            } else {
                val headers = exchange.requestHeaders.entries.associate { (name, values) -> name to values.firstOrNull().orEmpty() }
                val remote = exchange.remoteAddress?.address
                val caller = ControlCaller(remote?.hostAddress.orEmpty(), remote?.isLoopbackAddress == true)
                runCatching {
                    runBlocking {
                        api.handle(exchange.requestMethod, exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""), headers, bytes.decodeToString(), caller)
                    }
                }.getOrElse {
                    ControlHttpResponse(500, """{"ok":false,"error":{"code":"internal","message":"internal error"}}""")
                }
            }
            val body = response.body.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "${response.contentType}; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
            response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(response.status, if (body.isEmpty()) -1L else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.write(body)
        } finally {
            exchange.close()
        }
    }

    companion object {
        const val DEFAULT_PORT = 41242
        const val TOKEN_FILE = "control-token"
        const val DISCOVERY_FILE = "control.json"
        private const val PREFERENCES_NODE = "com/hereliesaz/haive/control-api"
        private const val ENABLED_KEY = "enabled"

        fun defaultDir(): File = File(System.getProperty("user.home"), ".aive/control")

        /** `AIVE_CONTROL_PORT` when it is a valid unprivileged port, else [DEFAULT_PORT]. */
        fun configuredPort(): Int =
            System.getenv("AIVE_CONTROL_PORT")?.trim()?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT

        private fun jsonString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/** A [ControlHost] that forwards every call to [target], which may not exist yet. */
internal abstract class ControlHostDelegate : ControlHost {
    abstract fun target(): ControlHost
    override suspend fun status() = target().status()
    override suspend fun listHives() = target().listHives()
    override suspend fun getHive(id: String) = target().getHive(id)
    override suspend fun listRoles() = target().listRoles()
    override suspend fun getRole(id: String) = target().getRole(id)
    override suspend fun listRuns(hiveId: String?, limit: Int) = target().listRuns(hiveId, limit)
    override suspend fun getRun(id: String) = target().getRun(id)
    override suspend fun listEvents(runId: String, afterMillis: Long?, limit: Int) = target().listEvents(runId, afterMillis, limit)
    override suspend fun listApprovals() = target().listApprovals()
    override suspend fun listProviders() = target().listProviders()
    override suspend fun settings() = target().settings()
    override suspend fun createHive(spec: kotlinx.serialization.json.JsonObject) = target().createHive(spec)
    override suspend fun updateHive(id: String, patch: kotlinx.serialization.json.JsonObject) = target().updateHive(id, patch)
    override suspend fun deleteHive(id: String) = target().deleteHive(id)
    override suspend fun addCreature(hiveId: String, spec: kotlinx.serialization.json.JsonObject) = target().addCreature(hiveId, spec)
    override suspend fun updateCreature(hiveId: String, creatureId: String, patch: kotlinx.serialization.json.JsonObject) =
        target().updateCreature(hiveId, creatureId, patch)
    override suspend fun removeCreature(hiveId: String, creatureId: String) = target().removeCreature(hiveId, creatureId)
    override suspend fun draftHive(prompt: String) = target().draftHive(prompt)
    override suspend fun setRoleProvider(roleId: String, providerId: String?) = target().setRoleProvider(roleId, providerId)
    override suspend fun startRun(spec: kotlinx.serialization.json.JsonObject) = target().startRun(spec)
    override suspend fun stopRun(runId: String) = target().stopRun(runId)
}
