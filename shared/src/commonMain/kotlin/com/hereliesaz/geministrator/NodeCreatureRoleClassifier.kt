package com.hereliesaz.geministrator

internal enum class NodeCreatureRoleKind {
    Orchestrator,
    ProductManager,
    Researcher,
    Architect,
    EpaRepresentative,
    UxDesigner,
    ImplementationEngineer,
    CrashTestDummy,
    QaEngineer,
    AdversarialReviewer,
    CodeReviewer,
    RecoveryEngineer,
    ReleaseEngineer,
    Antagonist,
    HallMonitor,
    Generic,
}

/**
 * Maps an arbitrary installed role name onto the mascot family.
 *
 * Azphalt Store roles are intentionally classified by semantic function rather than package ID so
 * newly installed workflow/role packages immediately receive a character without requiring a new
 * app release for every package. More-specific identities must stay above broad occupational words.
 */
internal fun classifyNodeCreatureRole(roleLabel: String): NodeCreatureRoleKind {
    val role = roleLabel.trim().lowercase()

    fun hasWord(word: String): Boolean =
        Regex("(^|[^a-z0-9])" + Regex.escape(word) + "([^a-z0-9]|$)").containsMatchIn(role)

    return when {
        // Watchers and continuity keepers.
        "hall monitor" in role ||
            "hall-monitor" in role ||
            "systems monitor" in role ||
            "continuity" in role ||
            "supervisor" in role -> NodeCreatureRoleKind.HallMonitor

        "antagonist" in role -> NodeCreatureRoleKind.Antagonist

        // A repair role is recovery even when its title also says verifier.
        "recovery" in role ||
            "repair" in role ||
            "diagnostician" in role ||
            "support escalation" in role ||
            "restoration" in role -> NodeCreatureRoleKind.RecoveryEngineer

        // Release/localization roles own the path from finished work to shipped work.
        "release" in role ||
            "publisher" in role ||
            "localization" in role -> NodeCreatureRoleKind.ReleaseEngineer

        // Keep code review distinct from general review/challenge roles.
        "code review" in role ||
            "code-review" in role -> NodeCreatureRoleKind.CodeReviewer

        // Explicit breakage/playtesting gets the elastic test character.
        "crash" in role ||
            "dummy" in role ||
            "stress test" in role ||
            "playtest" in role -> NodeCreatureRoleKind.CrashTestDummy

        // Skeptics, reviewers and red-team roles challenge another node's result.
        "adversarial" in role ||
            "skeptic" in role ||
            "red team" in role ||
            "reviewer" in role -> NodeCreatureRoleKind.AdversarialReviewer

        // Verification/audit/evaluation roles use the inspection character family.
        role == "qa" ||
            "qa engineer" in role ||
            "quality" in role ||
            "verification" in role ||
            "verifier" in role ||
            "auditor" in role ||
            "evaluator" in role ||
            "visual qc" in role ||
            "inspector" in role -> NodeCreatureRoleKind.QaEngineer

        // Environment planning is deliberately word-bounded: "repair" must never match "epa".
        hasWord("epa") ||
            "environment representative" in role ||
            "environment planner" in role ||
            "environmental" in role -> NodeCreatureRoleKind.EpaRepresentative

        "architect" in role ||
            "systems design" in role -> NodeCreatureRoleKind.Architect

        // Engineering/building roles are the construction character family.
        "implementation" in role ||
            "implementer" in role ||
            "builder" in role ||
            "developer" in role ||
            "coder" in role ||
            hasWord("engineer") -> NodeCreatureRoleKind.ImplementationEngineer

        // Human-centered and interaction design roles.
        "ux" in role ||
            "user experience" in role ||
            "experience designer" in role ||
            "interaction designer" in role ||
            "operations designer" in role ||
            "selection designer" in role -> NodeCreatureRoleKind.UxDesigner

        // Evidence-seeking and scientific roles.
        "research" in role ||
            "analyst" in role ||
            "investigat" in role ||
            "scientist" in role ||
            "methodologist" in role ||
            "synthesi" in role ||
            "reporter" in role ||
            "fact editor" in role -> NodeCreatureRoleKind.Researcher

        // Planning/editorial roles clarify and shape the requested result.
        "product manager" in role ||
            "requirements" in role ||
            "task planner" in role ||
            "planner" in role ||
            "strategist" in role ||
            "editor" in role ||
            "writer" in role ||
            "coach" in role -> NodeCreatureRoleKind.ProductManager

        // Leads/directors/producers coordinate other contributors.
        "orchestrat" in role ||
            "coordinat" in role ||
            "queen" in role ||
            "swarm lead" in role ||
            hasWord("lead") ||
            "commander" in role ||
            "director" in role ||
            "producer" in role ||
            "showrunner" in role ||
            "secretary" in role ||
            "communicator" in role -> NodeCreatureRoleKind.Orchestrator

        else -> NodeCreatureRoleKind.Generic
    }
}

internal fun NodeCreatureRoleKind.activeLabel(): String = when (this) {
    NodeCreatureRoleKind.Orchestrator -> "ROUTING"
    NodeCreatureRoleKind.ProductManager -> "CLARIFYING"
    NodeCreatureRoleKind.Researcher -> "RESEARCHING"
    NodeCreatureRoleKind.Architect -> "STRUCTURING"
    NodeCreatureRoleKind.EpaRepresentative -> "PROVISIONING"
    NodeCreatureRoleKind.UxDesigner -> "DESIGNING"
    NodeCreatureRoleKind.ImplementationEngineer -> "BUILDING"
    NodeCreatureRoleKind.CrashTestDummy -> "STRESS-TESTING"
    NodeCreatureRoleKind.QaEngineer -> "VERIFYING"
    NodeCreatureRoleKind.AdversarialReviewer -> "CHALLENGING"
    NodeCreatureRoleKind.CodeReviewer -> "REVIEWING CODE"
    NodeCreatureRoleKind.RecoveryEngineer -> "RECOVERING"
    NodeCreatureRoleKind.ReleaseEngineer -> "RELEASING"
    NodeCreatureRoleKind.Antagonist -> "FINDING FLAWS"
    NodeCreatureRoleKind.HallMonitor -> "MONITORING"
    NodeCreatureRoleKind.Generic -> "WORKING"
}
