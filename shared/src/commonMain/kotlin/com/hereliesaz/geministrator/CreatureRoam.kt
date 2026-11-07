package com.hereliesaz.geministrator

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.domain.isTerminal
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Roaming (docs/Node-Creatures.md, Roaming): one creature does a role's work, walking between its
 * tasks, instead of a new creature for every task of that role.
 *
 * A default, not a rule. Tasks of one role that are **ordered** (one depends, directly or through
 * others, on the other) share a creature, because the engine already runs them one after another, so
 * nothing needs locking. Tasks of one role that can run **at the same time** (parallel siblings,
 * Mixture-of-Agents proposers, a deliberate second review) each keep their own creature. Programmatic
 * work never shares: it is not a role's.
 *
 * Everything is read from the run and the workflow: nothing is stored, nothing is random.
 */
internal sealed interface CreatureRoam {
    /** The shared creature's body is drawn at this task, the one it is working on or about to. */
    data class Rider(
        /** The task it came from, the one before this in its group; null when this is the first. */
        val from: String?,
    ) : CreatureRoam

    /** A task of a shared creature that the creature is not at: drawn as the place it works. */
    data object Site : CreatureRoam
}

/** The tasks one creature does, in the order it does them, and the one it is at now. */
internal data class RoamGroup(
    val roleId: String,
    val members: List<String>,
    val rider: String,
)

internal data class RoamPlan(
    val groups: List<RoamGroup> = emptyList(),
    /** Every task of a shared creature, by task id. A task not listed has a creature of its own. */
    val roam: Map<String, CreatureRoam> = emptyMap(),
) {
    /** The creature [taskId] belongs to, when it is shared. */
    fun groupOf(taskId: String): RoamGroup? = groups.firstOrNull { taskId in it.members }
}

/**
 * Who shares a creature with whom. [building] shows every creature in its own place for editing, as does
 * a hive that has not started; the Swarm view asks for the same by passing [building] too.
 */
internal fun projectRoaming(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    building: Boolean = false,
): RoamPlan {
    val started = run.status != WorkflowRunStatus.Created ||
        run.taskRuns.values.any { it.status != TaskRunStatus.Created }
    if (building || !started) return RoamPlan()

    val byId = definition.tasks.associateBy { it.id.value }
    val feedersOf = definition.tasks.associate { it.id.value to it.feeders().filter { id -> id in byId } }
    val ancestors = HashMap<String, Set<String>>()
    fun ancestorsOf(id: String, path: Set<String> = emptySet()): Set<String> {
        ancestors[id]?.let { return it }
        if (id in path) return emptySet() // a cycle is the validator's to refuse; here it just ends
        val result = linkedSetOf<String>()
        for (feeder in feedersOf[id].orEmpty()) {
            result += feeder
            result += ancestorsOf(feeder, path + id)
        }
        return result.also { ancestors[id] = it }
    }

    // Dependency order, ties in the workflow's own order: a task is placed after everything it waits on.
    val order = buildList {
        val placed = hashSetOf<String>()
        fun place(id: String, path: Set<String> = emptySet()) {
            if (id in placed || id in path) return
            feedersOf[id].orEmpty().forEach { place(it, path + id) }
            if (placed.add(id)) add(id)
        }
        definition.tasks.forEach { place(it.id.value) }
    }

    // A task joins the first creature of its role whose every task is an ancestor of it; else it starts one.
    val groups = mutableListOf<Pair<String, MutableList<String>>>()
    for (id in order) {
        val task = byId.getValue(id)
        val role = task.sharedRole(run.taskRuns[task.id]) ?: continue
        val mine = ancestorsOf(id)
        val home = groups.firstOrNull { (groupRole, members) -> groupRole == role && members.all { it in mine } }
        if (home != null) home.second += id else groups += role to mutableListOf(id)
    }

    val shared = groups.filter { it.second.size > 1 }.map { (role, members) ->
        RoamGroup(role, members.toList(), rider = riderOf(members, run.taskRuns, byId))
    }
    val roam = linkedMapOf<String, CreatureRoam>()
    for (group in shared) {
        val at = group.members.indexOf(group.rider)
        group.members.forEach { id ->
            roam[id] = if (id == group.rider) CreatureRoam.Rider(group.members.getOrNull(at - 1)) else CreatureRoam.Site
        }
    }
    return RoamPlan(shared, roam)
}

/** The role a task's creature belongs to when it may share one: an agent's role, never programmatic work. */
private fun TaskDefinition.sharedRole(taskRun: TaskRun?): String? {
    val executor = taskRun?.executor ?: effectiveExecutor()
    if (!executor.isAgentExecutor()) return null
    return (taskRun?.assignedRoleId ?: roleId)?.value
}

private fun TaskExecutor.isAgentExecutor(): Boolean = when (this) {
    is TaskExecutor.RoleAgent -> true
    is TaskExecutor.Distributed -> delegate.isAgentExecutor()
    else -> false
}

/**
 * Where the creature is: at the task it is working on; else the next one released and waiting; else the
 * last it was born into; else the first. Read only from the tasks' recorded statuses.
 */
private fun riderOf(members: List<String>, runs: Map<com.hereliesaz.geministrator.domain.TaskDefinitionId, TaskRun>, byId: Map<String, TaskDefinition>): String {
    fun runOf(id: String): TaskRun? = runs[byId.getValue(id).id]
    members.firstOrNull { id -> runOf(id)?.let { it.isBorn() && !it.status.isTerminal() && it.status != TaskRunStatus.Ready } == true }
        ?.let { return it }
    members.firstOrNull { id -> runOf(id)?.status == TaskRunStatus.Ready }?.let { return it }
    members.lastOrNull { id -> runOf(id)?.isBorn() == true }?.let { return it }
    return members.first()
}

/** How long a shared creature takes to walk from one of its tasks to the next. */
internal const val RIDE_MILLIS = 1_100

/**
 * The walks in progress: a rider that is new since the field first drew (the run recorded that its
 * previous task finished and the next began) walks from where it was, `1` at the start of the walk and
 * `0` on arrival. A field opened mid-run, or run with reduced motion, shows riders where they are.
 * [riders] maps each rider to the task it comes from; one with nowhere to come from does not walk.
 */
@Composable
internal fun rememberRides(riders: Map<String, String>, reduceMotion: Boolean): Map<String, Float> {
    val progress = remember { mutableStateMapOf<String, Float>() }
    var seen by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(riders) {
        val before = seen
        seen = riders.keys.toSet()
        if (before == null || reduceMotion) return@LaunchedEffect
        coroutineScope {
            for (id in riders.keys - before) launch {
                try {
                    progress[id] = 1f
                    Animatable(1f).animateTo(0f, tween(RIDE_MILLIS, easing = FastOutSlowInEasing)) { progress[id] = value }
                } finally {
                    progress.remove(id)
                }
            }
        }
    }
    return progress
}
