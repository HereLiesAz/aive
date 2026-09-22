package com.hereliesaz.geministrator

import androidx.compose.ui.graphics.Color

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

    fun roleFor(roleLabel: String): NodeArmRole =
        when (classifyNodeCreatureRole(roleLabel)) {
            NodeCreatureRoleKind.Orchestrator -> NodeArmRole.Orchestrator
            NodeCreatureRoleKind.ProductManager -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.Researcher -> NodeArmRole.Researcher
            NodeCreatureRoleKind.Architect -> NodeArmRole.Architect
            NodeCreatureRoleKind.EpaRepresentative -> NodeArmRole.EpaRepresentative
            NodeCreatureRoleKind.UxDesigner -> NodeArmRole.UxDesigner
            NodeCreatureRoleKind.ImplementationEngineer -> NodeArmRole.ImplementationEngineer
            NodeCreatureRoleKind.CrashTestDummy -> NodeArmRole.CrashTestDummy
            NodeCreatureRoleKind.QaEngineer -> NodeArmRole.QaEngineer
            NodeCreatureRoleKind.AdversarialReviewer -> NodeArmRole.AdversarialReviewer
            NodeCreatureRoleKind.CodeReviewer -> NodeArmRole.CodeReviewer
            NodeCreatureRoleKind.RecoveryEngineer -> NodeArmRole.RecoveryEngineer
            NodeCreatureRoleKind.ReleaseEngineer -> NodeArmRole.ReleaseEngineer
            NodeCreatureRoleKind.Antagonist -> NodeArmRole.Antagonist
            NodeCreatureRoleKind.HallMonitor -> NodeArmRole.HallMonitor
            NodeCreatureRoleKind.DataScientist -> NodeArmRole.Researcher
            NodeCreatureRoleKind.DataEngineer -> NodeArmRole.ImplementationEngineer
            NodeCreatureRoleKind.ContentCreator -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.TechnicalAuthor -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.ComplianceOfficer -> NodeArmRole.QaEngineer
            NodeCreatureRoleKind.LegalCounsel -> NodeArmRole.AdversarialReviewer
            NodeCreatureRoleKind.BugReporter -> NodeArmRole.QaEngineer
            NodeCreatureRoleKind.WorldBuilder -> NodeArmRole.Architect
            NodeCreatureRoleKind.Storyteller -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.Pathfinder -> NodeArmRole.Researcher
            NodeCreatureRoleKind.DataMiner -> NodeArmRole.Researcher
            NodeCreatureRoleKind.KnowledgeKeeper -> NodeArmRole.Researcher
            NodeCreatureRoleKind.PatternSeeker -> NodeArmRole.Researcher
            NodeCreatureRoleKind.PatternSeer -> NodeArmRole.Researcher
            NodeCreatureRoleKind.Translator -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.Connector -> NodeArmRole.Orchestrator
            NodeCreatureRoleKind.Scheduler -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.Optimizer -> NodeArmRole.ImplementationEngineer
            NodeCreatureRoleKind.Simulator -> NodeArmRole.CrashTestDummy
            NodeCreatureRoleKind.Documenter -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.Guardian -> NodeArmRole.HallMonitor
            NodeCreatureRoleKind.CreativeSynthesizer -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.ResourceManager -> NodeArmRole.Orchestrator
            NodeCreatureRoleKind.FeedbackListener -> NodeArmRole.Researcher
            NodeCreatureRoleKind.DecisionHelper -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.FlowDirector -> NodeArmRole.Orchestrator
            NodeCreatureRoleKind.Communicator -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.Integrator -> NodeArmRole.ImplementationEngineer
            NodeCreatureRoleKind.SecurityAnalyst -> NodeArmRole.AdversarialReviewer
            NodeCreatureRoleKind.QualityGuardian -> NodeArmRole.QaEngineer
            NodeCreatureRoleKind.DocumentationSpecialist -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.TestExplorer -> NodeArmRole.QaEngineer
            NodeCreatureRoleKind.DeploymentPilot -> NodeArmRole.ReleaseEngineer
            NodeCreatureRoleKind.MonitoringSentinel -> NodeArmRole.HallMonitor
            NodeCreatureRoleKind.FeedbackCollector -> NodeArmRole.Researcher
            NodeCreatureRoleKind.IdeaCatalyst -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.ProcessMapper -> NodeArmRole.Architect
            NodeCreatureRoleKind.StrategyScout -> NodeArmRole.Researcher
            NodeCreatureRoleKind.SystemMaintainer -> NodeArmRole.RecoveryEngineer
            NodeCreatureRoleKind.SustainabilityAdvocate -> NodeArmRole.EpaRepresentative
            NodeCreatureRoleKind.SystemsAnalyst -> NodeArmRole.Researcher
            NodeCreatureRoleKind.DatabaseAdministrator -> NodeArmRole.ImplementationEngineer
            NodeCreatureRoleKind.NetworkEngineer -> NodeArmRole.ImplementationEngineer
            NodeCreatureRoleKind.SiteReliabilityEngineer -> NodeArmRole.ImplementationEngineer
            NodeCreatureRoleKind.BuildEngineer -> NodeArmRole.ImplementationEngineer
            NodeCreatureRoleKind.TestAutomationEngineer -> NodeArmRole.QaEngineer
            NodeCreatureRoleKind.ThreatModeler -> NodeArmRole.AdversarialReviewer
            NodeCreatureRoleKind.MigrationEngineer -> NodeArmRole.ImplementationEngineer
            NodeCreatureRoleKind.ObservabilityEngineer -> NodeArmRole.HallMonitor
            NodeCreatureRoleKind.BusinessAnalyst -> NodeArmRole.Researcher
            NodeCreatureRoleKind.ContentStrategist -> NodeArmRole.ProductManager
            NodeCreatureRoleKind.SupportAgent -> NodeArmRole.RecoveryEngineer
            NodeCreatureRoleKind.MlEngineer -> NodeArmRole.Researcher
            NodeCreatureRoleKind.Generic -> NodeArmRole.Orchestrator
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


internal fun nodeArmColor(role: NodeArmRole): Color = when (role) {
    NodeArmRole.Orchestrator -> Color(0xFFF5B82E)
    NodeArmRole.ProductManager -> Color(0xFFF15F89)
    NodeArmRole.Researcher -> Color(0xFF39A9EA)
    NodeArmRole.Architect -> Color(0xFF8B59E8)
    NodeArmRole.EpaRepresentative -> Color(0xFF38B84A)
    NodeArmRole.UxDesigner -> Color(0xFFF35F8A)
    NodeArmRole.ImplementationEngineer -> Color(0xFFFF7A00)
    NodeArmRole.CrashTestDummy -> Color(0xFFFFC928)
    NodeArmRole.QaEngineer -> Color(0xFF39BFEA)
    NodeArmRole.AdversarialReviewer -> Color(0xFFF12E3D)
    NodeArmRole.CodeReviewer -> Color(0xFF2F70E8)
    NodeArmRole.RecoveryEngineer -> Color(0xFF35B96A)
    NodeArmRole.ReleaseEngineer -> Color(0xFFA464E6)
    NodeArmRole.Antagonist -> Color(0xFF191919)
    NodeArmRole.HallMonitor -> Color(0xFF69A9EF)
}

internal fun nodeArmDarkColor(role: NodeArmRole): Color = when (role) {
    NodeArmRole.Orchestrator -> Color(0xFF16223C)
    NodeArmRole.ProductManager -> Color(0xFF5B2440)
    NodeArmRole.Researcher -> Color(0xFF123A62)
    NodeArmRole.Architect -> Color(0xFF3F246F)
    NodeArmRole.EpaRepresentative -> Color(0xFF174F24)
    NodeArmRole.UxDesigner -> Color(0xFF64243C)
    NodeArmRole.ImplementationEngineer -> Color(0xFF71350B)
    NodeArmRole.CrashTestDummy -> Color(0xFF6D5714)
    NodeArmRole.QaEngineer -> Color(0xFF173654)
    NodeArmRole.AdversarialReviewer -> Color(0xFF721722)
    NodeArmRole.CodeReviewer -> Color(0xFF173D7A)
    NodeArmRole.RecoveryEngineer -> Color(0xFF185B35)
    NodeArmRole.ReleaseEngineer -> Color(0xFF512D78)
    NodeArmRole.Antagonist -> Color(0xFF050505)
    NodeArmRole.HallMonitor -> Color(0xFF214D85)
}
