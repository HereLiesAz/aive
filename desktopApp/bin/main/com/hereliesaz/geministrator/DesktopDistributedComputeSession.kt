package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeExecutorIntegration
import com.hereliesaz.geministrator.distributed.DistributedComputeWorker
import com.hereliesaz.geministrator.distributed.RelayDistributedComputeClient
import com.hereliesaz.geministrator.distributed.RelayDistributedComputeGateway
import com.hereliesaz.geministrator.distributed.SystemExecutorDistributedWorkloadRunner
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
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

internal class DesktopDistributedComputeSession(
    configuration: DistributedComputeConfiguration,
    token: String,
    baseIntegrations: TaskExecutorIntegrationRegistry,
    supportedExecutorKinds: Set<String>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = HttpClient(CIO) {
        install(WebSockets)
    }

    val node: ComputeNodeDescriptor = desktopComputeNode(configuration, supportedExecutorKinds)

    val client = RelayDistributedComputeClient(
        httpClient = httpClient,
        relayBaseUrl = configuration.relayUrl,
        poolId = configuration.poolId,
        bearerToken = token,
        initialNode = node,
    )

    private val gateway = RelayDistributedComputeGateway(client, node.nodeId)

    val executorIntegrations: TaskExecutorIntegrationRegistry =
        baseIntegrations.withIntegration(DistributedComputeExecutorIntegration(gateway))

    private val worker = DistributedComputeWorker(
        client = client,
        node = node,
        runners = listOf(
            SystemExecutorDistributedWorkloadRunner(
                integrations = baseIntegrations,
                nowEpochMillis = System::currentTimeMillis,
                // Pool leases may use this device's repository credentials only for its own projects.
                trustedRepositories = {
                    SettingsWorkflowPersistence.createDefault().projects.all().mapNotNull { it.repository }
                },
            ),
        ),
        scope = scope,
    )

    fun start() {
        client.start(scope)
        if (node.acceptsWork) {
            worker.start()
        }
    }

    fun close() {
        scope.cancel()
        httpClient.close()
    }
}

private fun desktopComputeNode(
    configuration: DistributedComputeConfiguration,
    supportedExecutorKinds: Set<String>,
): ComputeNodeDescriptor {
    val runtime = Runtime.getRuntime()
    val memoryMiB = runtime.maxMemory().coerceAtLeast(0L) / (1024L * 1024L)
    val architecture = System.getProperty("os.arch", "desktop")
    val externalPowerKnown = !configuration.requireExternalPower

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
        },
        supportedExecutorKinds = supportedExecutorKinds,
        maxParallelLeases = configuration.maxParallelLeases,
        acceptsWork = configuration.sharingEnabled && externalPowerKnown,
        meteredNetwork = false,
        onExternalPower = null,
    )
}
