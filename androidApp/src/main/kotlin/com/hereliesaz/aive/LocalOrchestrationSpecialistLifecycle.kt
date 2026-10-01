package com.hereliesaz.aive

import com.hereliesaz.geministrator.LocalOrchestrationSpecialistStatus
import kotlinx.coroutines.CancellationException

internal suspend fun removeLocalOrchestrationSpecialists(
    resetExecutor: suspend () -> Unit,
    removeArtifacts: suspend () -> Unit,
): LocalOrchestrationSpecialistStatus = try {
    resetExecutor()
    removeArtifacts()
    LocalOrchestrationSpecialistStatus.NotInstalled
} catch (failure: CancellationException) {
    throw failure
} catch (failure: Throwable) {
    LocalOrchestrationSpecialistStatus.Failed(
        failure.message ?: failure::class.simpleName.orEmpty(),
    )
}
