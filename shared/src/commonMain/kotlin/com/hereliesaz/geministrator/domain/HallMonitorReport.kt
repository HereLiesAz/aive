package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

@Serializable
enum class HallMonitorScope {
    Model,
    Role,
    Workflow,
    OrchestrationLayer,
    MemoryLayer,
    Swarm,
}

@Serializable
enum class HallMonitorAction {
    Retain,
    Remove,
    Rewrite,
    Replace,
    Split,
    Merge,
    Reroute,
    Restructure,
    Benchmark,
}

@Serializable
data class HallMonitorEvidence(
    val claim: String,
    val source: String,
    val measurementWindow: String,
    val population: String,
    val value: String? = null,
)

@Serializable
data class HallMonitorSolution(
    val title: String,
    val action: HallMonitorAction,
    val target: String,
    val rationale: String,
    val tradeoffs: List<String>,
    val validationTests: List<String>,
) {
    init {
        require(title.isNotBlank()) { "Hall Monitor solution title must not be blank" }
        require(target.isNotBlank()) { "Hall Monitor solution target must not be blank" }
        require(rationale.isNotBlank()) { "Hall Monitor solution rationale must not be blank" }
        require(tradeoffs.isNotEmpty()) { "Hall Monitor solution must state at least one tradeoff" }
        require(validationTests.isNotEmpty()) { "Hall Monitor solution must define validation tests" }
    }
}

@Serializable
data class HallMonitorFinding(
    val id: String,
    val scope: HallMonitorScope,
    val subject: String,
    val observation: String,
    val evidence: List<HallMonitorEvidence>,
    val counterEvidence: List<HallMonitorEvidence>,
    val falsificationCriteria: List<String>,
    val solutions: List<HallMonitorSolution>,
) {
    init {
        require(id.isNotBlank()) { "Hall Monitor finding id must not be blank" }
        require(subject.isNotBlank()) { "Hall Monitor finding subject must not be blank" }
        require(observation.isNotBlank()) { "Hall Monitor finding observation must not be blank" }
        require(evidence.isNotEmpty()) { "Hall Monitor finding must include evidence" }
        require(counterEvidence.isNotEmpty()) { "Hall Monitor finding must include counter-evidence" }
        require(falsificationCriteria.isNotEmpty()) { "Hall Monitor finding must state falsification criteria" }
        require(solutions.size >= 2) { "Hall Monitor finding must provide at least two materially distinct solutions" }
    }
}

@Serializable
data class HallMonitorTestDesign(
    val name: String,
    val hypothesis: String,
    val fixture: String,
    val controls: List<String>,
    val measurements: List<String>,
    val expectedFailureModes: List<String>,
    val delegationRoleId: RoleDefinitionId? = null,
) {
    init {
        require(name.isNotBlank()) { "Hall Monitor test name must not be blank" }
        require(hypothesis.isNotBlank()) { "Hall Monitor test hypothesis must not be blank" }
        require(fixture.isNotBlank()) { "Hall Monitor test fixture must not be blank" }
        require(controls.isNotEmpty()) { "Hall Monitor test design must include controls" }
        require(measurements.isNotEmpty()) { "Hall Monitor test design must include measurements" }
        require(expectedFailureModes.isNotEmpty()) { "Hall Monitor test design must include expected failure modes" }
    }
}

@Serializable
data class HallMonitorReport(
    val reportId: String,
    val title: String,
    val measuredFromEpochMillis: Long,
    val measuredToEpochMillis: Long,
    val findings: List<HallMonitorFinding>,
    val orchestrationTests: List<HallMonitorTestDesign>,
    val memoryTests: List<HallMonitorTestDesign>,
    val summary: String,
) {
    init {
        require(reportId.isNotBlank()) { "Hall Monitor report id must not be blank" }
        require(title.isNotBlank()) { "Hall Monitor report title must not be blank" }
        require(measuredToEpochMillis >= measuredFromEpochMillis) { "Hall Monitor report measurement window is invalid" }
        require(findings.isNotEmpty()) { "Hall Monitor report must contain at least one finding" }
        require(orchestrationTests.isNotEmpty()) { "Hall Monitor report must design orchestration-layer tests" }
        require(memoryTests.isNotEmpty()) { "Hall Monitor report must design memory-layer tests" }
        require(summary.isNotBlank()) { "Hall Monitor report summary must not be blank" }
    }
}

@Serializable
enum class HallMonitorReviewVerdict {
    Pass,
    Revise,
    Reject,
}

@Serializable
data class HallMonitorAntagonistReview(
    val reportId: String,
    val verdict: HallMonitorReviewVerdict,
    val findings: List<String>,
    val evidenceChecked: List<String>,
    val note: String,
) {
    init {
        require(reportId.isNotBlank()) { "Hall Monitor review report id must not be blank" }
        require(evidenceChecked.isNotEmpty()) { "Antagonist review must record evidence it checked" }
        require(note.isNotBlank()) { "Antagonist review note must not be blank" }
    }
}
