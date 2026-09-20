package com.hereliesaz.geministrator

internal enum class NodeArmVariant {
    A,
    B,
}

internal data class NodeArmAsset(
    val role: NodeArmRole,
    val variant: NodeArmVariant,
    /** Socket center in the 200x100 source-art coordinate system. */
    val socketPivotX: Float = 0.10f,
    val socketPivotY: Float = 0.50f,
    /** Terminal center at x=188 in the 200-wide source artwork. */
    val terminalPivotX: Float = 0.94f,
)

internal enum class NodeArmRole {
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
}

internal object NodeArmAssets {
    fun forRole(roleLabel: String, variant: NodeArmVariant): NodeArmAsset =
        NodeArmAsset(
            role = roleFor(roleLabel),
            variant = variant,
        )

    fun roleFor(roleLabel: String): NodeArmRole {
        val role = roleLabel.trim().lowercase()
        return when {
            "hall monitor" in role || "hall-monitor" in role -> NodeArmRole.HallMonitor
            "antagonist" in role -> NodeArmRole.Antagonist
            "adversarial" in role -> NodeArmRole.AdversarialReviewer
            "recovery" in role -> NodeArmRole.RecoveryEngineer
            "release" in role -> NodeArmRole.ReleaseEngineer
            "code review" in role || "code-review" in role -> NodeArmRole.CodeReviewer
            "crash" in role || "dummy" in role -> NodeArmRole.CrashTestDummy
            role == "qa" || "qa engineer" in role || "quality" in role -> NodeArmRole.QaEngineer
            "implementation" in role || "implementer" in role -> NodeArmRole.ImplementationEngineer
            "ux" in role || "user experience" in role -> NodeArmRole.UxDesigner
            "epa" in role || "environment" in role -> NodeArmRole.EpaRepresentative
            "architect" in role -> NodeArmRole.Architect
            "research" in role || "investigat" in role -> NodeArmRole.Researcher
            "product" in role || "requirements" in role -> NodeArmRole.ProductManager
            "orchestrat" in role || "coordinat" in role -> NodeArmRole.Orchestrator
            "review" in role -> NodeArmRole.CodeReviewer
            "engineer" in role || "build" in role || "develop" in role -> NodeArmRole.ImplementationEngineer
            else -> NodeArmRole.Orchestrator
        }
    }

    fun variantFor(connectionKey: String, endpointIndex: Int): NodeArmVariant {
        var hash = 17
        connectionKey.forEach { character ->
            hash = hash * 31 + character.code
        }
        val parity = (hash xor endpointIndex) and 1
        return if (parity == 0) NodeArmVariant.A else NodeArmVariant.B
    }
}
