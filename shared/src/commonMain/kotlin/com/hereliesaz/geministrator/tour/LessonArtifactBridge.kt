package com.hereliesaz.geministrator.tour

import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.workflow.TaskOutputValidatorRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What one creature's output holds as a lesson: nothing, a lesson that may play, or a lesson that must be redone. */
sealed interface LessonHandIn {
    /** No payload line: the output is ordinary text and is left alone. */
    data object None : LessonHandIn

    /** A payload that passed [LessonValidator]. */
    data class Play(val payload: LessonPayload) : LessonHandIn

    /** A payload line that broke rules; [problems] are plain sentences, the task's failure reason. Nothing is played or invented. */
    data class Turned(val problems: List<String>) : LessonHandIn
}

/**
 * The swarm's own lesson creatures hand in the overlays (the keeper's ruling: the tutorial hive is both the lesson and its writer).
 * A creature of a run for a goal to be taught the app ends its output with one JSON line in the form of [LessonPayload]; this reads
 * that line, checks it with [LessonValidator] against the real registry, and either returns it to play (voiced by the creature) or
 * returns why it was turned back, which becomes an ordinary task failure reason so the usual retry and the queen's mending redo it.
 * Pure: no model, no network, nothing played here. Output is untrusted model text and is only parsed, never executed.
 */
object LessonArtifactBridge {
    /** The id of the compiled output check ([registry]) a lesson creature's task names. */
    const val VALIDATOR_ID: String = "lesson-payload"

    /** The registry rows of this layout, kept by the app shell (null or empty: lessons cannot be judged and none is turned back). */
    var rowsOf: () -> List<TargetRow> = { emptyList() }

    /** True once a lesson creature of the current tour has had a lesson played; the in-process writer then steps aside. */
    var supplied: Boolean = false

    private val json = Json { isLenient = false }

    /** The last line of [output] that is one JSON object holding both a target and a sentence, or null. */
    fun payloadLine(output: String): String? = output.lineSequence()
        .map { it.trim() }
        .lastOrNull { it.startsWith("{") && it.endsWith("}") && it.contains("\"target\"") && it.contains("\"sentence\"") }

    /**
     * The context a swarm's lesson is judged in. The steps are separate creatures that finish in any order, so what was introduced
     * earlier and which lesson is first are not known here: every control counts as introduced (L5 is the planner's order, not
     * this check's) and the consent lesson (L9) is not demanded. Every other rule holds.
     */
    fun contextFor(target: String, rows: List<TargetRow>): LessonContext = LessonContext(
        plannedTarget = target,
        targets = rows,
        uiWords = emptySet(),
        introduced = rows.map { it.id }.toSet(),
    )

    fun inspect(output: String, rows: List<TargetRow>, voice: String? = null): LessonHandIn {
        if (rows.isEmpty()) return LessonHandIn.None
        val line = payloadLine(output) ?: return LessonHandIn.None
        val target = try {
            ((json.parseToJsonElement(line) as? JsonObject)?.get("target") as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (_: Exception) {
            null
        }
        return when (val result = LessonValidator.validate(line, contextFor(target.orEmpty(), rows))) {
            is LessonValidation.Valid -> LessonHandIn.Play(result.payload.copy(voice = voice))
            is LessonValidation.Invalid -> LessonHandIn.Turned(result.violations.map { "${it.rule}: ${it.message}" }.distinct())
        }
    }

    /** The problems of the newest artifact with text among [artifacts] that carries a turned-back lesson; empty otherwise. */
    fun problems(artifacts: List<ArtifactRef>, rows: List<TargetRow>): List<String> {
        val turned = latestText(artifacts)?.let { inspect(it, rows) } as? LessonHandIn.Turned
        return turned?.problems.orEmpty()
    }

    /** The compiled check a lesson creature's task names (TaskOutputValidators.kt): an invalid payload turns the attempt back. */
    fun registry(rows: () -> List<TargetRow> = { rowsOf() }):TaskOutputValidatorRegistry =
        TaskOutputValidatorRegistry.Empty.with(VALIDATOR_ID) { artifacts -> problems(artifacts, rows()) }

    private fun latestText(artifacts: List<ArtifactRef>): String? =
        artifacts.filter { !it.textContent.isNullOrBlank() }.maxByOrNull { it.createdAtEpochMillis }?.textContent

    /**
     * The lessons newly handed in by the completed creatures of [run], in the order they finished, each voiced by its creature.
     * [played] holds what was already taken (run, task and attempt) and is added to here; a task is taken once.
     */
    fun newLessons(run: WorkflowRun, rows: List<TargetRow>, played: MutableSet<String>): List<LessonPayload> {
        if (rows.isEmpty()) return emptyList()
        val found = mutableListOf<Pair<Long, LessonPayload>>()
        for ((taskId, taskRun) in run.taskRuns) {
            if (taskRun.status != TaskRunStatus.Completed) continue
            if (!played.add("${run.id.value}:${taskId.value}:${taskRun.attempt}")) continue
            val text = latestText(taskRun.artifacts) ?: continue
            val voice = run.roleSnapshot.firstOrNull { it.id == taskRun.assignedRoleId }?.name
                ?: run.definitionSnapshot?.tasks?.firstOrNull { it.id == taskId }?.name
            val hand = inspect(text, rows, voice)
            if (hand is LessonHandIn.Play) {
                found += (taskRun.artifacts.maxOfOrNull { it.createdAtEpochMillis } ?: 0L) to hand.payload
            }
        }
        return found.sortedBy { it.first }.map { it.second }
    }
}
