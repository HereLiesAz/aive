package com.hereliesaz.geministrator.template

import kotlinx.serialization.Serializable

/*
 * Templates and their lineage (docs/Step-Templates.md). A template is a starting point the Nanny can improve over
 * time: a step template (a task for a role) or a role template (a role's own definition). One mechanism serves both.
 * Every improvement is a child generation of the version it came from; siblings may coexist; retiring a revision
 * archives its branch and keeps it. What a template is for and what it may change (its LOCKED fields) never changes
 * by a proposal alone.
 */

@Serializable
enum class TemplateKind(val label: String) {
    Step("step"),
    Role("role"),
}

/**
 * [Candidate] is a new generation kept beside its parent while it is evaluated: it is never resolved, so the parent
 * line keeps running. [Reverted] means the branch was retired and is kept for the record; it is never resolved again.
 */
@Serializable
enum class TemplateStatus { Approved, Superseded, Reverted, Candidate }

/** How fast a change moves: a quick change inside the current generation, or a new generation made on purpose. */
@Serializable
enum class RevisionMode(val minEvidenceSteps: Int) {
    Memetic(MEMETIC_MIN_STEPS),
    Genetic(GENETIC_MIN_STEPS),
}

/** Steps of run evidence a quick change needs, and a new generation needs (a larger threshold, judged across runs). */
const val MEMETIC_MIN_STEPS: Int = 3
const val GENETIC_MIN_STEPS: Int = 10

/** What a revision aims to improve: a closed set, so the next runs can be judged against it. */
@Serializable
enum class RevisionAim(val label: String) {
    FewerRetries("fewer retries"),
    LowerCost("lower cost"),
    Faster("faster"),
    FewerRejectedOutputs("fewer rejected outputs"),
    FewerKeeperEdits("fewer keeper edits"),
}

/** Who asked for the revision: the Nanny from her evidence, or the keeper or the queen with an intent of their own. */
@Serializable
enum class RevisionDirector { Nanny, Keeper, Queen }

/** A deliberate transfer: the lineage a change was taken from, and the generation it had reached. */
@Serializable
data class LateralLink(val kind: TemplateKind, val templateId: String, val version: Int, val generation: Int)

/** Why a revision exists and how it will be judged. Data only; recording it needs no model call. */
@Serializable
data class RevisionIntent(
    val aim: RevisionAim,
    /** One plain sentence: what it aims to improve. */
    val sentence: String,
    /** The failures or costs in the evidence it answers. */
    val hypothesis: String,
    /** How the next runs will judge it, in words and numbers (for example "retries per run below 1 over 3 runs"). */
    val measure: String,
    val directedBy: RevisionDirector = RevisionDirector.Nanny,
)

/** What a revision changed: the mutation record of a generation. */
@Serializable
enum class TunableField { Wording, Criteria, ToolsNarrowed, Validator, Allowance, Retry }

/** The fields a revision may change. For a step the wording is its objective; for a role, its instructions. */
@Serializable
data class TemplateBody(
    val wording: String,
    val criteria: List<String> = emptyList(),
    /** The closed set of tool names (a ceiling: a revision may only shrink it). */
    val allowedTools: List<String> = emptyList(),
    /** Output name to compiled validator id. */
    val validators: Map<String, String> = emptyMap(),
    /** A step's token allowance, or a role's token budget; null for none. */
    val budgetTokens: Long? = null,
    val maxAttempts: Int? = null,
)

/**
 * One version of a template as the catalog knows it: [locked] holds the fields no proposal may change, as a name to
 * value map so two versions compare by plain equality. [toolsOpen] is true when the keeper picks the tool per use.
 */
data class TemplateBase(
    val kind: TemplateKind,
    val id: String,
    val version: Int,
    val body: TemplateBody,
    val locked: Map<String, String>,
    val toolsOpen: Boolean = false,
)

/** What a Nanny proposal names: `step:<id>@<version>` or `role:<id>@<version>`. */
data class TemplateTarget(val kind: TemplateKind, val id: String, val version: Int) {
    val key: String get() = "${kind.label}:$id@$version"

    companion object {
        fun parse(text: String): TemplateTarget? {
            val kind = TemplateKind.entries.firstOrNull { text.startsWith(it.label + ":") } ?: return null
            val rest = text.substring(kind.label.length + 1)
            val at = rest.lastIndexOf('@')
            if (at <= 0) return null
            val version = rest.substring(at + 1).toIntOrNull() ?: return null
            return if (version >= 1) TemplateTarget(kind, rest.substring(0, at), version) else null
        }
    }
}

/**
 * The change a proposal asks for, carried as the proposal's text. Only the fields set are changed. [locked], when
 * present, is the draft's claim of the locked fields: anything that differs from the version it targets is refused.
 */
@Serializable
data class TemplateRevisionDraft(
    val target: String,
    val wording: String? = null,
    val criteria: List<String>? = null,
    val allowedTools: List<String>? = null,
    val validators: Map<String, String>? = null,
    val budgetTokens: Long? = null,
    val maxAttempts: Int? = null,
    val locked: Map<String, String>? = null,
    val mode: RevisionMode = RevisionMode.Memetic,
    val intent: RevisionIntent? = null,
    /** Set for a transfer: where the change worked. */
    val donor: LateralLink? = null,
    /** Steps of run evidence behind it, counted by the code that drafted the proposal, never read from model text. */
    val evidenceSteps: Int = 0,
)

/**
 * An adopted revision: a child generation. [parentVersion] is the version it descends from (a built-in or another
 * revision), [generation] counts from the built-in (1 for its first child), [mutation] says what changed and
 * [fitness] is the Nanny's run evidence it was adopted on. [body] is the full result.
 */
@Serializable
data class TemplateRevision(
    val kind: TemplateKind,
    val templateId: String,
    val version: Int,
    val parentVersion: Int,
    val generation: Int,
    val mutation: Set<TunableField>,
    val body: TemplateBody,
    val locked: Map<String, String>,
    val before: String,
    val after: String,
    val fitness: String,
    val proposalId: String,
    val status: TemplateStatus = TemplateStatus.Approved,
    val adoptedAtEpochMillis: Long,
    val mode: RevisionMode = RevisionMode.Memetic,
    val intent: RevisionIntent? = null,
    val donor: LateralLink? = null,
    /** How it did against its own intent once enough runs followed; null until judged. */
    val outcome: String? = null,
)
