package com.hereliesaz.geministrator.persistence

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId

/**
 * A [WorkflowPersistence] that keeps the user's workflow state on the device: whole-store export
 * and import, .ive project files, data deletion and corruption recovery. Implemented by the SQL
 * store (Android, desktop) and the Settings store (web).
 */
interface DurableWorkflowPersistence : WorkflowPersistence {
    /** The last unreadable-data failure, until [recoverFromCorruption] clears it. */
    val lastCorruptionError: Throwable?

    suspend fun exportJson(): String
    suspend fun importJson(encoded: String)
    suspend fun exportProjectFile(projectId: ProjectId, savedAtEpochMillis: Long): String
    suspend fun importProjectFile(encoded: String): Project
    suspend fun clearWorkflowData()

    /** Discards unreadable workflow state. Returns whether there was any to discard. */
    suspend fun recoverFromCorruption(): Boolean
}
