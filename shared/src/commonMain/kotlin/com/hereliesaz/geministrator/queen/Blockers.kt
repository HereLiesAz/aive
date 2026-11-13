package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepInstantiation
import com.hereliesaz.geministrator.template.TemplateCatalog
import com.hereliesaz.geministrator.template.TemplateKind
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import com.hereliesaz.geministrator.workflow.humanReadable
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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

/** Who put a blocker on the hive, so Messages can say it in plain words. */
enum class BlockerOrigin { Planner, Queen, Nanny, Keeper }

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
    val origin: BlockerOrigin = BlockerOrigin.Planner,
    /** The step that is waiting on this blocker (a step held for an input it does not have); its Needs tab is where the keeper can supply it. */
    val forStep: String? = null,
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
        origin: BlockerOrigin? = null,
        forStep: String? = null,
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
        return existing + Blocker(
            id = chosen,
            sentence = line,
            templateId = startingTemplate(templateId, byKeeper),
            stepId = stepId?.trim()?.takeIf { it.isNotEmpty() },
            origin = origin ?: if (byKeeper) BlockerOrigin.Keeper else BlockerOrigin.Planner,
            forStep = forStep?.trim()?.takeIf { it.isNotEmpty() },
        )
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
            if (blocker.state == BlockerState.Done || blocker.state == BlockerState.Dropped) return@map blocker
            val stepId = blocker.stepId ?: return@map resolvedWithoutStep(blocker, definition, run)
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

    /**
     * A blocker with no step of its own is resolved by what the creatures hand in: the step that was waiting on it
     * ([Blocker.forStep]) is no longer held for the input, or a finished step made from its starting template has output.
     */
    private fun resolvedWithoutStep(blocker: Blocker, definition: WorkflowDefinition, run: WorkflowRun): Blocker {
        val forStep = blocker.forStep
        if (forStep != null) {
            val waiting = run.taskRuns.values.firstOrNull { it.taskDefinitionId.value == forStep } ?: return blocker
            val stillHeld = waiting.blockingReason?.code == WorkflowRunFactory.CONTRACT_INPUT_MISSING
            return if (stillHeld) blocker else blocker.copy(state = BlockerState.Done)
        }
        val templateId = blocker.templateId ?: return blocker
        val made = definition.tasks.any { task ->
            task.contract?.template?.id == templateId &&
                run.taskRuns[task.id]?.let { it.status == TaskRunStatus.Completed && it.artifacts.isNotEmpty() } == true
        }
        return if (made) blocker.copy(state = BlockerState.Done) else blocker
    }

    /**
     * The queen's own addition in the middle of a run: a step held because a required input has no value means something
     * this hive needs is missing. One blocker per such step, never re-added (not even after the keeper dropped it); the
     * step's own Needs tab is where it is changed. No model.
     */
    fun fromHeldSteps(existing: List<Blocker>, definition: WorkflowDefinition, run: WorkflowRun): List<Blocker> =
        definition.tasks.fold(existing) { acc, task ->
            val taskRun = run.taskRuns[task.id] ?: return@fold acc
            if (taskRun.blockingReason?.code != WorkflowRunFactory.CONTRACT_INPUT_MISSING || taskRun.status.isTerminal()) return@fold acc
            val needs = task.contract?.let(StepInstantiation::missingInputs).orEmpty()
            if (needs.isEmpty() || acc.any { it.forStep == task.id.value }) return@fold acc
            add(acc, "", queenText(task.name, 60) + " needs its " + needs.joinToString(", "), null, null, byKeeper = false, origin = BlockerOrigin.Queen, forStep = task.id.value)
        }

    /** A Nanny finding whose subject starts with this names something the hive needs: its observation is the sentence. */
    const val NANNY_PREFIX = "missing:"

    /** The Nanny's findings that name something missing, added once each (also not after the keeper dropped it). */
    fun fromNannyFindings(existing: List<Blocker>, findings: List<NannyFinding>): List<Blocker> =
        findings.fold(existing) { acc, finding ->
            if (!finding.subject.trim().startsWith(NANNY_PREFIX, ignoreCase = true)) return@fold acc
            val line = cleanSentence(finding.observation)
            if (line.isBlank() || acc.any { it.sentence.equals(line, ignoreCase = true) }) return@fold acc
            add(acc, "", line, null, null, byKeeper = false, origin = BlockerOrigin.Nanny)
        }

    /** The blockers as the run stands now: what the queen and the Nanny found added, then what the creatures made resolved. */
    fun live(existing: List<Blocker>, definition: WorkflowDefinition, run: WorkflowRun, findings: List<NannyFinding>): List<Blocker> =
        resolved(fromNannyFindings(fromHeldSteps(existing, definition, run), findings), definition, run)

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
 * What she says once in Messages about each blocker: one still unmet is said as what is missing (in her own words when she
 * or the Nanny found it in the middle of the run), one the creatures have made as made. The blocker's own words are on a
 * quoted line, never in her sentence. A blocker links to its step when it has one, else to the step that waits on it (its
 * Needs tab), else to the Story. Stable ids.
 */
internal fun blockerNotes(runId: String, definition: WorkflowDefinition, run: WorkflowRun, blockers: List<Blocker>): List<QueenNote> =
    Blockers.resolved(blockers, definition, run).mapNotNull { blocker ->
        val has = { id: String? -> id != null && definition.tasks.any { it.id.value == id } }
        val link = when {
            has(blocker.stepId) -> QueenLink.Creature(blocker.stepId.orEmpty(), CreatureTab.Work)
            has(blocker.forStep) -> QueenLink.Creature(blocker.forStep.orEmpty(), CreatureTab.Needs)
            else -> QueenLink.Story
        }
        val text = queenQuoteText(blocker.sentence, Blockers.MAX_SENTENCE)
        when {
            blocker.state == BlockerState.Done -> QueenNote(
                id = queenNoteId(runId, "missing", "blocker", blocker.id, blocker.state.name),
                voice = QueenVoice.BlockerMade,
                link = link,
                quotes = listOf(QueenQuote(QueenQuoteLabel.NowMade, text)),
            )
            blocker.unmet -> QueenNote(
                id = queenNoteId(runId, "missing", "blocker", blocker.id, blocker.state.name),
                voice = when (blocker.origin) {
                    BlockerOrigin.Queen -> QueenVoice.BlockerAddedQueen
                    BlockerOrigin.Nanny -> QueenVoice.BlockerAddedNanny
                    BlockerOrigin.Planner, BlockerOrigin.Keeper -> QueenVoice.MissingBlocker
                },
                link = link,
                quotes = listOf(QueenQuote(QueenQuoteLabel.StillMissing, text)),
            )
            else -> null
        }
    }

/** What the app keeps of every run's blockers across a restart: one JSON value, run id to its blockers. Unreadable reads as none. */
object BlockerBook {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = MapSerializer(String.serializer(), ListSerializer(Blocker.serializer()))

    fun encode(book: Map<String, List<Blocker>>): String = json.encodeToString(serializer, book)

    fun decode(text: String?): Map<String, List<Blocker>> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString(serializer, text)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/** [BlockerBook] in a Settings store (the one the workflow persistence keeps beside the runs). Callers hand it a store that may hold long values. */
class BlockerStore(private val settings: Settings, private val key: String = DEFAULT_KEY) {
    fun load(): Map<String, List<Blocker>> = BlockerBook.decode(settings.getStringOrNull(key))

    fun save(book: Map<String, List<Blocker>>) {
        settings.putString(key, BlockerBook.encode(book))
    }

    companion object {
        const val DEFAULT_KEY = "aive.blockers.v1"
    }
}
