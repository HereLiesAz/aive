package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import kotlinx.serialization.Serializable

/*
 * The requirements checklist: what the keeper wrote or set, as statements a plan can be checked against. Whatever code can
 * tick is Pure and costs no model call; the rest is Judged by the reviewer. A requirement is never invented by a model.
 */

enum class RequirementSource { Goal, Gates, Safety, Vocabulary, Structure, KeeperRole }

enum class RequirementCheck { Pure, Judged }

data class Requirement(
    val id: String,
    val source: RequirementSource,
    val text: String,
    val check: RequirementCheck,
    /** The keeper's own words, for a Goal clause. */
    val quote: String? = null,
)

enum class RequirementVerdict { Met, Unmet, CannotTell }

data class RequirementResult(val id: String, val verdict: RequirementVerdict, val evidence: String, val byCode: Boolean)

data class RequirementChecklist(val items: List<Requirement>) {
    val judged: List<Requirement> get() = items.filter { it.check == RequirementCheck.Judged }
}

/** What the checklist is made from. */
data class RequirementInputs(
    val objective: String,
    /** The objective did not come from the keeper's own typing. */
    val objectiveExternal: Boolean,
    val roles: List<RoleDefinition>,
    /** Roles the planner may use (a disabled or unstaffable role is not in it). */
    val availableRoleIds: Set<String>,
)

object Requirements {
    const val MAX_PLAN_STEPS = 12
    const val MAX_GOAL_CLAUSES = 8
    const val MAX_JUDGED = 12
    const val CLAUSE_CHARS = 200
    const val MAX_NAME_WORDS = 4

    private val clauseWords = Regex("""\b(must|should|never|only|without|at most|no more than|at least|within|before)\b""", RegexOption.IGNORE_CASE)
    private val stepLimit = Regex("""\b(?:at most|no more than|up to|max(?:imum)?(?: of)?)\s+(\d{1,2})\s+steps?\b""", RegexOption.IGNORE_CASE)
    private val retired = Regex("""\b(timelines?|providers?|inbox(es)?|hall[ -]monitors?|drifters?)\b""", RegexOption.IGNORE_CASE)
    private val mutating = setOf(RoleAuthority.Implement, RoleAuthority.AuthorTests, RoleAuthority.ApproveIntegration, RoleAuthority.ApproveRelease)

    internal fun RoleDefinition.mutates(): Boolean =
        authorities.any { it in mutating } || AgentCapability.RepositoryWrite in capabilitiesRequired

    /** The step limit the goal states ("at most 5 steps"), else [MAX_PLAN_STEPS]. */
    fun stepLimit(objective: String): Int =
        stepLimit.find(objective)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, MAX_PLAN_STEPS) ?: MAX_PLAN_STEPS

    /** Deterministic: the same inputs give the same checklist (no clock, no model). */
    fun extract(inputs: RequirementInputs): RequirementChecklist {
        val items = mutableListOf<Requirement>()
        items += Requirement("R-structure", RequirementSource.Structure, "At most ${stepLimit(inputs.objective)} steps, each depending only on earlier steps.", RequirementCheck.Pure)
        items += Requirement("R-gates", RequirementSource.Gates, "Every step that can change the repository sits behind the keeper's approval.", RequirementCheck.Pure)
        items += Requirement("R-roles", RequirementSource.KeeperRole, "Every step uses a role that is switched on.", RequirementCheck.Pure)
        items += Requirement("R-words", RequirementSource.Vocabulary, "Step names are at most $MAX_NAME_WORDS words and use the keeper's words.", RequirementCheck.Pure)
        if (inputs.objectiveExternal) {
            items += Requirement("R-outside", RequirementSource.Safety, "The goal came from outside: no step may change anything without approval.", RequirementCheck.Pure)
        }
        val clauses = inputs.objective.split('.', '!', '?', '\n').map { it.trim() }
            .filter { it.isNotEmpty() && clauseWords.containsMatchIn(it) }
            .map { it.take(CLAUSE_CHARS) }.distinct().take(MAX_GOAL_CLAUSES)
        clauses.forEachIndexed { i, clause ->
            items += Requirement("R-goal-${i + 1}", RequirementSource.Goal, "The plan honours this: $clause", RequirementCheck.Judged, quote = clause)
        }
        val (pure, judged) = items.partition { it.check == RequirementCheck.Pure }
        return RequirementChecklist(pure + judged.take(MAX_JUDGED))
    }

    private fun approvedAncestry(plan: OrchestrationPlan, step: OrchestrationPlanStep): Boolean {
        val byId = plan.steps.associateBy { it.id }
        val seen = mutableSetOf<String>()
        fun walk(s: OrchestrationPlanStep): Boolean {
            if (s.requiresHumanApproval) return true
            if (!seen.add(s.id)) return false
            return s.dependsOn.any { dep -> byId[dep]?.let(::walk) == true }
        }
        return walk(step)
    }

    /** The results the code alone can give for the Pure items. */
    fun checkPure(inputs: RequirementInputs, plan: OrchestrationPlan, checklist: RequirementChecklist): List<RequirementResult> {
        val rolesById = inputs.roles.associateBy { it.id.value }
        val ids = plan.steps.map { it.id }.toSet()
        return checklist.items.filter { it.check == RequirementCheck.Pure }.map { req ->
            val problem: String? = when (req.id) {
                "R-structure" -> when {
                    plan.steps.size > stepLimit(inputs.objective) -> "The plan has ${plan.steps.size} steps."
                    plan.steps.any { s -> s.dependsOn.any { it !in ids || it == s.id } } -> "A step depends on a step that is not in the plan."
                    else -> null
                }
                "R-gates" -> plan.steps.firstOrNull { s -> rolesById[s.roleId]?.mutates() == true && !approvedAncestry(plan, s) }
                    ?.let { "Step ${it.id} can change the repository and has no approval before it." }
                "R-outside" -> plan.steps.firstOrNull { s -> rolesById[s.roleId]?.mutates() == true && !approvedAncestry(plan, s) }
                    ?.let { "Step ${it.id} can change something and the goal came from outside." }
                "R-roles" -> plan.steps.firstOrNull { it.roleId !in inputs.availableRoleIds }?.let { "Step ${it.id} uses a role that is not available." }
                "R-words" -> plan.steps.firstOrNull { s ->
                    s.name.trim().split(Regex("\\s+")).size > MAX_NAME_WORDS || retired.containsMatchIn(s.name)
                }?.let { "Step ${it.id} has a name that is too long or uses a retired word." }
                else -> null
            }
            RequirementResult(req.id, if (problem == null) RequirementVerdict.Met else RequirementVerdict.Unmet, problem ?: "Checked.", byCode = true)
        }
    }

    /** Approve only when nothing is Unmet. A model's yes never overrules an Unmet result. */
    fun approves(results: List<RequirementResult>): Boolean = results.none { it.verdict == RequirementVerdict.Unmet }
}

