package com.hereliesaz.geministrator

/**
 * Character registry for Azphalt workflow/role personas.
 *
 * Every listed Store persona owns a distinct character identity. [anatomyTemplate] only chooses a
 * compatible underlying body grammar; it never collapses two roles into the same character.
 */
internal data class MascotCharacterIdentity(
    val id: String,
    val roleLabel: String,
    val anatomyTemplate: NodeCreatureRoleKind,
    val phenotypeIndex: Int,
)

internal object MascotCharacterCatalog {
    private val storeCharacters = listOf(
        MascotCharacterIdentity(
            id = "azphalt-ai-evaluation-scientist",
            roleLabel = "AI Evaluation Scientist",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 0,
        ),
        MascotCharacterIdentity(
            id = "azphalt-accessibility-auditor",
            roleLabel = "Accessibility Auditor",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 1,
        ),
        MascotCharacterIdentity(
            id = "azphalt-agent-red-teamer",
            roleLabel = "Agent Red Teamer",
            anatomyTemplate = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 2,
        ),
        MascotCharacterIdentity(
            id = "azphalt-agent-security-architect",
            roleLabel = "Agent Security Architect",
            anatomyTemplate = NodeCreatureRoleKind.Architect,
            phenotypeIndex = 3,
        ),
        MascotCharacterIdentity(
            id = "azphalt-book-editor",
            roleLabel = "Book Editor",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 4,
        ),
        MascotCharacterIdentity(
            id = "azphalt-brand-strategist",
            roleLabel = "Brand Strategist",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 5,
        ),
        MascotCharacterIdentity(
            id = "azphalt-campaign-strategist",
            roleLabel = "Campaign Strategist",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 6,
        ),
        MascotCharacterIdentity(
            id = "azphalt-competitive-analyst",
            roleLabel = "Competitive Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 7,
        ),
        MascotCharacterIdentity(
            id = "azphalt-content-strategist",
            roleLabel = "Content Strategist",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 8,
        ),
        MascotCharacterIdentity(
            id = "azphalt-continuity-supervisor",
            roleLabel = "Continuity Supervisor",
            anatomyTemplate = NodeCreatureRoleKind.HallMonitor,
            phenotypeIndex = 9,
        ),
        MascotCharacterIdentity(
            id = "azphalt-contract-reviewer",
            roleLabel = "Contract Reviewer",
            anatomyTemplate = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 10,
        ),
        MascotCharacterIdentity(
            id = "azphalt-corporate-investigator",
            roleLabel = "Corporate Investigator",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 11,
        ),
        MascotCharacterIdentity(
            id = "azphalt-crisis-communicator",
            roleLabel = "Crisis Communicator",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 12,
        ),
        MascotCharacterIdentity(
            id = "azphalt-curriculum-architect",
            roleLabel = "Curriculum Architect",
            anatomyTemplate = NodeCreatureRoleKind.Architect,
            phenotypeIndex = 13,
        ),
        MascotCharacterIdentity(
            id = "azphalt-data-investigator",
            roleLabel = "Data Investigator",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 14,
        ),
        MascotCharacterIdentity(
            id = "azphalt-data-story-editor",
            roleLabel = "Data Story Editor",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 15,
        ),
        MascotCharacterIdentity(
            id = "azphalt-decision-analyst",
            roleLabel = "Decision Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 16,
        ),
        MascotCharacterIdentity(
            id = "azphalt-deep-research-lead",
            roleLabel = "Deep Research Lead",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 17,
        ),
        MascotCharacterIdentity(
            id = "azphalt-deep-researcher",
            roleLabel = "Deep Researcher",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 18,
        ),
        MascotCharacterIdentity(
            id = "azphalt-documentary-producer",
            roleLabel = "Documentary Producer",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 19,
        ),
        MascotCharacterIdentity(
            id = "azphalt-event-producer",
            roleLabel = "Event Producer",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 20,
        ),
        MascotCharacterIdentity(
            id = "azphalt-evidence-skeptic",
            roleLabel = "Evidence Skeptic",
            anatomyTemplate = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 21,
        ),
        MascotCharacterIdentity(
            id = "azphalt-evidence-synthesis-lead",
            roleLabel = "Evidence Synthesis Lead",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 22,
        ),
        MascotCharacterIdentity(
            id = "azphalt-execution-secretary",
            roleLabel = "Execution Secretary",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 23,
        ),
        MascotCharacterIdentity(
            id = "azphalt-exhibition-producer",
            roleLabel = "Exhibition Producer",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 24,
        ),
        MascotCharacterIdentity(
            id = "azphalt-experimental-methodologist",
            roleLabel = "Experimental Methodologist",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 25,
        ),
        MascotCharacterIdentity(
            id = "azphalt-failure-diagnostician",
            roleLabel = "Failure Diagnostician",
            anatomyTemplate = NodeCreatureRoleKind.RecoveryEngineer,
            phenotypeIndex = 26,
        ),
        MascotCharacterIdentity(
            id = "azphalt-feature-engineer",
            roleLabel = "Feature Engineer",
            anatomyTemplate = NodeCreatureRoleKind.ImplementationEngineer,
            phenotypeIndex = 27,
        ),
        MascotCharacterIdentity(
            id = "azphalt-feature-reviewer",
            roleLabel = "Feature Reviewer",
            anatomyTemplate = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 28,
        ),
        MascotCharacterIdentity(
            id = "azphalt-film-continuity-supervisor",
            roleLabel = "Film Continuity Supervisor",
            anatomyTemplate = NodeCreatureRoleKind.HallMonitor,
            phenotypeIndex = 29,
        ),
        MascotCharacterIdentity(
            id = "azphalt-film-showrunner",
            roleLabel = "Film Showrunner",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 30,
        ),
        MascotCharacterIdentity(
            id = "azphalt-financial-model-auditor",
            roleLabel = "Financial Model Auditor",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 31,
        ),
        MascotCharacterIdentity(
            id = "azphalt-game-director",
            roleLabel = "Game Director",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 32,
        ),
        MascotCharacterIdentity(
            id = "azphalt-geolocation-analyst",
            roleLabel = "Geolocation Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 33,
        ),
        MascotCharacterIdentity(
            id = "azphalt-grant-strategist",
            roleLabel = "Grant Strategist",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 34,
        ),
        MascotCharacterIdentity(
            id = "azphalt-graphic-novel-continuity-editor",
            roleLabel = "Graphic Novel Continuity Editor",
            anatomyTemplate = NodeCreatureRoleKind.HallMonitor,
            phenotypeIndex = 35,
        ),
        MascotCharacterIdentity(
            id = "azphalt-graphic-novel-director",
            roleLabel = "Graphic Novel Director",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 36,
        ),
        MascotCharacterIdentity(
            id = "azphalt-identity-director",
            roleLabel = "Identity Director",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 37,
        ),
        MascotCharacterIdentity(
            id = "azphalt-incident-commander",
            roleLabel = "Incident Commander",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 38,
        ),
        MascotCharacterIdentity(
            id = "azphalt-incident-investigator",
            roleLabel = "Incident Investigator",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 39,
        ),
        MascotCharacterIdentity(
            id = "azphalt-interaction-designer",
            roleLabel = "Interaction Designer",
            anatomyTemplate = NodeCreatureRoleKind.UxDesigner,
            phenotypeIndex = 40,
        ),
        MascotCharacterIdentity(
            id = "azphalt-investigative-reporter",
            roleLabel = "Investigative Reporter",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 41,
        ),
        MascotCharacterIdentity(
            id = "azphalt-knowledge-system-evaluator",
            roleLabel = "Knowledge System Evaluator",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 42,
        ),
        MascotCharacterIdentity(
            id = "azphalt-launch-strategist",
            roleLabel = "Launch Strategist",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 43,
        ),
        MascotCharacterIdentity(
            id = "azphalt-learning-coach",
            roleLabel = "Learning Coach",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 44,
        ),
        MascotCharacterIdentity(
            id = "azphalt-localization-lead",
            roleLabel = "Localization Lead",
            anatomyTemplate = NodeCreatureRoleKind.ReleaseEngineer,
            phenotypeIndex = 45,
        ),
        MascotCharacterIdentity(
            id = "azphalt-manuscript-continuity-editor",
            roleLabel = "Manuscript Continuity Editor",
            anatomyTemplate = NodeCreatureRoleKind.HallMonitor,
            phenotypeIndex = 46,
        ),
        MascotCharacterIdentity(
            id = "azphalt-methods-reviewer",
            roleLabel = "Methods Reviewer",
            anatomyTemplate = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 47,
        ),
        MascotCharacterIdentity(
            id = "azphalt-negotiation-planner",
            roleLabel = "Negotiation Planner",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 48,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-attribution-skeptic",
            roleLabel = "OSINT Attribution Skeptic",
            anatomyTemplate = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 49,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-change-auditor",
            roleLabel = "OSINT Change Auditor",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 50,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-chronology-auditor",
            roleLabel = "OSINT Chronology Auditor",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 51,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-corporate-investigator",
            roleLabel = "OSINT Corporate Investigator",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 52,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-earth-observation-analyst",
            roleLabel = "OSINT Earth Observation Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 53,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-entity-resolution-auditor",
            roleLabel = "OSINT Entity Resolution Auditor",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 54,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-geolocation-analyst",
            roleLabel = "OSINT Geolocation Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 55,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-infrastructure-analyst",
            roleLabel = "OSINT Infrastructure Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 56,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-location-skeptic",
            roleLabel = "OSINT Location Skeptic",
            anatomyTemplate = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 57,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-mobility-analyst",
            roleLabel = "OSINT Mobility Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 58,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-provenance-analyst",
            roleLabel = "OSINT Provenance Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 59,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-risk-researcher",
            roleLabel = "OSINT Risk Researcher",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 60,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-screening-auditor",
            roleLabel = "OSINT Screening Auditor",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 61,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-timeline-analyst",
            roleLabel = "OSINT Timeline Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 62,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-track-auditor",
            roleLabel = "OSINT Track Auditor",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 63,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-verification-editor",
            roleLabel = "OSINT Verification Editor",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 64,
        ),
        MascotCharacterIdentity(
            id = "azphalt-osint-verifier",
            roleLabel = "OSINT Verifier",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 65,
        ),
        MascotCharacterIdentity(
            id = "azphalt-operations-designer",
            roleLabel = "Operations Designer",
            anatomyTemplate = NodeCreatureRoleKind.UxDesigner,
            phenotypeIndex = 66,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-geometry-engineer",
            roleLabel = "PaperPlanes Geometry Engineer",
            anatomyTemplate = NodeCreatureRoleKind.ImplementationEngineer,
            phenotypeIndex = 67,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-layer-engineer",
            roleLabel = "PaperPlanes Layer Engineer",
            anatomyTemplate = NodeCreatureRoleKind.ImplementationEngineer,
            phenotypeIndex = 68,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-occlusion-architect",
            roleLabel = "PaperPlanes Occlusion Architect",
            anatomyTemplate = NodeCreatureRoleKind.Architect,
            phenotypeIndex = 69,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-scene-director",
            roleLabel = "PaperPlanes Scene Director",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 70,
        ),
        MascotCharacterIdentity(
            id = "azphalt-paperplanes-visual-qc",
            roleLabel = "PaperPlanes Visual QC",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
            phenotypeIndex = 71,
        ),
        MascotCharacterIdentity(
            id = "azphalt-playtest-analyst",
            roleLabel = "Playtest Analyst",
            anatomyTemplate = NodeCreatureRoleKind.CrashTestDummy,
            phenotypeIndex = 72,
        ),
        MascotCharacterIdentity(
            id = "azphalt-podcast-fact-editor",
            roleLabel = "Podcast Fact Editor",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 73,
        ),
        MascotCharacterIdentity(
            id = "azphalt-podcast-producer",
            roleLabel = "Podcast Producer",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 74,
        ),
        MascotCharacterIdentity(
            id = "azphalt-presentation-editor",
            roleLabel = "Presentation Editor",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 75,
        ),
        MascotCharacterIdentity(
            id = "azphalt-pricing-researcher",
            roleLabel = "Pricing Researcher",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 76,
        ),
        MascotCharacterIdentity(
            id = "azphalt-privacy-risk-analyst",
            roleLabel = "Privacy Risk Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 77,
        ),
        MascotCharacterIdentity(
            id = "azphalt-process-engineer",
            roleLabel = "Process Engineer",
            anatomyTemplate = NodeCreatureRoleKind.ImplementationEngineer,
            phenotypeIndex = 78,
        ),
        MascotCharacterIdentity(
            id = "azphalt-proposal-lead",
            roleLabel = "Proposal Lead",
            anatomyTemplate = NodeCreatureRoleKind.Orchestrator,
            phenotypeIndex = 79,
        ),
        MascotCharacterIdentity(
            id = "azphalt-qualitative-synthesist",
            roleLabel = "Qualitative Synthesist",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 80,
        ),
        MascotCharacterIdentity(
            id = "azphalt-release-producer",
            roleLabel = "Release Producer",
            anatomyTemplate = NodeCreatureRoleKind.ReleaseEngineer,
            phenotypeIndex = 81,
        ),
        MascotCharacterIdentity(
            id = "azphalt-repair-verifier",
            roleLabel = "Repair Verifier",
            anatomyTemplate = NodeCreatureRoleKind.RecoveryEngineer,
            phenotypeIndex = 82,
        ),
        MascotCharacterIdentity(
            id = "azphalt-review-scientist",
            roleLabel = "Review Scientist",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 83,
        ),
        MascotCharacterIdentity(
            id = "azphalt-scenario-planner",
            roleLabel = "Scenario Planner",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 84,
        ),
        MascotCharacterIdentity(
            id = "azphalt-search-strategist",
            roleLabel = "Search Strategist",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 85,
        ),
        MascotCharacterIdentity(
            id = "azphalt-selection-designer",
            roleLabel = "Selection Designer",
            anatomyTemplate = NodeCreatureRoleKind.UxDesigner,
            phenotypeIndex = 86,
        ),
        MascotCharacterIdentity(
            id = "azphalt-statistical-skeptic",
            roleLabel = "Statistical Skeptic",
            anatomyTemplate = NodeCreatureRoleKind.AdversarialReviewer,
            phenotypeIndex = 87,
        ),
        MascotCharacterIdentity(
            id = "azphalt-supplier-analyst",
            roleLabel = "Supplier Analyst",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 88,
        ),
        MascotCharacterIdentity(
            id = "azphalt-support-escalation-lead",
            roleLabel = "Support Escalation Lead",
            anatomyTemplate = NodeCreatureRoleKind.RecoveryEngineer,
            phenotypeIndex = 89,
        ),
        MascotCharacterIdentity(
            id = "azphalt-survey-methodologist",
            roleLabel = "Survey Methodologist",
            anatomyTemplate = NodeCreatureRoleKind.Researcher,
            phenotypeIndex = 90,
        ),
        MascotCharacterIdentity(
            id = "azphalt-technical-writer",
            roleLabel = "Technical Writer",
            anatomyTemplate = NodeCreatureRoleKind.ProductManager,
            phenotypeIndex = 91,
        ),
        MascotCharacterIdentity(
            id = "azphalt-ux-researcher",
            roleLabel = "UX Researcher",
            anatomyTemplate = NodeCreatureRoleKind.UxDesigner,
            phenotypeIndex = 92,
        ),
        MascotCharacterIdentity(
            id = "azphalt-vendor-evaluator",
            roleLabel = "Vendor Evaluator",
            anatomyTemplate = NodeCreatureRoleKind.QaEngineer,
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
            anatomyTemplate = NodeCreatureRoleKind.Generic,
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
