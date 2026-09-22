package com.hereliesaz.geministrator

/**
 * Character registry for Azphalt workflow/role personas.
 *
 * Every listed Store persona owns a distinct node-creature identity.
 *
 * [motionArchetype] is ONLY an animation vocabulary (how a character tends to move/express effort).
 * It is not a species/family mapping and must never be used to collapse multiple Store roles into
 * one visible character. [phenotypeIndex] identifies the character's own morphology.
 */
internal data class MascotCharacterIdentity(
    val id: String,
    val roleLabel: String,
    val motionArchetype: NodeCreatureRoleKind,
    val phenotypeIndex: Int,
) {
    val designFingerprint: String
        get() = "$id#$phenotypeIndex"
}

internal object MascotCharacterCatalog {
    private val storeCharacters = listOf(
        MascotCharacterIdentity(
            id = "azphalt-ai-evaluation-scientist",
            roleLabel = "AI Evaluation Scientist",
            motionArchetype = NodeCreatureRoleKind.MlEngineer,
            phenotypeIndex = 0,
        ),
        MascotCharacterIdentity(
            id = "azphalt-accessibility-auditor",
            roleLabel = "Accessibility Auditor",
            motionArchetype = NodeCreatureRoleKind.QualityGuardian,
            phenotypeIndex = 1,
        ),
        MascotCharacterIdentity(
            id = "azphalt-agent-red-teamer",
            roleLabel = "Agent Red Teamer",
            motionArchetype = NodeCreatureRoleKind.ThreatModeler,
            phenotypeIndex = 2,
        ),
        MascotCharacterIdentity(
            id = "azphalt-agent-security-architect",
            roleLabel = "Agent Security Architect",
            motionArchetype = NodeCreatureRoleKind.SecurityAnalyst,
            phenotypeIndex = 3,
        ),
        MascotCharacterIdentity(
            id = "azphalt-book-editor",
            roleLabel = "Book Editor",
            motionArchetype = NodeCreatureRoleKind.DocumentationSpecialist,
            phenotypeIndex = 4,
        ),
        MascotCharacterIdentity(
            id = "azphalt-brand-strategist",
            roleLabel = "Brand Strategist",
            motionArchetype = NodeCreatureRoleKind.ContentStrategist,
            phenotypeIndex = 5,
        ),
        MascotCharacterIdentity(
            id = "azphalt-campaign-strategist",
            roleLabel = "Campaign Strategist",
            motionArchetype = NodeCreatureRoleKind.StrategyScout,
            phenotypeIndex = 6,
        ),
        MascotCharacterIdentity(
            id = "azphalt-competitive-analyst",
            roleLabel = "Competitive Analyst",
            motionArchetype = NodeCreatureRoleKind.BusinessAnalyst,
            phenotypeIndex = 7,
        ),
        MascotCharacterIdentity(
            id = "azphalt-content-strategist",
            roleLabel = "Content Strategist",
            motionArchetype = NodeCreatureRoleKind.ContentStrategist,
            phenotypeIndex = 8,
        ),
        MascotCharacterIdentity(
            id = "azphalt-continuity-supervisor",
            roleLabel = "Continuity Supervisor",
            motionArchetype = NodeCreatureRoleKind.Guardian,
            phenotypeIndex = 9,
        ),
        MascotCharacterIdentity(
            id = "azphalt-contract-reviewer",
            roleLabel = "Contract Reviewer",
            motionArchetype = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 10,
        ),
        MascotCharacterIdentity(
            id = "azphalt-corporate-investigator",
            roleLabel = "Corporate Investigator",
            motionArchetype = NodeCreatureRoleKind.DataMiner,
            phenotypeIndex = 11,
        ),
        MascotCharacterIdentity(
            id = "azphalt-crisis-communicator",
            roleLabel = "Crisis Communicator",
            motionArchetype = NodeCreatureRoleKind.Communicator,
            phenotypeIndex = 12,
        ),
        MascotCharacterIdentity(
            id = "azphalt-curriculum-architect",
            roleLabel = "Curriculum Architect",
            motionArchetype = NodeCreatureRoleKind.Architect,
            phenotypeIndex = 13,
        ),
        MascotCharacterIdentity(
            id = "azphalt-data-investigator",
            roleLabel = "Data Investigator",
            motionArchetype = NodeCreatureRoleKind.DataMiner,
            phenotypeIndex = 14,
        ),
        MascotCharacterIdentity(
            id = "azphalt-data-story-editor",
            roleLabel = "Data Story Editor",
            motionArchetype = NodeCreatureRoleKind.Documenter,
            phenotypeIndex = 15,
        ),
        MascotCharacterIdentity(
            id = "azphalt-decision-analyst",
            roleLabel = "Decision Analyst",
            motionArchetype = NodeCreatureRoleKind.DecisionHelper,
            phenotypeIndex = 16,
        ),
        MascotCharacterIdentity(
            id = "azphalt-deep-research-lead",
            roleLabel = "Deep Research Lead",
            motionArchetype = NodeCreatureRoleKind.KnowledgeKeeper,
            phenotypeIndex = 17,
        ),
        MascotCharacterIdentity(
            id = "azphalt-deep-researcher",
            roleLabel = "Deep Researcher",
            motionArchetype = NodeCreatureRoleKind.KnowledgeKeeper,
            phenotypeIndex = 18,
        ),
        MascotCharacterIdentity(
            id = "azphalt-documentary-producer",
            roleLabel = "Documentary Producer",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 19,
        ),
        MascotCharacterIdentity(
            id = "azphalt-event-producer",
            roleLabel = "Event Producer",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 20,
        ),
        MascotCharacterIdentity(
            id = "azphalt-evidence-skeptic",
            roleLabel = "Evidence Skeptic",
            motionArchetype = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 21,
        ),
        MascotCharacterIdentity(
            id = "azphalt-evidence-synthesis-lead",
            roleLabel = "Evidence Synthesis Lead",
            motionArchetype = NodeCreatureRoleKind.CreativeSynthesizer,
            phenotypeIndex = 22,
        ),
        MascotCharacterIdentity(
            id = "azphalt-execution-secretary",
            roleLabel = "Execution Secretary",
            motionArchetype = NodeCreatureRoleKind.Scheduler,
            phenotypeIndex = 23,
        ),
        MascotCharacterIdentity(
            id = "azphalt-exhibition-producer",
            roleLabel = "Exhibition Producer",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 24,
        ),
        MascotCharacterIdentity(
            id = "azphalt-experimental-methodologist",
            roleLabel = "Experimental Methodologist",
            motionArchetype = NodeCreatureRoleKind.PatternSeeker,
            phenotypeIndex = 25,
        ),
        MascotCharacterIdentity(
            id = "azphalt-failure-diagnostician",
            roleLabel = "Failure Diagnostician",
            motionArchetype = NodeCreatureRoleKind.SystemMaintainer,
            phenotypeIndex = 26,
        ),
        MascotCharacterIdentity(
            id = "azphalt-feature-engineer",
            roleLabel = "Feature Engineer",
            motionArchetype = NodeCreatureRoleKind.BuildEngineer,
            phenotypeIndex = 27,
        ),
        MascotCharacterIdentity(
            id = "azphalt-feature-reviewer",
            roleLabel = "Feature Reviewer",
            motionArchetype = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 28,
        ),
        MascotCharacterIdentity(
            id = "azphalt-film-continuity-supervisor",
            roleLabel = "Film Continuity Supervisor",
            motionArchetype = NodeCreatureRoleKind.Guardian,
            phenotypeIndex = 29,
        ),
        MascotCharacterIdentity(
            id = "azphalt-film-showrunner",
            roleLabel = "Film Showrunner",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 30,
        ),
        MascotCharacterIdentity(
            id = "azphalt-financial-model-auditor",
            roleLabel = "Financial Model Auditor",
            motionArchetype = NodeCreatureRoleKind.QualityGuardian,
            phenotypeIndex = 31,
        ),
        MascotCharacterIdentity(
            id = "azphalt-game-director",
            roleLabel = "Game Director",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 32,
        ),
        MascotCharacterIdentity(
            id = "azphalt-geolocation-analyst",
            roleLabel = "Geolocation Analyst",
            motionArchetype = NodeCreatureRoleKind.PatternSeer,
            phenotypeIndex = 33,
        ),
        MascotCharacterIdentity(
            id = "azphalt-grant-strategist",
            roleLabel = "Grant Strategist",
            motionArchetype = NodeCreatureRoleKind.StrategyScout,
            phenotypeIndex = 34,
        ),
        MascotCharacterIdentity(
            id = "azphalt-graphic-novel-continuity-editor",
            roleLabel = "Graphic Novel Continuity Editor",
            motionArchetype = NodeCreatureRoleKind.Guardian,
            phenotypeIndex = 35,
        ),
        MascotCharacterIdentity(
            id = "azphalt-graphic-novel-director",
            roleLabel = "Graphic Novel Director",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 36,
        ),
        MascotCharacterIdentity(
            id = "azphalt-identity-director",
            roleLabel = "Identity Director",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 37,
        ),
        MascotCharacterIdentity(
            id = "azphalt-incident-commander",
            roleLabel = "Incident Commander",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 38,
        ),
        MascotCharacterIdentity(
            id = "azphalt-incident-investigator",
            roleLabel = "Incident Investigator",
            motionArchetype = NodeCreatureRoleKind.DataMiner,
            phenotypeIndex = 39,
        ),
        MascotCharacterIdentity(
            id = "azphalt-interaction-designer",
            roleLabel = "Interaction Designer",
            motionArchetype = NodeCreatureRoleKind.UxDesigner,
            phenotypeIndex = 40,
        ),
        MascotCharacterIdentity(
            id = "azphalt-investigative-reporter",
            roleLabel = "Investigative Reporter",
            motionArchetype = NodeCreatureRoleKind.DataMiner,
            phenotypeIndex = 41,
        ),
        MascotCharacterIdentity(
            id = "azphalt-knowledge-system-evaluator",
            roleLabel = "Knowledge System Evaluator",
            motionArchetype = NodeCreatureRoleKind.KnowledgeKeeper,
            phenotypeIndex = 42,
        ),
        MascotCharacterIdentity(
            id = "azphalt-launch-strategist",
            roleLabel = "Launch Strategist",
            motionArchetype = NodeCreatureRoleKind.DeploymentPilot,
            phenotypeIndex = 43,
        ),
        MascotCharacterIdentity(
            id = "azphalt-learning-coach",
            roleLabel = "Learning Coach",
            motionArchetype = NodeCreatureRoleKind.Communicator,
            phenotypeIndex = 44,
        ),
        MascotCharacterIdentity(
            id = "azphalt-localization-lead",
            roleLabel = "Localization Lead",
            motionArchetype = NodeCreatureRoleKind.Translator,
            phenotypeIndex = 45,
        ),
        MascotCharacterIdentity(
            id = "azphalt-manuscript-continuity-editor",
            roleLabel = "Manuscript Continuity Editor",
            motionArchetype = NodeCreatureRoleKind.Guardian,
            phenotypeIndex = 46,
        ),
        MascotCharacterIdentity(
            id = "azphalt-methods-reviewer",
            roleLabel = "Methods Reviewer",
            motionArchetype = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 47,
        ),
        MascotCharacterIdentity(
            id = "azphalt-negotiation-planner",
            roleLabel = "Negotiation Planner",
            motionArchetype = NodeCreatureRoleKind.Scheduler,
            phenotypeIndex = 48,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-attribution-skeptic",
            roleLabel = "OSINT Attribution Skeptic",
            motionArchetype = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 49,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-change-auditor",
            roleLabel = "OSINT Change Auditor",
            motionArchetype = NodeCreatureRoleKind.MonitoringSentinel,
            phenotypeIndex = 50,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-chronology-auditor",
            roleLabel = "OSINT Chronology Auditor",
            motionArchetype = NodeCreatureRoleKind.QualityGuardian,
            phenotypeIndex = 51,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-corporate-investigator",
            roleLabel = "OSINT Corporate Investigator",
            motionArchetype = NodeCreatureRoleKind.DataMiner,
            phenotypeIndex = 52,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-earth-observation-analyst",
            roleLabel = "OSINT Earth Observation Analyst",
            motionArchetype = NodeCreatureRoleKind.PatternSeer,
            phenotypeIndex = 53,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-entity-resolution-auditor",
            roleLabel = "OSINT Entity Resolution Auditor",
            motionArchetype = NodeCreatureRoleKind.QualityGuardian,
            phenotypeIndex = 54,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-geolocation-analyst",
            roleLabel = "OSINT Geolocation Analyst",
            motionArchetype = NodeCreatureRoleKind.PatternSeer,
            phenotypeIndex = 55,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-infrastructure-analyst",
            roleLabel = "OSINT Infrastructure Analyst",
            motionArchetype = NodeCreatureRoleKind.SystemsAnalyst,
            phenotypeIndex = 56,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-location-skeptic",
            roleLabel = "OSINT Location Skeptic",
            motionArchetype = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 57,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-mobility-analyst",
            roleLabel = "OSINT Mobility Analyst",
            motionArchetype = NodeCreatureRoleKind.PatternSeer,
            phenotypeIndex = 58,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-provenance-analyst",
            roleLabel = "OSINT Provenance Analyst",
            motionArchetype = NodeCreatureRoleKind.DataMiner,
            phenotypeIndex = 59,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-risk-researcher",
            roleLabel = "OSINT Risk Researcher",
            motionArchetype = NodeCreatureRoleKind.ThreatModeler,
            phenotypeIndex = 60,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-screening-auditor",
            roleLabel = "OSINT Screening Auditor",
            motionArchetype = NodeCreatureRoleKind.QualityGuardian,
            phenotypeIndex = 61,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-timeline-analyst",
            roleLabel = "OSINT Timeline Analyst",
            motionArchetype = NodeCreatureRoleKind.PatternSeer,
            phenotypeIndex = 62,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-track-auditor",
            roleLabel = "OSINT Track Auditor",
            motionArchetype = NodeCreatureRoleKind.MonitoringSentinel,
            phenotypeIndex = 63,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-verification-editor",
            roleLabel = "OSINT Verification Editor",
            motionArchetype = NodeCreatureRoleKind.QualityGuardian,
            phenotypeIndex = 64,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-verifier",
            roleLabel = "OSINT Verifier",
            motionArchetype = NodeCreatureRoleKind.QualityGuardian,
            phenotypeIndex = 65,
        ),
        MascotCharacterIdentity(
            id = "azphalt-operations-designer",
            roleLabel = "Operations Designer",
            motionArchetype = NodeCreatureRoleKind.UxDesigner,
            phenotypeIndex = 66,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-geometry-engineer",
            roleLabel = "PaperPlanes Geometry Engineer",
            motionArchetype = NodeCreatureRoleKind.BuildEngineer,
            phenotypeIndex = 67,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-layer-engineer",
            roleLabel = "PaperPlanes Layer Engineer",
            motionArchetype = NodeCreatureRoleKind.BuildEngineer,
            phenotypeIndex = 68,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-occlusion-architect",
            roleLabel = "PaperPlanes Occlusion Architect",
            motionArchetype = NodeCreatureRoleKind.Architect,
            phenotypeIndex = 69,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-scene-director",
            roleLabel = "PaperPlanes Scene Director",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 70,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-visual-qc",
            roleLabel = "PaperPlanes Visual QC",
            motionArchetype = NodeCreatureRoleKind.QualityGuardian,
            phenotypeIndex = 71,
        ),
        MascotCharacterIdentity(
            id = "azphalt-playtest-analyst",
            roleLabel = "Playtest Analyst",
            motionArchetype = NodeCreatureRoleKind.CrashTestDummy,
            phenotypeIndex = 72,
        ),
        MascotCharacterIdentity(
            id = "azphalt-podcast-fact-editor",
            roleLabel = "Podcast Fact Editor",
            motionArchetype = NodeCreatureRoleKind.DataMiner,
            phenotypeIndex = 73,
        ),
        MascotCharacterIdentity(
            id = "azphalt-podcast-producer",
            roleLabel = "Podcast Producer",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 74,
        ),
        MascotCharacterIdentity(
            id = "azphalt-presentation-editor",
            roleLabel = "Presentation Editor",
            motionArchetype = NodeCreatureRoleKind.Documenter,
            phenotypeIndex = 75,
        ),
        MascotCharacterIdentity(
            id = "azphalt-pricing-researcher",
            roleLabel = "Pricing Researcher",
            motionArchetype = NodeCreatureRoleKind.BusinessAnalyst,
            phenotypeIndex = 76,
        ),
        MascotCharacterIdentity(
            id = "azphalt-privacy-risk-analyst",
            roleLabel = "Privacy Risk Analyst",
            motionArchetype = NodeCreatureRoleKind.SecurityAnalyst,
            phenotypeIndex = 77,
        ),
        MascotCharacterIdentity(
            id = "azphalt-process-engineer",
            roleLabel = "Process Engineer",
            motionArchetype = NodeCreatureRoleKind.ProcessMapper,
            phenotypeIndex = 78,
        ),
        MascotCharacterIdentity(
            id = "azphalt-proposal-lead",
            roleLabel = "Proposal Lead",
            motionArchetype = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 79,
        ),
        MascotCharacterIdentity(
            id = "azphalt-qualitative-synthesist",
            roleLabel = "Qualitative Synthesist",
            motionArchetype = NodeCreatureRoleKind.CreativeSynthesizer,
            phenotypeIndex = 80,
        ),
        MascotCharacterIdentity(
            id = "azphalt-release-producer",
            roleLabel = "Release Producer",
            motionArchetype = NodeCreatureRoleKind.ReleaseEngineer,
            phenotypeIndex = 81,
        ),
        MascotCharacterIdentity(
            id = "azphalt-repair-verifier",
            roleLabel = "Repair Verifier",
            motionArchetype = NodeCreatureRoleKind.SystemMaintainer,
            phenotypeIndex = 82,
        ),
        MascotCharacterIdentity(
            id = "azphalt-review-scientist",
            roleLabel = "Review Scientist",
            motionArchetype = NodeCreatureRoleKind.PatternSeeker,
            phenotypeIndex = 83,
        ),
        MascotCharacterIdentity(
            id = "azphalt-scenario-planner",
            roleLabel = "Scenario Planner",
            motionArchetype = NodeCreatureRoleKind.Simulator,
            phenotypeIndex = 84,
        ),
        MascotCharacterIdentity(
            id = "azphalt-search-strategist",
            roleLabel = "Search Strategist",
            motionArchetype = NodeCreatureRoleKind.StrategyScout,
            phenotypeIndex = 85,
        ),
        MascotCharacterIdentity(
            id = "azphalt-selection-designer",
            roleLabel = "Selection Designer",
            motionArchetype = NodeCreatureRoleKind.UxDesigner,
            phenotypeIndex = 86,
        ),
        MascotCharacterIdentity(
            id = "azphalt-statistical-skeptic",
            roleLabel = "Statistical Skeptic",
            motionArchetype = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 87,
        ),
        MascotCharacterIdentity(
            id = "azphalt-supplier-analyst",
            roleLabel = "Supplier Analyst",
            motionArchetype = NodeCreatureRoleKind.BusinessAnalyst,
            phenotypeIndex = 88,
        ),
        MascotCharacterIdentity(
            id = "azphalt-support-escalation-lead",
            roleLabel = "Support Escalation Lead",
            motionArchetype = NodeCreatureRoleKind.SupportAgent,
            phenotypeIndex = 89,
        ),
        MascotCharacterIdentity(
            id = "azphalt-survey-methodologist",
            roleLabel = "Survey Methodologist",
            motionArchetype = NodeCreatureRoleKind.PatternSeeker,
            phenotypeIndex = 90,
        ),
        MascotCharacterIdentity(
            id = "azphalt-technical-writer",
            roleLabel = "Technical Writer",
            motionArchetype = NodeCreatureRoleKind.DocumentationSpecialist,
            phenotypeIndex = 91,
        ),
        MascotCharacterIdentity(
            id = "azphalt-ux-researcher",
            roleLabel = "UX Researcher",
            motionArchetype = NodeCreatureRoleKind.UxDesigner,
            phenotypeIndex = 92,
        ),
        MascotCharacterIdentity(
            id = "azphalt-vendor-evaluator",
            roleLabel = "Vendor Evaluator",
            motionArchetype = NodeCreatureRoleKind.QualityGuardian,
            phenotypeIndex = 93,
        ),
    )

