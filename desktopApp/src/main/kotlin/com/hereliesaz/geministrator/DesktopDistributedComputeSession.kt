package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeExecutorIntegration
import com.hereliesaz.geministrator.distributed.DistributedComputeWorker
import com.hereliesaz.geministrator.distributed.RelayDistributedComputeClient
import com.hereliesaz.geministrator.distributed.RelayDistributedComputeGateway
import com.hereliesaz.geministrator.distributed.SettingsComputeNodeEnrolmentLedger
import com.hereliesaz.geministrator.distributed.SystemExecutorDistributedWorkloadRunner
import com.hereliesaz.geministrator.distributed.computeWorkDecision
import com.hereliesaz.geministrator.domain.ComputeAccelerator
import com.hereliesaz.geministrator.domain.ComputePlatform
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class DesktopDistributedComputeSession(
    private val configuration: DistributedComputeConfiguration,
    token: String,
    baseIntegrations: TaskExecutorIntegrationRegistry,
    private val supportedExecutorKinds: Set<String>,
    /** This device's identity; the pool token only enrols it. */
    signer: DesktopComputeNodeSigner,
    /** The pool owner's token, when the keeper set one: lets this device revoke others and re-enrol itself. */
    ownerToken: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = HttpClient(CIO) {
        install(WebSockets)
    }

    @Volatile
    private var currentNode: ComputeNodeDescriptor = desktopComputeNode(configuration, supportedExecutorKinds)

    val node: ComputeNodeDescriptor get() = currentNode

    val client = RelayDistributedComputeClient(
        httpClient = httpClient,
        relayBaseUrl = configuration.relayUrl,
        poolId = configuration.poolId,
        bearerToken = token,
        initialNode = currentNode,
        signer = signer,
        enrolmentLedger = SettingsComputeNodeEnrolmentLedger(),
        ownerToken = ownerToken,
    )

    private val gateway = RelayDistributedComputeGateway(client, currentNode.nodeId)

    val executorIntegrations: TaskExecutorIntegrationRegistry =
        baseIntegrations.withIntegration(DistributedComputeExecutorIntegration(gateway))

    private val worker = DistributedComputeWorker(
        client = client,
        nodeProvider = { currentNode },
        runners = listOf(
            SystemExecutorDistributedWorkloadRunner(
                integrations = baseIntegrations,
                nowEpochMillis = System::currentTimeMillis,
                // Pool leases may use this device's repository credentials only for its own projects.
                trustedRepositories = {
                    com.hereliesaz.geministrator.persistence.WorkflowPersistenceFactory.durable.projects.all().mapNotNull { it.repository }
                },
            ),
        ),
        scope = scope,
    )

    fun start() {
        client.start(scope)
        // The worker stays subscribed even while this computer holds work back; each offer is checked
        // against [currentNode], so plugging in later needs no new session.
        worker.start()
        scope.launch {
            while (isActive) {
                delay(POWER_RECHECK_MILLIS)
                refreshNode()
            }
        }
    }

    private suspend fun refreshNode() {
        val updated = desktopComputeNode(configuration, supportedExecutorKinds)
        if (updated == currentNode) return
        currentNode = updated
        runCatching { client.updateNode(updated) }
    }

    fun close() {
        scope.cancel()
        httpClient.close()
    }
}

/** How often a computer that needs external power looks at its power source again. */
private const val POWER_RECHECK_MILLIS = 60_000L

internal fun desktopComputeNode(
    configuration: DistributedComputeConfiguration,
    supportedExecutorKinds: Set<String>,
    /** Null when the computer cannot tell (or the keeper does not require external power, so nobody asked). */
    onExternalPower: Boolean? = if (configuration.requireExternalPower) DesktopPowerSource.probe() else null,
): ComputeNodeDescriptor {
    val runtime = Runtime.getRuntime()
    val memoryMiB = runtime.maxMemory().coerceAtLeast(0L) / (1024L * 1024L)
    val architecture = System.getProperty("os.arch", "desktop")
    // A desktop cannot see whether its network is metered, so that check never holds it back.
    val decision = computeWorkDecision(configuration, meteredNetwork = null, onExternalPower = onExternalPower)

    return ComputeNodeDescriptor(
        nodeId = configuration.nodeId,
        displayName = configuration.displayName,
        platform = ComputePlatform.Desktop,
        architecture = architecture,
        logicalProcessors = runtime.availableProcessors().coerceAtLeast(1),
        memoryMiB = memoryMiB,
        accelerators = setOf(ComputeAccelerator.Cpu),
        capabilities = buildSet {
            add("workflow-executor")
            add("desktop")
            add("local-workspace")
            if (onExternalPower == true) add("external-power")
            decision.hold?.let { add(it.capability) }
        },
        supportedExecutorKinds = supportedExecutorKinds,
        maxParallelLeases = configuration.maxParallelLeases,
        acceptsWork = decision.accepts,
        meteredNetwork = false,
        onExternalPower = onExternalPower,
    )
}
