package com.hereliesaz.aive

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.ComputeNodeSigner
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeExecutorIntegration
import com.hereliesaz.geministrator.distributed.DistributedComputeWorker
import com.hereliesaz.geministrator.distributed.RelayDistributedComputeClient
import com.hereliesaz.geministrator.distributed.RelayDistributedComputeGateway
import com.hereliesaz.geministrator.distributed.computeWorkDecision
import com.hereliesaz.geministrator.distributed.SettingsComputeNodeEnrolmentLedger
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
import kotlinx.coroutines.launch

internal class AndroidDistributedComputeSession(
    context: Context,
    private val configuration: DistributedComputeConfiguration,
    token: String,
    baseIntegrations: TaskExecutorIntegrationRegistry,
    private val supportedExecutorKinds: Set<String>,
    /** This device's Keystore-held identity; the pool token only enrols it. */
    signer: ComputeNodeSigner,
    /** The pool owner's token, when the keeper set one: lets this device re-enrol itself after a revoke. */
    ownerToken: String? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = HttpClient(CIO) {
        install(WebSockets)
    }

    @Volatile
    private var currentNode: ComputeNodeDescriptor = androidComputeNode(
        context = appContext,
        configuration = configuration,
        supportedExecutorKinds = supportedExecutorKinds,
    )

    val node: ComputeNodeDescriptor get() = currentNode

    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNode()
        override fun onLost(network: Network) = refreshNode()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshNode()
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshNode()
    }

    fun start() {
        connectivity.registerDefaultNetworkCallback(networkCallback)
        ContextCompat.registerReceiver(
            appContext,
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        client.start(scope)
        // The worker stays subscribed even when this device is currently ineligible. Each offer is
        // checked against [currentNode], so becoming eligible later does not require recreating the
        // session or losing relay state.
        worker.start()
        refreshNode()
    }

    fun close() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { appContext.unregisterReceiver(powerReceiver) }
        scope.cancel()
        httpClient.close()
    }

    private fun refreshNode() {
        val updated = androidComputeNode(
            context = appContext,
            configuration = configuration,
            supportedExecutorKinds = supportedExecutorKinds,
        )
        if (updated == currentNode) return
        currentNode = updated
        scope.launch {
            runCatching { client.updateNode(updated) }
        }
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
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager?.currentThermalStatus else null
    val hot = thermalHoldReason(thermalStatus, battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE))
    noteThermalHold(hot)
    val decision = computeWorkDecision(
        configuration = configuration,
        meteredNetwork = metered,
        onExternalPower = onExternalPower,
        thermalHold = hot != null,
    )

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
            decision.hold?.let { add(it.capability) }
        },
        supportedExecutorKinds = supportedExecutorKinds,
        maxParallelLeases = configuration.maxParallelLeases,
        acceptsWork = decision.accepts,
        meteredNetwork = metered,
        onExternalPower = onExternalPower,
    )
}


internal fun androidComputePolicyAllowsWork(
    configuration: DistributedComputeConfiguration,
    meteredNetwork: Boolean,
    onExternalPower: Boolean,
    /** The phone is too hot to take new CPU-bound relay jobs (see [thermalHoldReason]). */
    thermalHold: Boolean = false,
): Boolean = computeWorkDecision(configuration, meteredNetwork, onExternalPower, thermalHold).accepts

/** PowerManager.THERMAL_STATUS_MODERATE: from here the system is already throttling. */
internal const val THERMAL_STATUS_MODERATE_VALUE = 2

/** Battery temperature, in tenths of a degree Celsius, at which new relay jobs are declined. */
internal const val BATTERY_HOT_TENTHS_C = 420

/**
 * Why new relay jobs should be declined because the phone is hot, or null when they may be taken. [thermalStatus] is
 * PowerManager.getCurrentThermalStatus (API 29+; null where unknown); [batteryTempTenthsC] is the battery's
 * EXTRA_TEMPERATURE (tenths of a degree Celsius; null or out of range where unknown). Pure.
 */
internal fun thermalHoldReason(thermalStatus: Int?, batteryTempTenthsC: Int?): String? = when {
    thermalStatus != null && thermalStatus >= THERMAL_STATUS_MODERATE_VALUE ->
        "The phone is running hot (thermal status $thermalStatus), so it is not taking new relay jobs."
    batteryTempTenthsC != null && batteryTempTenthsC in BATTERY_HOT_TENTHS_C..1000 ->
        "The battery is hot (${batteryTempTenthsC / 10} degrees C), so the phone is not taking new relay jobs."
    else -> null
}

@Volatile
private var lastThermalHold: String? = null

/** Says why, in the app log, when the hold starts or ends (not on every refresh). */
private fun noteThermalHold(reason: String?) {
    if ((reason == null) == (lastThermalHold == null)) return
    lastThermalHold = reason
    com.hereliesaz.geministrator.log.AppLog.record(
        "relay",
        reason ?: "The phone has cooled down, so it takes new relay jobs again.",
    )
}
