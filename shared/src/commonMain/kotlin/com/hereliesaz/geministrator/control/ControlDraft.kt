package com.hereliesaz.geministrator.control

import com.hereliesaz.geministrator.SwarmDraftProblem
import com.hereliesaz.geministrator.SwarmDraftResult
import com.hereliesaz.geministrator.classifyError
import com.hereliesaz.geministrator.domain.TaskDefinition

/** What a swarm draft came to, in terms the control API can show (see [SwarmDraftResult]). */
sealed interface ControlDraftOutcome {
    data class Ready(val suggestedName: String, val notes: List<String>, val tasks: List<TaskDefinition>) : ControlDraftOutcome
    data class Failed(val code: ControlErrorCode, val message: String) : ControlDraftOutcome
}

/** Maps the app's swarm draft onto a [ControlDraftOutcome]; a provider's own message is never passed on. */
fun controlDraftOutcome(result: SwarmDraftResult): ControlDraftOutcome = when (result) {
    is SwarmDraftResult.Ready -> ControlDraftOutcome.Ready(result.suggestedName, result.notes, result.tasks)
    is SwarmDraftResult.Problem -> ControlDraftOutcome.Failed(
        ControlErrorCode.InvalidArgument,
        "The draft could not be used: " + when (result.problem) {
            SwarmDraftProblem.NoPrompt -> "no prompt"
            SwarmDraftProblem.NoRoles -> "no role can be staffed"
            SwarmDraftProblem.Empty -> "the planner proposed nothing"
            SwarmDraftProblem.TooMany -> "too many creatures"
            SwarmDraftProblem.UnknownRole -> "the planner named an unknown role"
            SwarmDraftProblem.Cycle -> "the proposal had a cycle"
            SwarmDraftProblem.Unreadable -> "the proposal was unreadable"
        },
    )
    is SwarmDraftResult.ProviderFailed ->
        ControlDraftOutcome.Failed(ControlErrorCode.Unavailable, "The linked provider failed: ${classifyError(result.raw).name}")
    SwarmDraftResult.Unavailable ->
        ControlDraftOutcome.Failed(ControlErrorCode.Unavailable, "No planner is linked; connect a provider in the app")
}
