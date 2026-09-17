package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ArtifactKind {
    Requirement,
    Research,
    Architecture,
    Design,
    EnvironmentSpecification,
    TaskPlan,
    Specification,
    AcceptanceTestPlan,
    BehavioralTest,
    ContractTest,
    FailureScenario,
    CodeChange,
    TestPlan,
    TestCode,
    RegressionTest,
    CommandOutput,
    Media,
    PullRequest,
    TestResult,
    Review,
    Verification,
    FailureAnalysis,
    Release,
    HallMonitorReport,
    HallMonitorReview,
}

@Serializable
data class ArtifactRef(
    val id: ArtifactId,
    val kind: ArtifactKind,
    val taskRunId: TaskRunId,
    val label: String,
    val uri: String? = null,
    val textContent: String? = null,
    val mediaType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAtEpochMillis: Long,
) {
    init {
        require(uri != null || textContent != null) { "ArtifactRef must have at least one of uri or textContent" }
    }
}
