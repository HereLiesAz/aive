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
    /** Shared by the planners made from this one, which share its bench ([ProviderBench] is not thread-safe). */
    private val lock: Mutex = Mutex(),
) : OrchestrationAgentRuntime {
    private var answeredBy: String? = null

    override val lastSourceId: String? get() = answeredBy

    /** One planner for each lens, each trying the Sources in an order turned one place further than the last, so the first Source it asks is not the queen's when there is another. */
    override val planners: List<OrchestrationAgentRuntime>
        get() = if (candidates.size < 2 || candidates.any { tightlyLimited(it.providerId) }) {
            emptyList()
        } else {
            PlannerLens.entries.indices.map { index ->
                val turn = (index + 1) % candidates.size
                FailoverOrchestrationAgentRuntime(candidates.drop(turn) + candidates.take(turn), bench, lock)
            }
        }

    /**
     * A Source that answers only a few requests in a few minutes (OVHcloud) cannot carry three more drafts and a merge: they would
     * queue behind its limit for minutes. While any candidate is one, the queen plans alone and the plan is reviewed as before; she
     * also plans alone with fewer than two Sources, since the drafts would all go to the same one.
     */
    private fun tightlyLimited(providerId: String): Boolean =
        com.hereliesaz.geministrator.ProviderCatalog.keylessRateLimit(providerId)
            ?.let { limit -> limit.windowMillis <= TIGHT_WINDOW_MILLIS && limit.requests <= TIGHT_REQUESTS } == true

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

/** A Source that allows this few requests in a window this short cannot carry the drafts and the merge (OVHcloud: 2 a minute); Kilo's 200 an hour can. */
private const val TIGHT_REQUESTS = 5
private const val TIGHT_WINDOW_MILLIS = 5 * 60_000L
