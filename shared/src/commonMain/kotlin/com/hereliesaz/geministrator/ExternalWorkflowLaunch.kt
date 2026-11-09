package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.RepositoryRef

/**
 * An orchestrated workflow another app asked for (Android: through the remote service). [App]
 * launches it on its runtime the same way the launch form does, then reports the outcome through
 * `onExternalWorkflowLaunchHandled`: once the workflow started, or with the reason it could not.
 */
data class ExternalWorkflowLaunchRequest(
    val requestId: Long,
    val projectName: String,
    val objective: String,
    val repository: RepositoryRef? = null,
    /**
     * Where [objective] came from. A request from another app or agent is not the keeper's own typing, so the run
     * it starts treats its objective as an input to every task for the context-taint rule.
     */
    val objectiveProvenance: ContextProvenance = ContextProvenance.ExternalContent,
)
