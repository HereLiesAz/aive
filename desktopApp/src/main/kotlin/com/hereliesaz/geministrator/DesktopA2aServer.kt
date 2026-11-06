package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.a2a.A2aCaller
import com.hereliesaz.geministrator.a2a.A2aCodec
import com.hereliesaz.geministrator.a2a.A2aHttpResponse
import com.hereliesaz.geministrator.a2a.A2aProviderInfo
import com.hereliesaz.geministrator.a2a.a2aProviderInfos
import com.hereliesaz.geministrator.a2a.A2aServer
import com.hereliesaz.geministrator.a2a.A2aWorkflowHost
import com.hereliesaz.geministrator.a2a.normalizeA2aClientAddress
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking

/**
 * The Aive's A2A server on the desktop (docs/A2A.md). On by default (the keeper can turn it off);
 * it listens on loopback (127.0.0.1) and serves [A2aServer]: a public Agent Card, and a JSON-RPC
 * endpoint that needs the bearer token kept in [DesktopA2aServerTokenStore]. The token is never
 * written to a log or shown on screen.
 *
 * **Open to this network** (off by default, even while the server is on) binds every IPv4
 * interface instead, so other computers can reach it. A caller from another computer then needs
 * the token *and* its address on the keeper's allowlist; one that has the token but is not listed
 * is refused and shown in the Keeper's room to allow. Plain HTTP: nothing is encrypted.
 */
