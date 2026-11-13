package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepInstantiation
import com.hereliesaz.geministrator.template.TemplateCatalog
import com.hereliesaz.geministrator.template.TemplateKind
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.humanReadable
import kotlinx.serialization.Serializable

/*
 * Blockers (docs/Step-Templates.md). A blocker is something that has to be created or obtained for THIS hive to be
 * complete: a document, a page, a table, files on a branch, a read or a change of a connected tool. It is typed data on
 * the run, not a model's opinion: an id, one plain sentence, optionally the id of a starting template from the closed
 * catalog, optionally the step that makes it, and a state. The planner can name them in its plan, the queen can add
 * them in the middle of a run, the keeper can add and change them, and the Nanny's findings can carry them to the next
 * hive. Nothing here calls a model, and nothing here adds a screen: the queen says what is missing in Messages, and
 * the hive is not called reached while one is open (see hiveReached).
 */

enum class BlockerState { Open, InProgress, Done, Dropped }

@Serializable
data class Blocker(
    val id: String,
    /** One plain line, from the planner, the queen, the keeper or the Nanny; shown on its own quoted line, never as her sentence. */
    val sentence: String,
    /** A template or preset of the closed catalog ([BuiltInTemplates.byId]) to start from, or null for none. */
    val templateId: String? = null,
    /** The step of the swarm that makes it, once there is one. */
    val stepId: String? = null,
    val state: BlockerState = BlockerState.Open,
) {
    /** True while the hive is held: the blocker is open or being made. */
    val unmet: Boolean get() = state == BlockerState.Open || state == BlockerState.InProgress
}

/** The result of giving a blocker a step made from its starting template. */
sealed interface BlockerAssignment {
    data class Assigned(val definition: WorkflowDefinition, val blocker: Blocker) : BlockerAssignment
    data class Rejected(val reason: String) : BlockerAssignment
}

object Blockers {
    const val MAX = 8
    const val MAX_SENTENCE = 160
    private const val MAX_ID = 40

