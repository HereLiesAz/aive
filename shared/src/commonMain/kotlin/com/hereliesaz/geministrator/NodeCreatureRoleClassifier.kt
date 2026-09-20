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

internal fun classifyNodeCreatureRole(roleLabel: String): NodeCreatureRoleKind {
    val role = roleLabel.trim().lowercase()
    return when {
        "hall monitor" in role ||
            "hall-monitor" in role ||
            "systems monitor" in role -> NodeCreatureRoleKind.HallMonitor

        "antagonist" in role -> NodeCreatureRoleKind.Antagonist
        "adversarial" in role -> NodeCreatureRoleKind.AdversarialReviewer
        "recovery" in role ||
            "repair engineer" in role -> NodeCreatureRoleKind.RecoveryEngineer

        "release" in role ||
            "publisher" in role -> NodeCreatureRoleKind.ReleaseEngineer

        "code review" in role ||
            "code-review" in role -> NodeCreatureRoleKind.CodeReviewer

        "crash" in role ||
            "dummy" in role ||
            "stress test" in role -> NodeCreatureRoleKind.CrashTestDummy

        role == "qa" ||
            "qa engineer" in role ||
            "quality" in role ||
            "verification engineer" in role ||
            "inspector" in role -> NodeCreatureRoleKind.QaEngineer

        "implementation" in role ||
            "implementer" in role ||
            "builder" in role ||
            "developer" in role ||
            "coder" in role -> NodeCreatureRoleKind.ImplementationEngineer

        "ux" in role ||
            "user experience" in role ||
            "experience designer" in role -> NodeCreatureRoleKind.UxDesigner

        "epa" in role ||
            "environment representative" in role ||
            "environment planner" in role -> NodeCreatureRoleKind.EpaRepresentative

        "architect" in role ||
            "systems design" in role -> NodeCreatureRoleKind.Architect

        "research" in role ||
            "analyst" in role ||
            "investigat" in role -> NodeCreatureRoleKind.Researcher

        "product manager" in role ||
            "requirements" in role ||
            "task planner" in role ||
            role == "planner" -> NodeCreatureRoleKind.ProductManager

        "orchestrat" in role ||
            "coordinat" in role ||
            "queen" in role ||
            "swarm lead" in role -> NodeCreatureRoleKind.Orchestrator

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
