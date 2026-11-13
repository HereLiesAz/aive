package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

/*
 * A step's contract (docs/Step-Templates.md). A step is an ordinary task for a generic role; the contract is
 * what stays deterministic around the creature: typed inputs, a closed tool set, typed outputs checked by a
 * compiled validator, declared effects and the template version it was made from. Everything is defaulted, so
 * a definition saved before this existed still reads.
 */

/** What a step may do beyond writing its own output. Declared by its template and never widened by a proposal. */
@Serializable
enum class StepEffect {
    None,
    ReadsConnectedSource,
    WritesRepositoryBranch,
    MutatesConnectedSource,
    DestructiveConnectedSource,
    LeavesDevice,
}

/** Effects that change something outside the step's own output; they need an approval and a single attempt. */
val StepEffect.isEffectful: Boolean
    get() = this == StepEffect.WritesRepositoryBranch ||
        this == StepEffect.MutatesConnectedSource ||
        this == StepEffect.DestructiveConnectedSource ||
        this == StepEffect.LeavesDevice

/** True when any of [this] changes something outside the step's own output. */
fun Set<StepEffect>.anyEffectful(): Boolean = any { it.isEffectful }

/** True when any of [this] changes a connected source (the kind the taint rule and the tool-call policy weigh). */
fun Set<StepEffect>.mutatesConnectedSource(): Boolean =
    StepEffect.MutatesConnectedSource in this || StepEffect.DestructiveConnectedSource in this

/** The type of one input port. [Any] accepts every artifact. */
@Serializable
enum class PortType { Text, Markdown, Html, Table, Research, Any }

@Serializable
data class PortSpec(
    val name: String,
    val type: PortType,
    val required: Boolean,
)

/** One typed output: the kind and media type of the artifact, a schema tag and the compiled validator that checks it. */
@Serializable
data class OutputSpec(
    val name: String,
    val kind: ArtifactKind,
    val mediaType: String,
    val schema: String,
    val validatorId: String,
)

/** The template version a step was made from, pinned when the step was made. */
@Serializable
data class TemplateRef(
    val id: String,
    val version: Int,
) {
    val key: String get() = "$id@$version"

    companion object {
        /** `id@version`, or null when [text] is not of that form. */
        fun parse(text: String): TemplateRef? {
            val at = text.lastIndexOf('@')
            if (at <= 0) return null
            val version = text.substring(at + 1).toIntOrNull() ?: return null
            return if (version >= 1) TemplateRef(text.substring(0, at), version) else null
        }
    }
}

/** Artifact metadata key naming the template version that made the artifact. */
const val TEMPLATE_METADATA_KEY: String = "aive.template"

/** Artifact metadata key naming the output schema tag. */
const val SCHEMA_METADATA_KEY: String = "aive.schema"

@Serializable
data class TaskContract(
    val template: TemplateRef,
    /** Port name to a literal or `@<taskId>.<port>`. */
    val inputs: Map<String, String> = emptyMap(),
    /** Names of the tool offers this step may use: a subset of its template's set; empty means none. */
    val allowedTools: List<String> = emptyList(),
    val outputs: List<OutputSpec> = emptyList(),
    val effects: Set<StepEffect> = emptySet(),
    /** Declared allowance in tokens for this step; carried, not yet enforced by a breaker. */
    val tokenAllowance: Long? = null,
    /**
     * True for a step that existed before it named a template: the contract says which template the step started from
     * and what that template hands back, and nothing more. It does not narrow the step's tools (it keeps the run's
     * toolbox), does not hold the step for a missing input, and does not fail the step on its output. The Nanny can
     * still read it by template and improve the template. False for a step made from a template, which is enforced.
     */
    val startingPoint: Boolean = false,
)
