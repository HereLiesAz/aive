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
        require(title.isNotBlank()) { "Nanny solution title must not be blank" }
        require(target.isNotBlank()) { "Nanny solution target must not be blank" }
        require(rationale.isNotBlank()) { "Nanny solution rationale must not be blank" }
        require(tradeoffs.isNotEmpty()) { "Nanny solution must state at least one tradeoff" }
        require(validationTests.isNotEmpty()) { "Nanny solution must define validation tests" }
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
        require(id.isNotBlank()) { "Nanny finding id must not be blank" }
        require(subject.isNotBlank()) { "Nanny finding subject must not be blank" }
        require(observation.isNotBlank()) { "Nanny finding observation must not be blank" }
        require(evidence.isNotEmpty()) { "Nanny finding must include evidence" }
        require(counterEvidence.isNotEmpty()) { "Nanny finding must include counter-evidence" }
        require(falsificationCriteria.isNotEmpty()) { "Nanny finding must state falsification criteria" }
        require(solutions.size >= 2) { "Nanny finding must provide at least two materially distinct solutions" }
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
        require(name.isNotBlank()) { "Nanny test name must not be blank" }
        require(hypothesis.isNotBlank()) { "Nanny test hypothesis must not be blank" }
        require(fixture.isNotBlank()) { "Nanny test fixture must not be blank" }
        require(controls.isNotEmpty()) { "Nanny test design must include controls" }
        require(measurements.isNotEmpty()) { "Nanny test design must include measurements" }
        require(expectedFailureModes.isNotEmpty()) { "Nanny test design must include expected failure modes" }
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
        require(reportId.isNotBlank()) { "Nanny report id must not be blank" }
        require(title.isNotBlank()) { "Nanny report title must not be blank" }
        require(measuredToEpochMillis >= measuredFromEpochMillis) { "Nanny report measurement window is invalid" }
        require(findings.isNotEmpty()) { "Nanny report must contain at least one finding" }
        require(orchestrationTests.isNotEmpty()) { "Nanny report must design orchestration-layer tests" }
        require(memoryTests.isNotEmpty()) { "Nanny report must design memory-layer tests" }
        require(summary.isNotBlank()) { "Nanny report summary must not be blank" }
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
        require(reportId.isNotBlank()) { "Nanny review report id must not be blank" }
        require(evidenceChecked.isNotEmpty()) { "Antagonist review must record evidence it checked" }
        require(note.isNotBlank()) { "Antagonist review note must not be blank" }
    }
}