    private val byRole = storeCharacters.associateBy { normalize(it.roleLabel) }

    val explicitCharacters: List<MascotCharacterIdentity>
        get() = storeCharacters

    fun explicitForRole(roleLabel: String): MascotCharacterIdentity? = byRole[normalize(roleLabel)]

    /**
     * Custom/user-created roles also get a stable, distinct phenotype instead of falling back to one
     * anonymous "generic" creature.
     */
    fun forRole(roleLabel: String): MascotCharacterIdentity? {
        explicitForRole(roleLabel)?.let { return it }
        val normalized = normalize(roleLabel)
        if (normalized.isBlank()) return null
        val builtIn = classifyNodeCreatureRole(roleLabel)
        if (builtIn != NodeCreatureRoleKind.Generic) return null
        val hash = stableHash(normalized)
        return MascotCharacterIdentity(
            id = "custom-" + normalized.replace(Regex("[^a-z0-9]+"), "-").trim('-'),
            roleLabel = roleLabel,
            motionArchetype = NodeCreatureRoleKind.Generic,
            phenotypeIndex = hash and Int.MAX_VALUE,
        )
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    private fun stableHash(value: String): Int {
        var hash = 0x45D9F3B
        value.forEach { char -> hash = (hash * 31) xor char.code }
        return hash
    }
}
