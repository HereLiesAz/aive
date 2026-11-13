package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.orchestration.planReviewerRole

/** The two creatures the queen creates first: a planner to break the hive into steps and a reviewer to judge the work. */
data class QueenStaff(val planner: RoleDefinition, val reviewer: RoleDefinition)

/** What staffing the queen's two creatures came to: the staff, or what is missing. */
data class QueenStaffing(val staff: QueenStaff?, val missing: List<Missing>)

/** Rules a planner and reviewer pair must keep (pure). Neither may do work, and neither may be the queen or the Nanny. */
object PlanningIntegrity {
    private val workAuthorities = setOf(
        RoleAuthority.Implement, RoleAuthority.AuthorTests, RoleAuthority.Verify,
        RoleAuthority.ApproveIntegration, RoleAuthority.ApproveRelease,
    )

    private fun RoleDefinition.writes(): Boolean =
        authorities.any { it in workAuthorities } || AgentCapability.RepositoryWrite in capabilitiesRequired

    /** Why the pair breaks the rules, or empty. */
    fun violations(planner: RoleDefinition, reviewer: RoleDefinition, orchestrator: RoleDefinition): List<String> = buildList {
        if (planner.id == reviewer.id) add("the planner and the reviewer are the same role")
        if (planner.id == orchestrator.id || reviewer.id == orchestrator.id) add("the queen cannot be her own planner or reviewer")
        if (planner.id == HallMonitorRole.id || reviewer.id == HallMonitorRole.id) add("the Nanny cannot be the planner or the reviewer")
        if (planner.writes()) add("the planner can write or release")
        if (reviewer.writes()) add("the reviewer can write or release")
    }
}

/**
 * The queen creates her planner and reviewer from [roles]: the generic Planner when enabled, and the reviewer
 * [planReviewerRole] picks. A keeper's edited copy of either wins, as roles merge by id. When one is missing or
 * the pair breaks [PlanningIntegrity], there is no staff and [QueenStaffing.missing] says what, and how to fix it.
 */
fun staffQueen(roles: List<RoleDefinition>): QueenStaffing {
    val planner = roles.firstOrNull { it.enabled && it.id == BuiltInRoles.Planner.id }
    val reviewer = planReviewerRole(roles)
    val missing = buildList {
        if (planner == null) add(Missing(MissingKind.NoPlanner, BuiltInRoles.Planner.name, MissingFix.EnableRole))
        if (reviewer == null) add(Missing(MissingKind.NoReviewer, BuiltInRoles.Reviewer.name, MissingFix.EnableRole))
    }
    if (planner == null || reviewer == null) return QueenStaffing(null, missing)
    val broken = PlanningIntegrity.violations(planner, reviewer, BuiltInRoles.Orchestrator)
    if (broken.isNotEmpty()) return QueenStaffing(null, broken.map { Missing(MissingKind.PairBroken, it, MissingFix.EditRole) })
    return QueenStaffing(QueenStaff(planner, reviewer), emptyList())
}
