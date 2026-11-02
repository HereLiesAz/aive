package com.hereliesaz.geministrator.domain

/**
 * Where a piece of task context came from (docs/THREAT_MODEL.md, "Context provenance").
 *
 * Artifacts carry it in [ArtifactRef.metadata] under [CONTEXT_PROVENANCE_METADATA_KEY], so stored
 * runs need no schema change; an artifact without the key is [Unknown], which counts as external.
 */
enum class ContextProvenance {
    /** Written by the keeper: the objective, the definition's task text, their messages. */
    KeeperWritten,

    /** Produced by an agent or executor of this run from its own inputs. */
    AgentArtifact,

    /** Read from the project's own repository (repository operations, retrieved repository files). */
    RepositoryContent,

    /** From outside the keeper's control: web research, external services, deployments, pool devices. */
    ExternalContent,

    /** Recalled from the keeper's memory layer. */
    MemoryRecall,

    /** No label: stored before labelling, or from a source that did not say. Treated as external. */
    Unknown,
}

/** The [ArtifactRef.metadata] key holding the artifact's [ContextProvenance] name. */
const val CONTEXT_PROVENANCE_METADATA_KEY: String = "aive.provenance"

/**
 * The [ArtifactRef.metadata] key set to "true" when the task that made the artifact read external or
 * unknown content: whatever it made may carry that content on (taint propagates).
 */
const val CONTEXT_UNTRUSTED_INPUT_METADATA_KEY: String = "aive.provenance.untrustedInput"

/** Content a privileged task may not act on without a person's approval. */
fun ContextProvenance.isUntrusted(): Boolean =
    this == ContextProvenance.ExternalContent || this == ContextProvenance.Unknown

/** The artifact's label; missing or unrecognised labels are [ContextProvenance.Unknown]. */
val ArtifactRef.provenance: ContextProvenance
    get() = metadata[CONTEXT_PROVENANCE_METADATA_KEY]
        ?.let { name -> ContextProvenance.entries.firstOrNull { it.name == name } }
        ?: ContextProvenance.Unknown

/** Whether the task that made this artifact read external or unknown content. */
val ArtifactRef.madeFromUntrustedInput: Boolean
    get() = metadata[CONTEXT_UNTRUSTED_INPUT_METADATA_KEY] == "true"

/** External or unknown content, or content made from it. */
fun ArtifactRef.isUntrustedContext(): Boolean = provenance.isUntrusted() || madeFromUntrustedInput

/**
 * Labels an artifact as the runtime takes it in. The runtime's [assigned] label replaces whatever the
 * producer wrote, so a provider or executor cannot claim more trust than it has; it may only lower it:
 * a producer's own [ContextProvenance.ExternalContent] label and untrusted-input flag are kept.
 * [assigned] null keeps the existing label (an artifact relayed from a child run that labelled it).
 */
fun ArtifactRef.labelledAtIntake(assigned: ContextProvenance?, madeFromUntrustedInput: Boolean): ArtifactRef {
    val producerSaidExternal = metadata[CONTEXT_PROVENANCE_METADATA_KEY] == ContextProvenance.ExternalContent.name
    val label = when {
        producerSaidExternal -> ContextProvenance.ExternalContent
        assigned != null -> assigned
        else -> provenance
    }
    val untrustedInput = madeFromUntrustedInput || this.madeFromUntrustedInput
    val labelled = buildMap<String, String> {
        putAll(metadata)
        put(CONTEXT_PROVENANCE_METADATA_KEY, label.name)
        if (untrustedInput) put(CONTEXT_UNTRUSTED_INPUT_METADATA_KEY, "true") else remove(CONTEXT_UNTRUSTED_INPUT_METADATA_KEY)
    }
    return if (labelled == metadata) this else copy(metadata = labelled)
}
