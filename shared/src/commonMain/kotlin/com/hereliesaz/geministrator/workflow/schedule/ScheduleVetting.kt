package com.hereliesaz.geministrator.workflow.schedule

import com.hereliesaz.geministrator.Sha256
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.workflow.FILM_ASSEMBLE_SERVICE
import com.hereliesaz.geministrator.workflow.FILM_FRAMES_SERVICE
import kotlinx.serialization.json.Json

/** What the host says about a hive a schedule is about to start (docs/Scheduled-Workflows.md). */
data class HiveVetting(
    /** [ScheduleVetting.fingerprint] of the hive as it is now. */
    val fingerprint: String,
    /** Why the hive may never be started by a schedule, or null. */
    val refusal: String? = null,
)

/** Two things a schedule checks about its hive before it starts it: whether it may be scheduled at all, and whether it is still the hive that was scheduled. */
object ScheduleVetting {
    private val json = Json { encodeDefaults = true; allowStructuredMapKeys = true }

    const val FILM_REFUSAL: String =
        "This hive draws or assembles film frames, which spend money, so it is never run on a schedule. Run it by hand."

    const val CHANGED_NOTE: String =
        "The hive changed since you scheduled it, so it was not started. Review the hive, then turn the schedule on again."

    private const val MAX_NESTING = 4

    /**
     * A fingerprint of everything in [definition] that changes what a run does: its steps (objectives, roles, executors, order,
     * conditions, policies) and its run-wide policies. Its name and description are left out, so renaming a hive is not a change.
     * The roles a step names are separate records and are not part of it.
     */
    fun fingerprint(definition: WorkflowDefinition): String =
        Sha256.hex(json.encodeToString(WorkflowDefinition.serializer(), definition.copy(name = "", description = null)).encodeToByteArray())

    /**
     * Why [definition] may not be scheduled, or null: it spends money through the film executors, directly or in a hive it nests
     * (found in [others]; a nested hive that is not there is not looked into).
     */
    fun refusal(definition: WorkflowDefinition, others: Map<WorkflowDefinitionId, WorkflowDefinition> = emptyMap()): String? =
        if (spendsOnFilm(definition, others, MAX_NESTING, mutableSetOf())) FILM_REFUSAL else null

    fun vet(definition: WorkflowDefinition, others: Map<WorkflowDefinitionId, WorkflowDefinition> = emptyMap()): HiveVetting =
        HiveVetting(fingerprint(definition), refusal(definition, others))

    private fun spendsOnFilm(
        definition: WorkflowDefinition,
        others: Map<WorkflowDefinitionId, WorkflowDefinition>,
        depth: Int,
        seen: MutableSet<WorkflowDefinitionId>,
    ): Boolean {
        if (!seen.add(definition.id)) return false
        return definition.tasks.any { task ->
            // A step handed to another node (Distributed) is still the executor it wraps.
            val executor = (task.executor as? TaskExecutor.Distributed)?.delegate ?: task.executor
            when (executor) {
                is TaskExecutor.ExternalService -> executor.service == FILM_FRAMES_SERVICE || executor.service == FILM_ASSEMBLE_SERVICE
                is TaskExecutor.NestedWorkflow ->
                    depth > 0 && others[executor.workflowDefinitionId]?.let { spendsOnFilm(it, others, depth - 1, seen) } == true
                else -> false
            }
        }
    }
}
