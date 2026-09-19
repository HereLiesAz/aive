package com.hereliesaz.aive

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeExecutorIntegration
import com.hereliesaz.geministrator.distributed.DistributedComputeWorker
import com.hereliesaz.geministrator.distributed.RelayDistributedComputeClient
import com.hereliesaz.geministrator.distributed.RelayDistributedComputeGateway
import com.hereliesaz.geministrator.distributed.SystemExecutorDistributedWorkloadRunner
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

internal class AndroidDistributedComputeSession(
    context: Context,
    configuration: DistributedComputeConfiguration,
    token: String,
    baseIntegrations: TaskExecutorIntegrationRegistry,
    supportedExecutorKinds: Set<String>,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = HttpClient(CIO) {
        install(WebSockets)
    }

    val node: ComputeNodeDescriptor = androidComputeNode(
        context = appContext,
        configuration = configuration,
        supportedExecutorKinds = supportedExecutorKinds,
    )

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

private fun androidComputeNode(
    context: Context,
    configuration: DistributedComputeConfiguration,
    supportedExecutorKinds: Set<String>,
): ComputeNodeDescriptor {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val metered = connectivity.isActiveNetworkMetered
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val onExternalPower = plugged != 0
    val policyAllowsWork =
        configuration.sharingEnabled &&
            (configuration.allowMeteredNetwork || !metered) &&
            (!configuration.requireExternalPower || onExternalPower)

    return ComputeNodeDescriptor(
        nodeId = configuration.nodeId,
        displayName = configuration.displayName,
        platform = ComputePlatform.Android,
        architecture = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "android" },
        logicalProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        memoryMiB = memoryInfo.totalMem / (1024L * 1024L),
        accelerators = setOf(ComputeAccelerator.Cpu),
        capabilities = buildSet {
            add("workflow-executor")
            add("android")
            if (!metered) add("unmetered-network")
            if (onExternalPower) add("external-power")
        },
        supportedExecutorKinds = supportedExecutorKinds,
        maxParallelLeases = configuration.maxParallelLeases,
        acceptsWork = policyAllowsWork,
        meteredNetwork = metered,
        onExternalPower = onExternalPower,
    )
}
