package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.workflow.AllProvidersFailedException
import com.hereliesaz.geministrator.workflow.ProviderBench
import com.hereliesaz.geministrator.workflow.ProviderFailover
import com.hereliesaz.geministrator.workflow.ProviderFailure
import com.hereliesaz.geministrator.workflow.reportFailoverNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One connected provider that can plan: its catalog id, its name for the keeper, and its planner. */
class PlannerCandidate(val providerId: String, val displayName: String, val runtime: OrchestrationAgentRuntime)

/**
 * Plans with the first connected provider and, when that one fails for a reason that is its own
 * (out of credit, rejected key, rate limit, offline, timeout, 5xx), asks the next one the same
 * question, each provider at most once per call (docs/Orchestration_layer.md, provider failover).
 * A failure that would repeat anywhere (an unreadable plan, a refused prompt) is rethrown at once.
 * With one candidate the behaviour is exactly the candidate's own. When all fail, the error names
 * every provider and what to do. Each switch is reported with [reportFailoverNote] and launch progress.
 */
class FailoverOrchestrationAgentRuntime(
    private val candidates: List<PlannerCandidate>,
    private val bench: ProviderBench = ProviderBench(),
) : OrchestrationAgentRuntime {
    private val lock = Mutex()
    private var answeredBy: String? = null

    override val lastSourceId: String? get() = answeredBy

    override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan = attempt { it.plan(packet) }

    override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan = attempt { it.repair(packet) }

    private suspend fun attempt(call: suspend (OrchestrationAgentRuntime) -> OrchestrationPlan): OrchestrationPlan {
        if (candidates.size <= 1) {
            val only = candidates.firstOrNull() ?: error("No planner available: link an LLM")
            return call(only.runtime).also { answeredBy = only.providerId }
        }
        val tried = mutableSetOf<String>()
        val failures = mutableListOf<ProviderFailure>()
        while (true) {
            val next = lock.withLock {
                ProviderFailover.nextCandidate(candidates, { it.providerId }, tried) { bench.isBenched(it) }
            } ?: throw AllProvidersFailedException(failures)
            tried += next.providerId
            try {
                val plan = call(next.runtime)
                lock.withLock { bench.clear(next.providerId) }
                answeredBy = next.providerId
                failures.lastOrNull()?.let { announce(it, next.displayName) }
                return plan
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val classified = ProviderFailover.failureOf(next.providerId, next.displayName, failure.message)
                // Would fail the same on every provider: do not spend the prompt on a second service.
                if (!ProviderFailover.shouldFailOver(classified.kind)) throw failure
                lock.withLock { bench.bench(classified) }
                failures += classified
            }
        }
    }

    private suspend fun announce(failed: ProviderFailure, continuedWith: String) {
        val note = ProviderFailover.note(failed, continuedWith)
        reportFailoverNote(note)
        reportLaunchProgress(note)
    }
}
