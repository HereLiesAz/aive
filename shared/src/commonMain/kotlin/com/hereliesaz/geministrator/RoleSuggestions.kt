package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition

/*
 * What a role is for and what it connects to, read from the role catalog itself (its authorities and
 * required capabilities), never from a role's name. The flow table mirrors how the starter workflow
 * wires roles (StarterWorkflowFactory: implementation, then verification, then review, then release)
 * and the test-design policy (tests are designed before implementation and expanded after it).
 * Pure: nothing here edits a hive.
 */

/** The jobs a role can do in a hive, derived from its authorities and capabilities. */
internal enum class RoleFunction { Researcher, Planner, EnvironmentPlanner, TestWriter, Implementer, Verifier, Reviewer, Releaser, Recovery }

/** The job an authority marks, or null for a gate (approving or rejecting a plan, approving integration) that is not a job of its own. */
internal fun RoleAuthority.function(): RoleFunction? = when (this) {
    RoleAuthority.ProposePlan -> RoleFunction.Planner
    RoleAuthority.RejectPlan, RoleAuthority.ApprovePlan, RoleAuthority.ApproveIntegration -> null
    RoleAuthority.SelectEnvironment -> RoleFunction.EnvironmentPlanner
    RoleAuthority.Implement -> RoleFunction.Implementer
    RoleAuthority.AuthorTests -> RoleFunction.TestWriter
    RoleAuthority.Verify -> RoleFunction.Verifier
    RoleAuthority.ReviewCode -> RoleFunction.Reviewer
    RoleAuthority.ApproveRelease -> RoleFunction.Releaser
    RoleAuthority.DiagnoseFailure -> RoleFunction.Recovery
}

internal fun RoleDefinition.functions(): Set<RoleFunction> = buildSet {
    authorities.forEach { it.function()?.let(::add) }
    if (AgentCapability.Research in capabilitiesRequired) add(RoleFunction.Researcher)
}

/** Where work usually comes from and goes to, per job. */
private val receivesFrom: Map<RoleFunction, List<RoleFunction>> = mapOf(
    RoleFunction.Researcher to emptyList(),
    RoleFunction.Planner to listOf(RoleFunction.Researcher),
    RoleFunction.EnvironmentPlanner to listOf(RoleFunction.Planner),
    RoleFunction.TestWriter to listOf(RoleFunction.Planner),
    RoleFunction.Implementer to listOf(RoleFunction.Planner, RoleFunction.TestWriter, RoleFunction.EnvironmentPlanner),
    RoleFunction.Verifier to listOf(RoleFunction.Implementer),
    RoleFunction.Reviewer to listOf(RoleFunction.Implementer, RoleFunction.Verifier),
    RoleFunction.Releaser to listOf(RoleFunction.Reviewer),
    RoleFunction.Recovery to listOf(RoleFunction.Implementer, RoleFunction.Verifier),
)

private val handsTo: Map<RoleFunction, List<RoleFunction>> = mapOf(
    RoleFunction.Researcher to listOf(RoleFunction.Planner),
    RoleFunction.Planner to listOf(RoleFunction.Implementer, RoleFunction.TestWriter),
    RoleFunction.EnvironmentPlanner to listOf(RoleFunction.Implementer),
    RoleFunction.TestWriter to listOf(RoleFunction.Implementer, RoleFunction.Verifier),
    RoleFunction.Implementer to listOf(RoleFunction.Verifier, RoleFunction.Reviewer),
    RoleFunction.Verifier to listOf(RoleFunction.Reviewer),
    RoleFunction.Reviewer to listOf(RoleFunction.Releaser),
    RoleFunction.Releaser to emptyList(),
    RoleFunction.Recovery to listOf(RoleFunction.Implementer),
)

/** What a role usually gets work from (its own jobs left out), in a stable order. */
internal fun RoleDefinition.usuallyReceivesFrom(): List<RoleFunction> {
    val own = functions()
    return functions().flatMap { receivesFrom.getValue(it) }.distinct().filter { it !in own }
}

/** What a role usually hands work to (its own jobs left out), in a stable order. */
internal fun RoleDefinition.usuallyHandsTo(): List<RoleFunction> {
    val own = functions()
    return functions().flatMap { handsTo.getValue(it) }.distinct().filter { it !in own }
}

/** Why a role is suggested; the screen turns each into a plain sentence. */
internal enum class SuggestionReason { StartPlanner, StartImplementer, NothingReviews, NothingTests, NothingFeedsBuilder, NothingFeedsChecker, NoOrchestrator }

