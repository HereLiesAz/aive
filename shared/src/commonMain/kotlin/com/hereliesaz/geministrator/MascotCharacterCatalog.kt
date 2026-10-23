package com.hereliesaz.geministrator

/**
 * Registry of the Azphalt Store workflow/role personas.
 *
 * A persona is an identity (stable [id] and [roleLabel]); it is drawn with one of the creature
 * designs in `files/rigs/`. [rigKind] names the closest design by role: it is the explicit
 * persona -> creature table that [CreatureRigMapping.slugFor] resolves through [bySlugKind]
 * (`CreatureRigs.kt`), so "Brand Strategist" and "Content Strategist" both wear the content
 * strategist creature while "Film Showrunner" wears the orchestrator.
 */
internal data class MascotCharacterIdentity(
    val id: String,
    val roleLabel: String,
    val rigKind: NodeCreatureRoleKind,
)

internal object MascotCharacterCatalog {
    private val storeCharacters = listOf(
        MascotCharacterIdentity(
            id = "azphalt-ai-evaluation-scientist",
            roleLabel = "AI Evaluation Scientist",
            rigKind = NodeCreatureRoleKind.MlEngineer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-accessibility-auditor",
            roleLabel = "Accessibility Auditor",
            rigKind = NodeCreatureRoleKind.QualityGuardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-agent-red-teamer",
            roleLabel = "Agent Red Teamer",
            rigKind = NodeCreatureRoleKind.ThreatModeler,
        ),
        MascotCharacterIdentity(
            id = "azphalt-agent-security-architect",
            roleLabel = "Agent Security Architect",
            rigKind = NodeCreatureRoleKind.SecurityAnalyst,
        ),
        MascotCharacterIdentity(
            id = "azphalt-book-editor",
            roleLabel = "Book Editor",
            rigKind = NodeCreatureRoleKind.DocumentationSpecialist,
        ),
        MascotCharacterIdentity(
            id = "azphalt-brand-strategist",
            roleLabel = "Brand Strategist",
            rigKind = NodeCreatureRoleKind.ContentStrategist,
        ),
        MascotCharacterIdentity(
            id = "azphalt-campaign-strategist",
            roleLabel = "Campaign Strategist",
            rigKind = NodeCreatureRoleKind.StrategyScout,
        ),
        MascotCharacterIdentity(
            id = "azphalt-competitive-analyst",
            roleLabel = "Competitive Analyst",
            rigKind = NodeCreatureRoleKind.BusinessAnalyst,
        ),
        MascotCharacterIdentity(
            id = "azphalt-content-strategist",
            roleLabel = "Content Strategist",
            rigKind = NodeCreatureRoleKind.ContentStrategist,
        ),
        MascotCharacterIdentity(
            id = "azphalt-continuity-supervisor",
            roleLabel = "Continuity Supervisor",
            rigKind = NodeCreatureRoleKind.Guardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-contract-reviewer",
            roleLabel = "Contract Reviewer",
            rigKind = NodeCreatureRoleKind.AdversarialReviewer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-corporate-investigator",
            roleLabel = "Corporate Investigator",
            rigKind = NodeCreatureRoleKind.DataMiner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-crisis-communicator",
            roleLabel = "Crisis Communicator",
            rigKind = NodeCreatureRoleKind.Communicator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-curriculum-architect",
            roleLabel = "Curriculum Architect",
            rigKind = NodeCreatureRoleKind.Architect,
        ),
        MascotCharacterIdentity(
            id = "azphalt-data-investigator",
            roleLabel = "Data Investigator",
            rigKind = NodeCreatureRoleKind.DataMiner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-data-story-editor",
            roleLabel = "Data Story Editor",
            rigKind = NodeCreatureRoleKind.Documenter,
        ),
        MascotCharacterIdentity(
            id = "azphalt-decision-analyst",
            roleLabel = "Decision Analyst",
            rigKind = NodeCreatureRoleKind.DecisionHelper,
        ),
        MascotCharacterIdentity(
            id = "azphalt-deep-research-lead",
            roleLabel = "Deep Research Lead",
            rigKind = NodeCreatureRoleKind.KnowledgeKeeper,
        ),
        MascotCharacterIdentity(
            id = "azphalt-deep-researcher",
            roleLabel = "Deep Researcher",
            rigKind = NodeCreatureRoleKind.KnowledgeKeeper,
        ),
        MascotCharacterIdentity(
            id = "azphalt-documentary-producer",
            roleLabel = "Documentary Producer",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-event-producer",
            roleLabel = "Event Producer",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-evidence-skeptic",
            roleLabel = "Evidence Skeptic",
            rigKind = NodeCreatureRoleKind.AdversarialReviewer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-evidence-synthesis-lead",
            roleLabel = "Evidence Synthesis Lead",
            rigKind = NodeCreatureRoleKind.CreativeSynthesizer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-execution-secretary",
            roleLabel = "Execution Secretary",
            rigKind = NodeCreatureRoleKind.Scheduler,
        ),
        MascotCharacterIdentity(
            id = "azphalt-exhibition-producer",
            roleLabel = "Exhibition Producer",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-experimental-methodologist",
            roleLabel = "Experimental Methodologist",
            rigKind = NodeCreatureRoleKind.PatternSeeker,
        ),
        MascotCharacterIdentity(
            id = "azphalt-failure-diagnostician",
            roleLabel = "Failure Diagnostician",
            rigKind = NodeCreatureRoleKind.SystemMaintainer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-feature-engineer",
            roleLabel = "Feature Engineer",
            rigKind = NodeCreatureRoleKind.BuildEngineer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-feature-reviewer",
            roleLabel = "Feature Reviewer",
            rigKind = NodeCreatureRoleKind.AdversarialReviewer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-film-continuity-supervisor",
            roleLabel = "Film Continuity Supervisor",
            rigKind = NodeCreatureRoleKind.Guardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-film-showrunner",
            roleLabel = "Film Showrunner",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-financial-model-auditor",
            roleLabel = "Financial Model Auditor",
            rigKind = NodeCreatureRoleKind.QualityGuardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-game-director",
            roleLabel = "Game Director",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-geolocation-analyst",
            roleLabel = "Geolocation Analyst",
            rigKind = NodeCreatureRoleKind.PatternSeer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-grant-strategist",
            roleLabel = "Grant Strategist",
            rigKind = NodeCreatureRoleKind.StrategyScout,
        ),
        MascotCharacterIdentity(
            id = "azphalt-graphic-novel-continuity-editor",
            roleLabel = "Graphic Novel Continuity Editor",
            rigKind = NodeCreatureRoleKind.Guardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-graphic-novel-director",
            roleLabel = "Graphic Novel Director",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-identity-director",
            roleLabel = "Identity Director",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-incident-commander",
            roleLabel = "Incident Commander",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-incident-investigator",
            roleLabel = "Incident Investigator",
            rigKind = NodeCreatureRoleKind.DataMiner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-interaction-designer",
            roleLabel = "Interaction Designer",
            rigKind = NodeCreatureRoleKind.UxDesigner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-investigative-reporter",
            roleLabel = "Investigative Reporter",
            rigKind = NodeCreatureRoleKind.DataMiner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-knowledge-system-evaluator",
            roleLabel = "Knowledge System Evaluator",
            rigKind = NodeCreatureRoleKind.KnowledgeKeeper,
        ),
        MascotCharacterIdentity(
            id = "azphalt-launch-strategist",
            roleLabel = "Launch Strategist",
            rigKind = NodeCreatureRoleKind.DeploymentPilot,
        ),
        MascotCharacterIdentity(
            id = "azphalt-learning-coach",
            roleLabel = "Learning Coach",
            rigKind = NodeCreatureRoleKind.Communicator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-localization-lead",
            roleLabel = "Localization Lead",
            rigKind = NodeCreatureRoleKind.Translator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-manuscript-continuity-editor",
            roleLabel = "Manuscript Continuity Editor",
            rigKind = NodeCreatureRoleKind.Guardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-methods-reviewer",
            roleLabel = "Methods Reviewer",
            rigKind = NodeCreatureRoleKind.AdversarialReviewer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-negotiation-planner",
            roleLabel = "Negotiation Planner",
            rigKind = NodeCreatureRoleKind.Scheduler,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-attribution-skeptic",
            roleLabel = "OSINT Attribution Skeptic",
            rigKind = NodeCreatureRoleKind.AdversarialReviewer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-change-auditor",
            roleLabel = "OSINT Change Auditor",
            rigKind = NodeCreatureRoleKind.MonitoringSentinel,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-chronology-auditor",
            roleLabel = "OSINT Chronology Auditor",
            rigKind = NodeCreatureRoleKind.QualityGuardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-corporate-investigator",
            roleLabel = "OSINT Corporate Investigator",
            rigKind = NodeCreatureRoleKind.DataMiner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-earth-observation-analyst",
            roleLabel = "OSINT Earth Observation Analyst",
            rigKind = NodeCreatureRoleKind.PatternSeer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-entity-resolution-auditor",
            roleLabel = "OSINT Entity Resolution Auditor",
            rigKind = NodeCreatureRoleKind.QualityGuardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-geolocation-analyst",
            roleLabel = "OSINT Geolocation Analyst",
            rigKind = NodeCreatureRoleKind.PatternSeer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-infrastructure-analyst",
            roleLabel = "OSINT Infrastructure Analyst",
            rigKind = NodeCreatureRoleKind.SystemsAnalyst,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-location-skeptic",
            roleLabel = "OSINT Location Skeptic",
            rigKind = NodeCreatureRoleKind.AdversarialReviewer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-mobility-analyst",
            roleLabel = "OSINT Mobility Analyst",
            rigKind = NodeCreatureRoleKind.PatternSeer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-provenance-analyst",
            roleLabel = "OSINT Provenance Analyst",
            rigKind = NodeCreatureRoleKind.DataMiner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-risk-researcher",
            roleLabel = "OSINT Risk Researcher",
            rigKind = NodeCreatureRoleKind.ThreatModeler,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-screening-auditor",
            roleLabel = "OSINT Screening Auditor",
            rigKind = NodeCreatureRoleKind.QualityGuardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-timeline-analyst",
            roleLabel = "OSINT Timeline Analyst",
            rigKind = NodeCreatureRoleKind.PatternSeer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-track-auditor",
            roleLabel = "OSINT Track Auditor",
            rigKind = NodeCreatureRoleKind.MonitoringSentinel,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-verification-editor",
            roleLabel = "OSINT Verification Editor",
            rigKind = NodeCreatureRoleKind.QualityGuardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-verifier",
            roleLabel = "OSINT Verifier",
            rigKind = NodeCreatureRoleKind.QualityGuardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-operations-designer",
            roleLabel = "Operations Designer",
            rigKind = NodeCreatureRoleKind.UxDesigner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-geometry-engineer",
            roleLabel = "PaperPlanes Geometry Engineer",
            rigKind = NodeCreatureRoleKind.BuildEngineer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-layer-engineer",
            roleLabel = "PaperPlanes Layer Engineer",
            rigKind = NodeCreatureRoleKind.BuildEngineer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-occlusion-architect",
            roleLabel = "PaperPlanes Occlusion Architect",
            rigKind = NodeCreatureRoleKind.Architect,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-scene-director",
            roleLabel = "PaperPlanes Scene Director",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-visual-qc",
            roleLabel = "PaperPlanes Visual QC",
            rigKind = NodeCreatureRoleKind.QualityGuardian,
        ),
        MascotCharacterIdentity(
            id = "azphalt-playtest-analyst",
            roleLabel = "Playtest Analyst",
            rigKind = NodeCreatureRoleKind.CrashTestDummy,
        ),
        MascotCharacterIdentity(
            id = "azphalt-podcast-fact-editor",
            roleLabel = "Podcast Fact Editor",
            rigKind = NodeCreatureRoleKind.DataMiner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-podcast-producer",
            roleLabel = "Podcast Producer",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-presentation-editor",
            roleLabel = "Presentation Editor",
            rigKind = NodeCreatureRoleKind.Documenter,
        ),
        MascotCharacterIdentity(
            id = "azphalt-pricing-researcher",
            roleLabel = "Pricing Researcher",
            rigKind = NodeCreatureRoleKind.BusinessAnalyst,
        ),
        MascotCharacterIdentity(
            id = "azphalt-privacy-risk-analyst",
            roleLabel = "Privacy Risk Analyst",
            rigKind = NodeCreatureRoleKind.SecurityAnalyst,
        ),
        MascotCharacterIdentity(
            id = "azphalt-process-engineer",
            roleLabel = "Process Engineer",
            rigKind = NodeCreatureRoleKind.ProcessMapper,
        ),
        MascotCharacterIdentity(
            id = "azphalt-proposal-lead",
            roleLabel = "Proposal Lead",
            rigKind = NodeCreatureRoleKind.Orchestrator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-qualitative-synthesist",
            roleLabel = "Qualitative Synthesist",
            rigKind = NodeCreatureRoleKind.CreativeSynthesizer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-release-producer",
            roleLabel = "Release Producer",
            rigKind = NodeCreatureRoleKind.ReleaseEngineer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-repair-verifier",
            roleLabel = "Repair Verifier",
            rigKind = NodeCreatureRoleKind.SystemMaintainer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-review-scientist",
            roleLabel = "Review Scientist",
            rigKind = NodeCreatureRoleKind.PatternSeeker,
        ),
        MascotCharacterIdentity(
            id = "azphalt-scenario-planner",
            roleLabel = "Scenario Planner",
            rigKind = NodeCreatureRoleKind.Simulator,
        ),
        MascotCharacterIdentity(
            id = "azphalt-search-strategist",
            roleLabel = "Search Strategist",
            rigKind = NodeCreatureRoleKind.StrategyScout,
        ),
        MascotCharacterIdentity(
            id = "azphalt-selection-designer",
            roleLabel = "Selection Designer",
            rigKind = NodeCreatureRoleKind.UxDesigner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-statistical-skeptic",
            roleLabel = "Statistical Skeptic",
            rigKind = NodeCreatureRoleKind.AdversarialReviewer,
        ),
        MascotCharacterIdentity(
            id = "azphalt-supplier-analyst",
            roleLabel = "Supplier Analyst",
            rigKind = NodeCreatureRoleKind.BusinessAnalyst,
        ),
        MascotCharacterIdentity(
            id = "azphalt-support-escalation-lead",
            roleLabel = "Support Escalation Lead",
            rigKind = NodeCreatureRoleKind.SupportAgent,
        ),
        MascotCharacterIdentity(
            id = "azphalt-survey-methodologist",
            roleLabel = "Survey Methodologist",
            rigKind = NodeCreatureRoleKind.PatternSeeker,
        ),
        MascotCharacterIdentity(
            id = "azphalt-technical-writer",
            roleLabel = "Technical Writer",
            rigKind = NodeCreatureRoleKind.DocumentationSpecialist,
        ),
        MascotCharacterIdentity(
            id = "azphalt-ux-researcher",
            roleLabel = "UX Researcher",
            rigKind = NodeCreatureRoleKind.UxDesigner,
        ),
        MascotCharacterIdentity(
            id = "azphalt-vendor-evaluator",
            roleLabel = "Vendor Evaluator",
            rigKind = NodeCreatureRoleKind.QualityGuardian,
        ),
    )

    private val byRole = storeCharacters.associateBy { normalize(it.roleLabel) }

    val explicitCharacters: List<MascotCharacterIdentity>
        get() = storeCharacters

    fun explicitForRole(roleLabel: String): MascotCharacterIdentity? = byRole[normalize(roleLabel)]

    private fun normalize(value: String): String = value.trim().lowercase()
}
