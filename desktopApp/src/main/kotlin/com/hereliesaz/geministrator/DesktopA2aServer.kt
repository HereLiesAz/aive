package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.a2a.A2aCodec
import com.hereliesaz.geministrator.a2a.A2aHttpResponse
import com.hereliesaz.geministrator.a2a.A2aServer
import com.hereliesaz.geministrator.a2a.A2aWorkflowHost
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking

/**
 * The Aive's A2A server on the desktop (docs/A2A.md). Off by default; when on, it listens on
 * loopback only (127.0.0.1) and serves [A2aServer]: a public Agent Card, and a JSON-RPC endpoint
 * that needs the bearer token kept in [DesktopA2aServerTokenStore].
 */
internal class DesktopA2aServer(
    private val tokenStore: DesktopA2aServerTokenStore = DesktopA2aServerTokenStore(),
) {
    private val preferences = Preferences.userRoot().node(PREFERENCES_NODE)
    private val runtime = AtomicReference<ApplicationRuntime?>(null)
    private val token = AtomicReference<String?>(null)
    private var http: HttpServer? = null
    private var executor: ExecutorService? = null

    /** True when the token could not be stored in the OS keychain and lives only in memory. */
    @Volatile
    var tokenIsTemporary: Boolean = false
        private set

    val port: Int = System.getenv("AIVE_A2A_PORT")?.trim()?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT

    val baseUrl: String get() = "http://127.0.0.1:$port"

    val agentCardUrl: String get() = baseUrl + A2aCodec.WELL_KNOWN_CARD_PATH

    val core = A2aServer(
        host = object : A2aWorkflowHost {
            override suspend fun run(runId: String): Pair<WorkflowRun, WorkflowDefinition?>? {
                val active = runtime.get() ?: return null
                val run = active.persistence.runs.get(WorkflowRunId(runId)) ?: return null
                return run to active.persistence.definitions.get(run.workflowDefinitionId)
            }

            // Without a runtime nothing can launch yet; the queue waits for it, so not busy.
            override suspend fun busy(): Boolean = runtime.get()?.hasUnfinishedActiveRun() ?: false

            override suspend fun cancel(runId: String): Boolean =
                runtime.get()?.cancelActiveRun(WorkflowRunId(runId)) ?: false

            override suspend fun roles(): List<RoleDefinition> {
                val active = runtime.get() ?: return emptyList()
                return activeRoles(resolveRoleCollection(active.persistence.roles.all()))
            }
        },
        token = { token.get() },
        baseUrl = { baseUrl },
        agentVersion = System.getProperty("jpackage.app-version")?.takeIf(String::isNotBlank) ?: "dev",
        loopbackOnly = true,
        nowIso = { Instant.now().toString() },
    )

    var enabledPreference: Boolean
        get() = preferences.getBoolean(ENABLED_KEY, false)
        set(value) {
            preferences.putBoolean(ENABLED_KEY, value)
            preferences.flush()
        }

    fun attachRuntime(value: ApplicationRuntime?) = runtime.set(value)

    /** The run the runtime shows right now; read straight after a launch to learn the new run's id. */
    fun shownRunId(): String? =
        (runtime.get()?.state?.value as? ApplicationRuntimeState.Live)?.presentation?.run?.id?.value

    /** Starts listening; throws when the port is taken. */
    @Synchronized
    fun start() {
        if (http != null) return
        token.set(loadOrCreateToken())
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        val pool = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "aive-a2a").apply { isDaemon = true } }
        server.executor = pool
        server.createContext("/") { exchange -> serve(exchange) }
        server.start()
        http = server
        executor = pool
    }

    @Synchronized
    fun stop() {
        http?.stop(0)
        http = null
        executor?.shutdownNow()
        executor = null
        runBlocking { core.failPending("The A2A server was turned off before the launch started") }
    }

    /** A new token: clients holding the old one are refused from now on. */
    @Synchronized
    fun resetToken() {
        val fresh = newToken()
        tokenIsTemporary = !tokenStore.write(fresh)
        token.set(fresh)
    }

    fun currentToken(): String? = token.get()

    private fun loadOrCreateToken(): String {
        tokenStore.read()?.let {
            tokenIsTemporary = false
            return it
        }
        val fresh = newToken()
        tokenIsTemporary = !tokenStore.write(fresh)
        return fresh
    }

    private fun serve(exchange: HttpExchange) {
        try {
            val bytes = exchange.requestBody.readNBytes(A2aServer.MAX_BODY_BYTES + 1)
            val response = if (bytes.size > A2aServer.MAX_BODY_BYTES) {
                A2aHttpResponse(413, """{"error":"request too large"}""")
            } else {
                val headers = exchange.requestHeaders.entries.associate { (name, values) -> name to values.firstOrNull().orEmpty() }
                runCatching {
                    runBlocking {
                        core.handle(exchange.requestMethod, exchange.requestURI.rawPath, headers, bytes.decodeToString())
                    }
                }.getOrElse {
                    A2aHttpResponse(500, """{"error":"internal error"}""")
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
        const val DEFAULT_PORT = 41241
        private const val PREFERENCES_NODE = "com/hereliesaz/haive/a2a-server"
        private const val ENABLED_KEY = "enabled"
        private val random = SecureRandom()

        /** 32 random bytes, base64url without padding. */
        fun newToken(): String {
            val bytes = ByteArray(32).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

/**
 * The A2A server's bearer token in the OS keychain (macOS Keychain, Linux Secret Service, Windows
 * DPAPI). Never in plain preferences: without a keychain the token is kept in memory for the
 * session only, and the Keeper's room says so.
 */
internal class DesktopA2aServerTokenStore {
    fun read(): String? = runCatching {
        val os = System.getProperty("os.name", "").lowercase()
        val result = when {
            os.contains("mac") -> CredentialTool.run(listOf("security", "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w"))
            os.contains("win") -> {
                val file = windowsTokenFile()
                if (!file.isFile) return@runCatching null
                CredentialTool.run(
                    DesktopDistributedComputeCredentialStore.windowsPowerShell(
                        DesktopDistributedComputeCredentialStore.WINDOWS_READ_SCRIPT,
                        file,
                    ),
                )
            }
            os.contains("nux") || os.contains("nix") || os.contains("bsd") ->
                CredentialTool.run(listOf("secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT))
            else -> null
        }
        result?.takeIf { it.ok }?.output?.trim()?.takeIf(String::isNotEmpty)
    }.getOrNull()

    /** True when the OS store holds [token] afterwards. */
    fun write(token: String): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val stored = runCatching {
            when {
                os.contains("mac") -> {
                    CredentialTool.run(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", ACCOUNT))
                    MacKeychain.addGenericPassword(SERVICE, ACCOUNT, token)
                }
                os.contains("win") -> CredentialTool.run(
                    DesktopDistributedComputeCredentialStore.windowsPowerShell(
                        DesktopDistributedComputeCredentialStore.WINDOWS_WRITE_SCRIPT,
                        windowsTokenFile(),
                    ),
                    stdin = token,
                )?.ok == true
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> CredentialTool.run(
                    listOf("secret-tool", "store", "--label", "Aive A2A server", "service", SERVICE, "account", ACCOUNT),
                    stdin = token,
                )?.ok == true
                else -> false
            }
        }.getOrDefault(false)
        return stored && read() == token
    }

    private fun windowsTokenFile(): File =
        File(DesktopDistributedComputeCredentialStore.windowsTokenFile().parentFile, "a2a-server-token.dpapi")

    private companion object {
        const val SERVICE = "com.hereliesaz.haive.a2a-server"
        const val ACCOUNT = "a2a-token"
    }
}
