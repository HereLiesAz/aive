package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef

/**
 * What a reviewing task concluded about the work put to it.
 *
 * A task that requires a [ArtifactKind.Verification] artifact is a gate: when its artifact states a
 * verdict that is not [Approve], the engine marks the task failed, so everything that depends on it
 * stays blocked instead of running on work the gate turned back. An artifact that states no verdict
 * changes nothing, so existing reviewing tasks behave as before until their agents state one.
 */
enum class ArtifactVerdict {
    Approve,
    Revise,
    Reject,
    ;

    /** True when the work must not move on. */
    val blocks: Boolean get() = this != Approve
}

/** The [com.hereliesaz.geministrator.domain.BlockingReason] code of a task a gate turned back. */
const val GATE_VERDICT_BLOCKED: String = "gate-verdict-blocked"

/** Artifact metadata key a provider may use to state a verdict. */
const val ARTIFACT_VERDICT_METADATA: String = "verdict"

object ArtifactVerdicts {
    private val verdictLine = Regex("""^\W*verdict\W*[:=]\W*([A-Za-z]+)""", RegexOption.IGNORE_CASE)

    fun parse(word: String): ArtifactVerdict? = when (word.trim().lowercase()) {
        "approve", "approved", "pass", "passed" -> ArtifactVerdict.Approve
        "revise", "revision" -> ArtifactVerdict.Revise
        "reject", "rejected", "fail", "failed" -> ArtifactVerdict.Reject
        else -> null
    }

    /**
     * The verdict a [ArtifactKind.Verification] or [ArtifactKind.Review] artifact states: the
     * [ARTIFACT_VERDICT_METADATA] value, else a first non-blank line of the form `VERDICT: reject`.
     * Null when the artifact is another kind or states none.
     */
    fun of(artifact: ArtifactRef): ArtifactVerdict? {
        if (artifact.kind != ArtifactKind.Verification && artifact.kind != ArtifactKind.Review) return null
        artifact.metadata[ARTIFACT_VERDICT_METADATA]?.let(::parse)?.let { return it }
        val firstLine = artifact.textContent?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: return null
        return verdictLine.find(firstLine)?.groupValues?.get(1)?.let(::parse)
    }

    /** The first verdict among [artifacts] that blocks, or null when none does. */
    fun blocking(artifacts: List<ArtifactRef>): ArtifactVerdict? =
        artifacts.asSequence().mapNotNull(::of).firstOrNull { it.blocks }

    /** The instruction a gate's objective carries so its agent states a verdict the engine reads. */
    const val INSTRUCTION: String =
        "Begin the Verification artifact with exactly one line, `VERDICT: approve` or `VERDICT: reject`, then give your reasons."
}