internal class DesktopA2aServer(
    private val tokenStore: DesktopA2aServerTokenStore = DesktopA2aServerTokenStore(),
    /** The configured port; 0 picks a free one (tests). */
    val port: Int = configuredPort(),
    preferencesNode: () -> Preferences = { Preferences.userRoot().node(PREFERENCES_NODE) },
    /** This computer's IPv4 network addresses (not loopback, not link-local); replaced in tests. */
    private val networkAddresses: () -> List<String> = ::localNetworkAddresses,
) {
    private val preferences by lazy(preferencesNode)
    private val runtime = AtomicReference<ApplicationRuntime?>(null)
    private val token = AtomicReference<String?>(null)
    @Volatile
    private var http: HttpServer? = null
    private var executor: ExecutorService? = null

    /** Whether the running server is bound to the network; false while stopped or loopback only. */
    @Volatile
    var openToNetwork: Boolean = false
        private set
    private var lastApplied = Long.MIN_VALUE
    private val addressCache = AtomicReference<Pair<Long, Set<String>>?>(null)

    /** The keeper's allowlist of network clients (normalized addresses); preferences are its backing store. */
    @Volatile
    private var allowed: Set<String>? = null

    /** True when the token could not be stored in the OS keychain and lives only in memory. */
    @Volatile
    var tokenIsTemporary: Boolean = false
        private set

    /** What the Keeper's room shows; set by [apply], [startForStatus] and [stop]. */
    @Volatile
    var status: A2aServerStatus = A2aServerStatus.Off
        private set

    /** The socket address it listens on; null while stopped. */
    val listeningAddress: InetSocketAddress? get() = http?.address

    /** The port it listens on while running (differs from [port] only when that is 0). */
    val boundPort: Int get() = http?.address?.port ?: port

    val baseUrl: String get() = "http://127.0.0.1:$boundPort"

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

            // The registry holds only the providers linked here. Id, name and capabilities, nothing else.
            override suspend fun providers(): List<A2aProviderInfo> =
                runtime.get()?.providerRegistry?.a2aProviderInfos().orEmpty()
        },
        token = { token.get() },
        baseUrl = { baseUrl },
        agentVersion = System.getProperty("jpackage.app-version")?.takeIf(String::isNotBlank) ?: "dev",
        loopbackOnly = true,
        nowIso = { Instant.now().toString() },
        lanOpen = { openToNetwork },
        lanHosts = { if (openToNetwork) lanHosts() else emptySet() },
        lanBaseUrl = { host -> "http://$host:$boundPort".takeIf { openToNetwork && host in lanHosts() } },
        isAllowedClient = { address -> address in allowedClients() },
        describeProviders = { describesProviders },
    )

    /**
     * The keeper's switch. Never pressed: on ([a2aServerEnabled]). The key is written only when the
     * keeper presses it, so an explicit off (also from builds where off was the default) is kept.
     */
    var enabledPreference: Boolean
        get() = a2aServerEnabled(parseA2aServerChoice(runCatching { preferences.get(ENABLED_KEY, null) }.getOrNull()))
        set(value) {
            runCatching {
                preferences.putBoolean(ENABLED_KEY, value)
                preferences.flush()
            }
        }

    /** **Open to this network**: off unless the keeper turned it on ([a2aServerOpenToNetwork]). */
    var lanPreference: Boolean
        get() = a2aServerOpenToNetwork(parseA2aServerChoice(runCatching { preferences.get(LAN_KEY, null) }.getOrNull()))
        set(value) {
            runCatching {
                preferences.putBoolean(LAN_KEY, value)
                preferences.flush()
            }
        }

    /** The keeper's explicit **Describe my providers in the card** choice; null until the switch is pressed. */
    var describeProvidersChoice: Boolean?
        get() = parseA2aServerChoice(runCatching { preferences.get(DESCRIBE_PROVIDERS_KEY, null) }.getOrNull())
        set(value) {
            runCatching {
                if (value == null) preferences.remove(DESCRIBE_PROVIDERS_KEY) else preferences.putBoolean(DESCRIBE_PROVIDERS_KEY, value)
                preferences.flush()
            }
        }

    /**
     * Whether the card describes the linked providers now ([a2aDescribeProviders]): the explicit
     * choice, else on while the server is loopback only and off while it is open to the network.
     */
    val describesProviders: Boolean get() = a2aDescribeProviders(describeProvidersChoice, openToNetwork)

    /** Network clients the keeper allowed, as normalized addresses. */
    fun allowedClients(): Set<String> = allowed ?: runCatching {
        preferences.get(ALLOWED_KEY, "").split(',').mapNotNull(::normalizeA2aClientAddress).toSet()
    }.getOrDefault(emptySet()).also { allowed = it }

    /** Allows [address] to send work while the server is open to the network; false when it is not an address. */
    @Synchronized
    fun allowClient(address: String): Boolean {
        val normalized = normalizeA2aClientAddress(address) ?: return false
        saveAllowed(allowedClients() + normalized)
        core.forgetWaitingClient(normalized)
        return true
    }

    @Synchronized
    fun forgetClient(address: String) {
        val normalized = normalizeA2aClientAddress(address) ?: return
        saveAllowed(allowedClients() - normalized)
    }

    private fun saveAllowed(clients: Set<String>) {
        allowed = clients
        runCatching {
            preferences.put(ALLOWED_KEY, clients.sorted().joinToString(","))
            preferences.flush()
        }
    }

    /** Where other computers read the Agent Card while open to the network; empty otherwise. */
    fun lanAgentCardUrls(): List<String> =
        if (!openToNetwork) emptyList() else lanHosts().sorted().map { "http://$it:$boundPort" + A2aCodec.WELL_KNOWN_CARD_PATH }

    /** This computer's network addresses, re-read at most every 30 seconds (they change with Wi-Fi). */
    private fun lanHosts(): Set<String> {
        val now = System.nanoTime()
        addressCache.get()?.let { (at, hosts) -> if (now - at < ADDRESS_CACHE_NANOS) return hosts }
        val hosts = runCatching { networkAddresses() }.getOrDefault(emptyList()).mapNotNull(::normalizeA2aClientAddress).toSet()
        addressCache.set(now to hosts)
        return hosts
    }

    fun attachRuntime(value: ApplicationRuntime?) = runtime.set(value)

    /** The run the runtime shows right now; read straight after a launch to learn the new run's id. */
    fun shownRunId(): String? =
        (runtime.get()?.state?.value as? ApplicationRuntimeState.Live)?.presentation?.run?.id?.value

    /**
     * Brings the server to the keeper's switches: off, on loopback, or open to the network
     * ([lan]). Restarts it when only [lan] changed. Calls are applied in [order] (take
     * `System.nanoTime()` when the switch changed): a call older than the last one applied is
     * ignored, so a slow start cannot undo a later switch. Never throws.
     */
    @Synchronized
    fun apply(enabled: Boolean, lan: Boolean, order: Long = System.nanoTime()): A2aServerStatus {
        if (order < lastApplied) return status
        lastApplied = order
        if (!enabled) {
            stop()
            return status
        }
        if (http != null && openToNetwork == lan) return status
        stop()
        return startForStatus(lan)
    }

    /**
     * Starts listening and reports how that went, for the Keeper's room. Never throws: a port in
     * use or any other failure leaves the server off and the rest of The Aive running.
     */
    @Synchronized
    fun startForStatus(lan: Boolean = false): A2aServerStatus {
        status = try {
            start(lan)
            A2aServerStatus.Listening(baseUrl)
        } catch (_: BindException) {
            A2aServerStatus.PortInUse(port)
        } catch (failure: Exception) {
            A2aServerStatus.Failed(failure.message ?: failure::class.simpleName.orEmpty())
        }
        return status
    }

    /** Starts listening (every IPv4 interface when [lan]); throws [BindException] when the port is taken. */
    @Synchronized
    fun start(lan: Boolean = false) {
        if (http != null) return
        token.set(loadOrCreateToken())
        val bindAddress = if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress()
        val server = HttpServer.create(InetSocketAddress(bindAddress, port), 0)
        val pool = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "aive-a2a").apply { isDaemon = true } }
        server.executor = pool
        server.createContext("/") { exchange -> serve(exchange) }
        addressCache.set(null)
        openToNetwork = lan
        server.start()
        http = server
        executor = pool
    }

    @Synchronized
    fun stop() {
        val wasRunning = http != null
        http?.stop(0)
        http = null
        openToNetwork = false
        executor?.shutdownNow()
        executor = null
        status = A2aServerStatus.Off
        if (wasRunning) runBlocking { core.failPending("The A2A server was turned off before the launch started") }
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
                val remote = exchange.remoteAddress?.address
                val caller = A2aCaller(
                    address = remote?.hostAddress.orEmpty(),
                    isLoopback = remote?.isLoopbackAddress == true,
                )
                runCatching {
                    runBlocking {
                        core.handle(exchange.requestMethod, exchange.requestURI.rawPath, headers, bytes.decodeToString(), caller)
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
        private const val LAN_KEY = "lan"
        private const val ALLOWED_KEY = "lan-allowed"
        private const val DESCRIBE_PROVIDERS_KEY = "describe-providers"
        private const val ADDRESS_CACHE_NANOS = 30_000_000_000L
        private val random = SecureRandom()

        /** `AIVE_A2A_PORT` when it is a valid unprivileged port, else [DEFAULT_PORT]. */
        fun configuredPort(): Int =
            System.getenv("AIVE_A2A_PORT")?.trim()?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT

        /** 32 random bytes, base64url without padding. */
        fun newToken(): String {
            val bytes = ByteArray(32).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** IPv4 addresses of this computer's interfaces that are up, not loopback and not link-local. */
        fun localNetworkAddresses(): List<String> =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress }
                .map { it.hostAddress }
                .distinct()
    }
}

/**
 * The A2A server's bearer token in the OS keychain (macOS Keychain, Linux Secret Service, Windows
 * DPAPI). Never in plain preferences: without a keychain the token is kept in memory for the
 * session only, and the Keeper's room says so.
 */
internal open class DesktopA2aServerTokenStore {
    open fun read(): String? = runCatching {
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
    open fun write(token: String): Boolean {
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
