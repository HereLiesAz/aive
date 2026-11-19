package com.hereliesaz.geministrator.tour

import com.hereliesaz.geministrator.CreatureBirth
import com.hereliesaz.geministrator.projectBirths
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.keeperName

/** Where one creature is, in the few words the tour layer can say about it. Read from the run; nothing is invented. */
enum class CreatureStage { Egg, Withered, Waiting, Working, Checking, Retrying, NeedsYou, Done, Failed, Cancelled }

/** One row of "what the hive is doing": [growth] (0..1) is only set for an egg. */
data class CreatureLine(val id: String, val name: String, val stage: CreatureStage, val growth: Float? = null)

/** Every creature the keeper can see right now, in the hive's order: eggs that are growing, and the creatures that have hatched. Hidden eggs are left out. */
internal fun creatureLines(definition: WorkflowDefinition, run: WorkflowRun, roles: Collection<RoleDefinition>): List<CreatureLine> {
    val births = projectBirths(definition, run)
    val rolesById = roles.associateBy { it.id }
    return definition.tasks.mapNotNull { task ->
        val taskRun = run.taskRuns[task.id]
        val role = (taskRun?.assignedRoleId ?: task.roleId)?.let(rolesById::get)
        val name = role?.keeperName() ?: task.name
        val id = task.id.value
        when (val birth = births[id]) {
            CreatureBirth.Hidden -> null
            CreatureBirth.Withered -> CreatureLine(id, name, CreatureStage.Withered)
            is CreatureBirth.Egg -> CreatureLine(id, name, CreatureStage.Egg, birth.growth)
            null -> CreatureLine(
                id,
                name,
                when (taskRun?.status) {
                    TaskRunStatus.Planning, TaskRunStatus.Running -> CreatureStage.Working
                    TaskRunStatus.Verifying -> CreatureStage.Checking
                    TaskRunStatus.Retrying -> CreatureStage.Retrying
                    TaskRunStatus.AwaitingApproval, TaskRunStatus.Escalated -> CreatureStage.NeedsYou
                    TaskRunStatus.Completed -> CreatureStage.Done
                    TaskRunStatus.Failed -> CreatureStage.Failed
                    TaskRunStatus.Cancelled -> CreatureStage.Cancelled
                    // Not released yet and fed by others: no egg is drawn for it, so none is listed.
                    TaskRunStatus.Created, TaskRunStatus.Blocked, null -> if (task.dependsOn.isEmpty()) CreatureStage.Waiting else return@mapNotNull null
                    TaskRunStatus.Ready -> CreatureStage.Waiting
                },
            )
        }
    }
}