/** What the reviewer may suggest beyond the requirements: a closed set. */
enum class PlanSuggestionKind { AddCheck, SplitStep, ReorderSteps, AddApproval, SwapSource, ConnectSource, RaiseAllowance, Narrow, Other }

/** One idea beyond the requirements: plain text only, bounded. */
@Serializable
data class PlanSuggestion(val kind: PlanSuggestionKind, val text: String, val why: String, val stepId: String?)

/** The reviewer's reply lines. Malformed lines are counted, never shifted. */
data class ReviewReply(
    val results: List<RequirementResult>,
    val suggestions: List<PlanSuggestion>,
    val malformed: Int,
)

object ReviewReplyParser {
    const val MAX_SUGGESTIONS = 3
    private const val TEXT = 280
    private const val EVIDENCE = 300

    private fun clean(raw: String, max: Int): String =
        raw.filter { !it.isISOControl() && it !in "`*_#<>[]" }.replace(Regex("(?i)(https?://|www\\.)\\S+"), "link").trim().take(max)

    /**
     * Reads `REQ | id | MET|UNMET|UNSURE | step ids | evidence` and `SUGGEST | kind | text | why | step id or - `.
     * Unknown requirement ids and unknown kinds are dropped; at most [MAX_SUGGESTIONS] suggestions are kept.
     */
    fun parse(text: String, checklist: RequirementChecklist): ReviewReply {
        val judged = checklist.judged.map { it.id }.toSet()
        val results = mutableListOf<RequirementResult>()
        val suggestions = mutableListOf<PlanSuggestion>()
        var malformed = 0
        for (raw in text.lineSequence()) {
            val parts = raw.trim().split(" | ").map { it.trim() }
            when (parts.firstOrNull()?.uppercase()) {
                "REQ" -> {
                    val verdict = when (parts.getOrNull(2)?.uppercase()) {
                        "MET" -> RequirementVerdict.Met
                        "UNMET" -> RequirementVerdict.Unmet
                        "UNSURE" -> RequirementVerdict.CannotTell
                        else -> null
                    }
                    if (parts.size < 5 || verdict == null) malformed += 1
                    else if (parts[1] in judged && results.none { it.id == parts[1] }) {
                        results += RequirementResult(parts[1], verdict, clean(parts[4], EVIDENCE), byCode = false)
                    }
                }
                "SUGGEST" -> {
                    val kind = PlanSuggestionKind.entries.firstOrNull { it.name.equals(parts.getOrNull(1), ignoreCase = true) }
                    if (parts.size < 5) malformed += 1
                    else if (kind != null && suggestions.size < MAX_SUGGESTIONS && parts[2].isNotBlank()) {
                        suggestions += PlanSuggestion(kind, clean(parts[2], TEXT), clean(parts[3], TEXT), parts[4].takeIf { it != "-" && it.isNotBlank() }?.take(40))
                    }
                }
            }
        }
        return ReviewReply(results, suggestions, malformed)
    }
}
