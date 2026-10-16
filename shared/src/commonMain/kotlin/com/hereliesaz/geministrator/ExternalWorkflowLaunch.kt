package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RepositoryRef

/**
 * An orchestrated workflow another app asked for (Android: through the remote service). [App]
 * launches it on its runtime the same way the launch form does, then reports the outcome.
 */
data class ExternalWorkflowLaunchRequest(
    val requestId: Long,
    val projectName: String,
    val objective: String,
    val repository: RepositoryRef? = null,
)
