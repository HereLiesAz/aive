package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.resources.*
import org.jetbrains.compose.resources.DrawableResource

internal enum class NodeArmVariant {
    A,
    B,
}

internal data class NodeArmAsset(
    val resource: DrawableResource,
    val socketPivotX: Float = 0.10f,
    val socketPivotY: Float = 0.50f,
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
    fun forRole(roleLabel: String, variant: NodeArmVariant): NodeArmAsset {
        val resource = when (roleFor(roleLabel) to variant) {
            NodeArmRole.Orchestrator to NodeArmVariant.A -> Res.drawable.node_arm_orchestrator_a
            NodeArmRole.Orchestrator to NodeArmVariant.B -> Res.drawable.node_arm_orchestrator_b
            NodeArmRole.ProductManager to NodeArmVariant.A -> Res.drawable.node_arm_product_manager_a
            NodeArmRole.ProductManager to NodeArmVariant.B -> Res.drawable.node_arm_product_manager_b
            NodeArmRole.Researcher to NodeArmVariant.A -> Res.drawable.node_arm_researcher_a
            NodeArmRole.Researcher to NodeArmVariant.B -> Res.drawable.node_arm_researcher_b
            NodeArmRole.Architect to NodeArmVariant.A -> Res.drawable.node_arm_architect_a
            NodeArmRole.Architect to NodeArmVariant.B -> Res.drawable.node_arm_architect_b
            NodeArmRole.EpaRepresentative to NodeArmVariant.A -> Res.drawable.node_arm_epa_representative_a
            NodeArmRole.EpaRepresentative to NodeArmVariant.B -> Res.drawable.node_arm_epa_representative_b
            NodeArmRole.UxDesigner to NodeArmVariant.A -> Res.drawable.node_arm_ux_designer_a
            NodeArmRole.UxDesigner to NodeArmVariant.B -> Res.drawable.node_arm_ux_designer_b
            NodeArmRole.ImplementationEngineer to NodeArmVariant.A -> Res.drawable.node_arm_implementation_engineer_a
            NodeArmRole.ImplementationEngineer to NodeArmVariant.B -> Res.drawable.node_arm_implementation_engineer_b
            NodeArmRole.CrashTestDummy to NodeArmVariant.A -> Res.drawable.node_arm_crash_test_dummy_a
            NodeArmRole.CrashTestDummy to NodeArmVariant.B -> Res.drawable.node_arm_crash_test_dummy_b
            NodeArmRole.QaEngineer to NodeArmVariant.A -> Res.drawable.node_arm_qa_engineer_a
            NodeArmRole.QaEngineer to NodeArmVariant.B -> Res.drawable.node_arm_qa_engineer_b
            NodeArmRole.AdversarialReviewer to NodeArmVariant.A -> Res.drawable.node_arm_adversarial_reviewer_a
            NodeArmRole.AdversarialReviewer to NodeArmVariant.B -> Res.drawable.node_arm_adversarial_reviewer_b
            NodeArmRole.CodeReviewer to NodeArmVariant.A -> Res.drawable.node_arm_code_reviewer_a
            NodeArmRole.CodeReviewer to NodeArmVariant.B -> Res.drawable.node_arm_code_reviewer_b
            NodeArmRole.RecoveryEngineer to NodeArmVariant.A -> Res.drawable.node_arm_recovery_engineer_a
            NodeArmRole.RecoveryEngineer to NodeArmVariant.B -> Res.drawable.node_arm_recovery_engineer_b
            NodeArmRole.ReleaseEngineer to NodeArmVariant.A -> Res.drawable.node_arm_release_engineer_a
            NodeArmRole.ReleaseEngineer to NodeArmVariant.B -> Res.drawable.node_arm_release_engineer_b
            NodeArmRole.Antagonist to NodeArmVariant.A -> Res.drawable.node_arm_antagonist_a
            NodeArmRole.Antagonist to NodeArmVariant.B -> Res.drawable.node_arm_antagonist_b
            NodeArmRole.HallMonitor to NodeArmVariant.A -> Res.drawable.node_arm_hall_monitor_a
            NodeArmRole.HallMonitor to NodeArmVariant.B -> Res.drawable.node_arm_hall_monitor_b
        }
        return NodeArmAsset(resource)
    }

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
