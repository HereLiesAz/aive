package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.workflow.isTerminal
import kotlinx.coroutines.CancellationException

/**
 * The queen gives a blocker that has a starting template a step of its own, in the swarm already running (the engine's run amendment,
 * [ApplicationRuntime.amendRun]). Only for a template that changes nothing outside its own output: an effectful one stays the keeper's
 * and a swarm that fails validation is left as it was ([ApplicationRuntime.assignBlockerStep] says why and changes nothing).
 * Each blocker is tried once per run, so a refusal is not retried on every update. Collects until cancelled.
 */
internal suspend fun ApplicationRuntime.assignQueenBlockerSteps() {
    val tried = mutableSetOf<String>()
    state.collect { current ->
        val live = current as? ApplicationRuntimeState.Live ?: return@collect
        val run = live.presentation.run
        if (run.status.isTerminal()) return@collect
        for (blocker in blockersWaitingForAStep(run.id.value)) {
            if (!tried.add("${run.id.value}:${blocker.id}")) continue
            try {
                assignBlockerStep(run.id, blocker.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Left open: the blocker is still said in Messages and the keeper can start its step.
            }
        }
    }
}