/** [subject] is the creature the reason is about (a task name) or the creature count for [SuggestionReason.NoOrchestrator]. */
internal data class RoleSuggestion(val role: RoleDefinition, val reason: SuggestionReason, val subject: String = "")

internal const val MAX_ROLE_SUGGESTIONS = 3

private val preferredFor: Map<RoleFunction, RoleDefinitionId> = mapOf(
    RoleFunction.Planner to BuiltInRoles.Orchestrator.id,
    RoleFunction.TestWriter to BuiltInRoles.CrashTestDummy.id,
    RoleFunction.Implementer to BuiltInRoles.ImplementationEngineer.id,
    RoleFunction.Verifier to BuiltInRoles.QaEngineer.id,
    RoleFunction.Reviewer to BuiltInRoles.CodeReviewer.id,
    RoleFunction.Releaser to BuiltInRoles.ReleaseEngineer.id,
    RoleFunction.Recovery to BuiltInRoles.RecoveryEngineer.id,
)

/** The role to offer for [function]: the catalog's usual one when installed, else the most focused match. */
private fun pick(roles: List<RoleDefinition>, function: RoleFunction): RoleDefinition? {
    val matches = roles.filter { function in it.functions() }
    return matches.firstOrNull { it.id == preferredFor[function] }
        ?: matches.minWithOrNull(compareBy<RoleDefinition>({ it.functions().size }, { it.name.lowercase() }))
}

private fun TaskDefinition.functionsIn(byId: Map<RoleDefinitionId, RoleDefinition>): Set<RoleFunction> = buildSet {
    roleId?.let(byId::get)?.let { addAll(it.functions()) }
    requiredRoleAuthority?.function()?.let(::add)
}

/**
 * Up to [MAX_ROLE_SUGGESTIONS] roles worth adding next, from what the hive has and lacks:
 * an empty hive gets a planner and an implementer; otherwise a builder with no reviewer gets one,
 * a builder with nothing testing it gets a tester, a creature with nothing feeding it gets the role
 * that usually would, and three or more creatures with no orchestrator get one. Only enabled roles
 * in [roles] are offered, and never one doing the same job twice in a list.
 */
internal fun suggestRoles(definition: WorkflowDefinition, roles: Collection<RoleDefinition>): List<RoleSuggestion> {
    val offered = terrariumAddableRoles(roles)
    val byId = roles.associateBy { it.id }
    val out = mutableListOf<RoleSuggestion>()
    fun offer(function: RoleFunction, reason: SuggestionReason, subject: String = "") {
        val role = pick(offered, function) ?: return
        if (out.none { it.role.id == role.id }) out += RoleSuggestion(role, reason, subject)
    }

    if (definition.tasks.isEmpty()) {
        offer(RoleFunction.Planner, SuggestionReason.StartPlanner)
        offer(RoleFunction.Implementer, SuggestionReason.StartImplementer)
        return out.take(MAX_ROLE_SUGGESTIONS)
    }

    val has = definition.tasks.associateWith { it.functionsIn(byId) }
    val present = has.values.flatten().toSet()
    val builder = has.entries.firstOrNull { RoleFunction.Implementer in it.value }?.key

    if (builder != null && RoleFunction.Reviewer !in present) offer(RoleFunction.Reviewer, SuggestionReason.NothingReviews, builder.name)
    if (builder != null && RoleFunction.Verifier !in present && RoleFunction.TestWriter !in present) {
        offer(RoleFunction.Verifier, SuggestionReason.NothingTests, builder.name)
    }
    // Nothing feeds it: a builder with no input and no planner, or a checker with no builder at all.
    has.forEach { (task, jobs) ->
        if (task.dependsOn.isNotEmpty()) return@forEach
        if (RoleFunction.Implementer in jobs && RoleFunction.Planner !in present) {
            offer(RoleFunction.Planner, SuggestionReason.NothingFeedsBuilder, task.name)
        }
        if ((RoleFunction.Verifier in jobs || RoleFunction.Reviewer in jobs) && RoleFunction.Implementer !in present) {
            offer(RoleFunction.Implementer, SuggestionReason.NothingFeedsChecker, task.name)
        }
    }
    val orchestrator = offered.firstOrNull { it.id == BuiltInRoles.Orchestrator.id }
    val hasOrchestrator = definition.tasks.any { it.roleId ==BuiltInRoles.Orchestrator.id }
    if (definition.tasks.size >= 3 && !hasOrchestrator && orchestrator != null && out.none { it.role.id == orchestrator.id }) {
        out += RoleSuggestion(orchestrator, SuggestionReason.NoOrchestrator, definition.tasks.size.toString())
    }
    return out.take(MAX_ROLE_SUGGESTIONS)
}