    private fun cleanId(raw: String): String =
        raw.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }.take(MAX_ID)

    private fun cleanSentence(raw: String): String = queenText(raw, MAX_SENTENCE)

    /**
     * The starting template, kept only when it is in the closed catalog; for the queen only one that changes nothing
     * outside its own output (a keeper may name any). Anything else becomes "none".
     */
    fun startingTemplate(templateId: String?, byKeeper: Boolean): String? {
        val template = templateId?.trim()?.let { BuiltInTemplates.byId[it] } ?: return null
        return if (byKeeper || !template.effectful) template.id else null
    }

    /** `sentence`, `sentence => template-id` or `sentence => template-id @ step-id` as its three parts (the last two may be null). */
    fun parse(raw: String): Triple<String, String?, String?> {
        val (head, step) = raw.substringBefore(" @ ").let { it to raw.substringAfter(" @ ", "").trim().ifEmpty { null } }
        val sentence = head.substringBefore(" => ")
        val template = head.substringAfter(" => ", "").trim().ifEmpty { null }
        return Triple(sentence, template, step)
    }

    /** The blockers the planner named in [plan]: bounded, cleaned, no repeats; unknown templates and steps are dropped. */
    fun nominated(plan: OrchestrationPlan): List<Blocker> {
        val stepIds = plan.steps.map { it.id }.toSet()
        return plan.blockers.fold(emptyList<Blocker>()) { acc, raw ->
            val (sentence, templateId, stepId) = parse(raw)
            add(acc, "", sentence, templateId, stepId?.takeIf { it in stepIds }, byKeeper = false)
        }
    }

    /**
     * [existing] with one more blocker, or [existing] when the sentence is blank, already there, or the list is full.
     * An empty [id] gets the next free `b<n>`.
     */
    fun add(
        existing: List<Blocker>,
        id: String,
        sentence: String,
        templateId: String?,
        stepId: String?,
        byKeeper: Boolean,
    ): List<Blocker> {
        val line = cleanSentence(sentence)
        if (line.isBlank() || existing.size >= MAX) return existing
        if (existing.any { it.sentence.equals(line, ignoreCase = true) && it.state != BlockerState.Dropped }) return existing
        val taken = existing.map { it.id }.toSet()
        var chosen = cleanId(id)
        if (chosen.isEmpty() || chosen in taken) {
            var n = existing.size + 1
            while ("b$n" in taken) n++
            chosen = "b$n"
        }
        return existing + Blocker(chosen, line, startingTemplate(templateId, byKeeper), stepId?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** [existing] with blocker [id] in [state]; unchanged when there is none. The keeper's mark (Done, Dropped) holds. */
    fun setState(existing: List<Blocker>, id: String, state: BlockerState): List<Blocker> =
        existing.map { if (it.id == id) it.copy(state = state) else it }

    /**
     * What each blocker's state is now, from the run (pure). Done and Dropped are marks that hold. Otherwise a blocker with a
     * step follows that step: finished means Done, working means InProgress, failed or stopped means Open again; a
     * step the swarm no longer has leaves the blocker as it was.
     */
    fun resolved(blockers: List<Blocker>, definition: WorkflowDefinition, run: WorkflowRun): List<Blocker> =
        blockers.map { blocker ->
            val stepId = blocker.stepId
            if (blocker.state == BlockerState.Done || blocker.state == BlockerState.Dropped || stepId == null) return@map blocker
            if (definition.tasks.none { it.id.value == stepId }) return@map blocker
            val status = run.taskRuns.values.firstOrNull { it.taskDefinitionId.value == stepId }?.status
            val state = when (status) {
                TaskRunStatus.Completed -> BlockerState.Done
                TaskRunStatus.Running, TaskRunStatus.Planning, TaskRunStatus.Verifying, TaskRunStatus.Retrying -> BlockerState.InProgress
                TaskRunStatus.Failed, TaskRunStatus.Escalated, TaskRunStatus.Cancelled -> BlockerState.Open
                else -> blocker.state
            }
            blocker.copy(state = state)
        }

    /** The blockers that still hold the hive. */
    fun unmet(blockers: List<Blocker>): List<Blocker> = blockers.filter { it.unmet }

    /**
     * What a finished hive hands to the next one (the carry-forward): every blocker still unmet, open again and without a
     * step, so the next swarm is planned knowing what is still missing. A Nanny finding that names something missing is
     * added the same way, through [add].
     */
    fun carryForward(finished: List<Blocker>): List<Blocker> =
        unmet(finished).map { it.copy(state = BlockerState.Open, stepId = null) }.take(MAX)

    /**
     * Gives [blocker] a step made from its starting template: the template's own contract, the version the catalog has now,
     * its first required input set to the blocker's sentence. The queen may do this only for a template that changes
     * nothing outside its output ([byKeeper] false); an effectful one is the keeper's, and it carries its approval. The
     * swarm is checked by the existing validation; a swarm that fails it is not changed.
     */
    fun assignStep(
        definition: WorkflowDefinition,
        blocker: Blocker,
        catalog: TemplateCatalog? = null,
        byKeeper: Boolean = false,
    ): BlockerAssignment {
        val templateId = blocker.templateId ?: return BlockerAssignment.Rejected("This blocker has no starting template.")
        val template = BuiltInTemplates.byId[templateId] ?: return BlockerAssignment.Rejected("Unknown starting template.")
        if (template.effectful && !byKeeper) return BlockerAssignment.Rejected("Only the keeper may start a step that changes something.")
        if (blocker.stepId != null && definition.tasks.any { it.id.value == blocker.stepId }) {
            return BlockerAssignment.Rejected("This blocker already has a step.")
        }
        val taken = definition.tasks.map { it.id.value }.toSet()
        val stem = "blocker-" + cleanId(blocker.id).ifEmpty { "step" }
        var stepId = stem
        var n = 2
        while (stepId in taken) stepId = "$stem-${n++}"
        val port = template.inputs.firstOrNull { it.required }?.name
        val inputs = if (port == null) emptyMap() else mapOf(port to blocker.sentence)
        val base = catalog?.current(TemplateKind.Step, template.id) ?: template.base()
        val task = StepInstantiation.task(template, base, TaskDefinitionId(stepId), queenSlot(blocker.sentence), inputs)
        val candidate = definition.copy(tasks = definition.tasks + task)
        val errors = WorkflowGraphValidator.validate(candidate)
        if (errors.isNotEmpty()) return BlockerAssignment.Rejected(errors.joinToString(" ") { it.humanReadable() })
        return BlockerAssignment.Assigned(candidate, blocker.copy(stepId = stepId, state = BlockerState.InProgress))
    }
}

/**
 * What she says once in Messages about each blocker still unmet: the sentence is the existing "something is missing", with
 * the blocker's own words on a quoted line, linking to its step when it has one, else to the Story. Stable ids.
 */
internal fun blockerNotes(runId: String, definition: WorkflowDefinition, run: WorkflowRun, blockers: List<Blocker>): List<QueenNote> =
    Blockers.unmet(Blockers.resolved(blockers, definition, run)).map { blocker ->
        val step = blocker.stepId?.takeIf { id -> definition.tasks.any { it.id.value == id } }
        QueenNote(
            id = queenNoteId(runId, "missing", "blocker", blocker.id, blocker.state.name),
            voice = QueenVoice.MissingBlocker,
            link = if (step != null) QueenLink.Creature(step, CreatureTab.Work) else QueenLink.Story,
            quotes = listOf(QueenQuote(QueenQuoteLabel.StillMissing, queenQuoteText(blocker.sentence, Blockers.MAX_SENTENCE))),
        )
    }
