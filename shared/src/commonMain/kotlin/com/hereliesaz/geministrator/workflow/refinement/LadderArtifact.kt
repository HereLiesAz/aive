package com.hereliesaz.geministrator.workflow.refinement

import com.hereliesaz.geministrator.memory.procedural.ProcedureSideEffect
import com.hereliesaz.geministrator.memory.procedural.ProcedureStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The typed answer of one refinement-ladder level, `aive.ladder.level.v1`.
 *
 * A level that opts into the contract (`RefinementLevel.structured`) answers with this document
 * instead of prose. Each level's [decisions] say which decisions of the coarser level they
 * [LadderDecision.refines], so a finer level provably builds on the structure above it. The finest
 * reasoning level also lists [steps]; a step may carry an [operation] shaped like a procedure step,
 * which is validated with the same bounds as `ProcedureStep`. The document describes work; it
 * grants nothing and executes nothing.
 */
@Serializable
data class LadderArtifact(
    val schema: String = SCHEMA,
    val level: Int,
    val of: Int,
    val summary: String,
    val decisions: List<LadderDecision> = emptyList(),
    val steps: List<LadderStep> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA: String = "aive.ladder.level.v1"
    }
}

/** One thing a level decided. [refines] names decisions of the next coarser level (none at level 1). */
@Serializable
data class LadderDecision(
    val id: String,
    val statement: String,
    val refines: List<String> = emptyList(),
)

@Serializable
data class LadderStep(
    val id: String,
    val summary: String,
    /** Earlier steps of the same level; this keeps the plan finite and acyclic. */
    val dependsOn: List<String> = emptyList(),
    val acceptance: List<String> = emptyList(),
    val operation: LadderOperation? = null,
)

/** A step's operation, shaped like `ProcedureStep`: an existing executor operation with symbolic bindings. */
@Serializable
data class LadderOperation(
    val executorKind: String,
    val operationId: String,
    val sideEffect: ProcedureSideEffect,
    val parameterBindings: Map<String, String> = emptyMap(),
)

sealed interface LadderParse {
    data class Parsed(val artifact: LadderArtifact) : LadderParse
    data class Malformed(val reason: String) : LadderParse
}

object LadderArtifactCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    private val fence = Regex("""```(?:json)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE)

    /** The JSON object in [text]: the body of a code fence if there is one, else the trimmed text. */
    fun extractJson(text: String): String? {
        val body = fence.find(text)?.groupValues?.get(1)?.trim() ?: text.trim()
        return body.takeIf { it.startsWith("{") && it.endsWith("}") }
    }

    fun decode(text: String): LadderParse {
        val body = extractJson(text) ?: return LadderParse.Malformed("not a JSON object")
        return try {
            LadderParse.Parsed(json.decodeFromString(LadderArtifact.serializer(), body))
        } catch (failure: SerializationException) {
            LadderParse.Malformed(failure.message?.lineSequence()?.firstOrNull().orEmpty().take(200).ifBlank { "malformed" })
        } catch (failure: IllegalArgumentException) {
            LadderParse.Malformed(failure.message.orEmpty().take(200).ifBlank { "malformed" })
        }
    }

    fun encode(artifact: LadderArtifact): String = json.encodeToString(LadderArtifact.serializer(), artifact)
}

/** The prompt text of the output contract. */
object LadderArtifactContract {
    fun instruction(level: Int, total: Int): String = buildString {
        append("Answer with one JSON object and nothing else, schema \"${LadderArtifact.SCHEMA}\": ")
        append("{\"schema\":\"${LadderArtifact.SCHEMA}\",\"level\":$level,\"of\":$total,\"summary\":\"...\",")
        append("\"decisions\":[{\"id\":\"d1\",\"statement\":\"...\",\"refines\":[ids of coarser decisions]}],")
        append("\"steps\":[],\"assumptions\":[],\"openQuestions\":[]}. ")
        if (level > 1) {
            append("Every decision of the coarser level must be refined by at least one of yours, and each `refines` id must exist there. ")
        } else {
            append("`refines` stays empty at level 1. ")
        }
        if (level == total) {
            append("As the finest level, list `steps` (earlier step ids only in `dependsOn`), each with `acceptance` and, where it names an existing executor operation, an `operation` {executorKind, operationId, sideEffect: ReadOnly|Reversible|Mutating|Privileged, parameterBindings}. ")
        } else {
            append("Leave `steps` empty; finer levels decide them. ")
        }
    }
}

object LadderArtifactValidator {
    const val MAX_LEVELS: Int = 4
    const val MAX_DECISIONS: Int = 32
    const val MAX_STEPS: Int = 32
    const val MAX_LIST: Int = 16
    const val MAX_TEXT: Int = 1000
    private const val MAX_ERRORS = 16
    private val identifier = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")

    /**
     * What is wrong with [artifact] as level [level] of [total], empty when it is sound. [coarser] is
     * the already-sound artifact of the level above; without it the coverage rule cannot be checked.
     */
    fun validate(
        artifact: LadderArtifact,
        level: Int,
        total: Int,
        coarser: LadderArtifact? = null,
    ): List<String> {
        val errors = mutableListOf<String>()
        fun err(message: String) {
            if (errors.size < MAX_ERRORS) errors += message
        }

        if (artifact.schema != LadderArtifact.SCHEMA) err("schema must be ${LadderArtifact.SCHEMA}")
        if (total !in 1..MAX_LEVELS) err("total must be 1..$MAX_LEVELS")
        if (artifact.of != total) err("of must be $total")
        if (artifact.level != level) err("level must be $level")
        if (level !in 1..total) err("level $level is outside 1..$total")
        text("summary", artifact.summary, ::err)

        if (artifact.decisions.size > MAX_DECISIONS) err("at most $MAX_DECISIONS decisions")
        val decisionIds = mutableSetOf<String>()
        for (decision in artifact.decisions) {
            if (!identifier.matches(decision.id)) err("decision id '${decision.id.take(40)}' is not an identifier")
            if (!decisionIds.add(decision.id)) err("duplicate decision id ${decision.id.take(40)}")
            text("decision ${decision.id.take(40)} statement", decision.statement, ::err)
            if (decision.refines.size > MAX_LIST) err("decision ${decision.id.take(40)} refines too many")
        }

        if (level == 1) {
            if (artifact.decisions.any { it.refines.isNotEmpty() }) err("level 1 decisions refine nothing")
        } else if (coarser != null) {
            val coarserIds = coarser.decisions.mapTo(mutableSetOf()) { it.id }
            val refined = mutableSetOf<String>()
            for (decision in artifact.decisions) {
                for (target in decision.refines) {
                    if (target !in coarserIds) err("decision ${decision.id.take(40)} refines unknown '${target.take(40)}'")
                    refined += target
                }
            }
            for (missing in coarserIds - refined) err("coarser decision '${missing.take(40)}' is not refined")
        }

        if (artifact.steps.size > MAX_STEPS) err("at most $MAX_STEPS steps")
        if (level < total && artifact.steps.isNotEmpty()) err("only the finest level lists steps")
        if (level == total && artifact.steps.isEmpty()) err("the finest level must list steps")
        val seenSteps = mutableSetOf<String>()
        for (step in artifact.steps) {
            if (!identifier.matches(step.id)) err("step id '${step.id.take(40)}' is not an identifier")
            text("step ${step.id.take(40)} summary", step.summary, ::err)
            for (dependency in step.dependsOn) {
                if (dependency !in seenSteps) err("step ${step.id.take(40)} depends on '${dependency.take(40)}', which is not an earlier step")
            }
            if (step.acceptance.size > 8) err("step ${step.id.take(40)} has more than 8 acceptance lines")
            step.acceptance.forEach { text("step ${step.id.take(40)} acceptance", it, ::err) }
            step.operation?.let { operation ->
                try {
                    ProcedureStep(
                        id = step.id,
                        executorKind = operation.executorKind,
                        operationId = operation.operationId,
                        sideEffect = operation.sideEffect,
                        parameterBindings = operation.parameterBindings,
                    )
                } catch (failure: IllegalArgumentException) {
                    err("step ${step.id.take(40)} operation: ${failure.message.orEmpty().take(120)}")
                }
            }
            if (!seenSteps.add(step.id)) err("duplicate step id ${step.id.take(40)}")
        }

        if (artifact.assumptions.size > MAX_LIST) err("at most $MAX_LIST assumptions")
        artifact.assumptions.forEach { text("assumption", it, ::err) }
        if (artifact.openQuestions.size > MAX_LIST) err("at most $MAX_LIST openQuestions")
        artifact.openQuestions.forEach { text("openQuestion", it, ::err) }
        return errors
    }

    private fun text(what: String, value: String, err: (String) -> Unit) {
        if (value.isBlank()) err("$what is blank")
        if (value.length > MAX_TEXT) err("$what is longer than $MAX_TEXT characters")
    }
}
