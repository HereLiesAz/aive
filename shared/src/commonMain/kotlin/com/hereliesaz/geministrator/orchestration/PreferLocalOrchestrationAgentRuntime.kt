package com.hereliesaz.geministrator.orchestration

import kotlinx.coroutines.CancellationException

/**
 * Plans with [local] while [localReady] holds, and with [cloud] otherwise or when the local planner
 * fails. With no [cloud], a local failure propagates.
 */
class PreferLocalOrchestrationAgentRuntime(
    private val local: OrchestrationAgentRuntime,
    private val localReady: () -> Boolean,
    private val cloud: OrchestrationAgentRuntime?,
) : OrchestrationAgentRuntime {
    override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan = route { it.plan(packet) }

    override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan = route { it.repair(packet) }

    private suspend fun route(call: suspend (OrchestrationAgentRuntime) -> OrchestrationPlan): OrchestrationPlan {
        if (localReady()) {
            try {
                return call(local)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Throwable, not Exception: a model too large for memory surfaces as OutOfMemoryError.
                if (cloud == null) throw failure
                reportLaunchProgress("Local planner failed (${failure.message ?: failure::class.simpleName}); asking the linked LLM…")
            }
        }
        return call(cloud ?: error("No planner available: install the local planner or link an LLM"))
    }
}
