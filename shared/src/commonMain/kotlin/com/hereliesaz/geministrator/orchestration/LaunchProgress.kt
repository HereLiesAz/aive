package com.hereliesaz.geministrator.orchestration

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Carries human-readable launch progress lines from the code actually doing the work (workflow
 * launch, on-device planner) up to the UI, without widening [OrchestrationAgentRuntime]'s API.
 *
 * Install it with `withContext(LaunchProgress { line -> ... })`; any code running inside that
 * coroutine (including `withContext(Dispatchers.Default)` hops, which inherit the element) can call
 * [reportLaunchProgress]. With no element installed, reporting is a no-op.
 */
class LaunchProgress(private val sink: (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    fun report(line: String) = sink(line)

    companion object Key : CoroutineContext.Key<LaunchProgress>
}

/** Reports [line] to the enclosing [LaunchProgress], if any. */
suspend fun reportLaunchProgress(line: String) {
    coroutineContext[LaunchProgress]?.report(line)
}

/** The enclosing [LaunchProgress]'s sink, for non-suspending inner loops; no-op when absent. */
suspend fun currentLaunchProgressSink(): (String) -> Unit =
    coroutineContext[LaunchProgress]?.let { progress -> progress::report } ?: {}
