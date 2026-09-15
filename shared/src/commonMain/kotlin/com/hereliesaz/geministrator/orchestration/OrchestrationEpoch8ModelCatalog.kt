package com.hereliesaz.geministrator.orchestration

enum class OrchestrationAgentRole {
    Planner,
    PlanRepair,
}

data class OrchestrationModelReleaseBundle(
    val role: OrchestrationAgentRole,
    val releaseTag: String,
    val archiveName: String,
    val partsManifestName: String,
    val runtimeArtifactId: String,
    val quantization: String,
) {
    val releaseApiUrl: String
        get() = "https://api.github.com/repos/HereLiesAz/haive/releases/tags/$releaseTag"

    val manifestDownloadUrl: String
        get() = "https://github.com/HereLiesAz/haive/releases/download/$releaseTag/$partsManifestName"
}

object OrchestrationEpoch8ModelCatalog {
    const val RELEASE_TAG: String = "orchestration-layer-epoch8"

    val planner = OrchestrationModelReleaseBundle(
        role = OrchestrationAgentRole.Planner,
        releaseTag = RELEASE_TAG,
        archiveName = "haive-orch_planner-int8-epoch8.tar.gz",
        partsManifestName = "haive-orch_planner-int8-epoch8.tar.gz.parts.json",
        runtimeArtifactId = "orchestration:epoch8:planner:int8",
        quantization = "int8",
    )

    val planRepair = OrchestrationModelReleaseBundle(
        role = OrchestrationAgentRole.PlanRepair,
        releaseTag = RELEASE_TAG,
        archiveName = "haive-orch_plan_repair-int8-epoch8.tar.gz",
        partsManifestName = "haive-orch_plan_repair-int8-epoch8.tar.gz.parts.json",
        runtimeArtifactId = "orchestration:epoch8:plan-repair:int8",
        quantization = "int8",
    )

    val all: List<OrchestrationModelReleaseBundle> = listOf(planner, planRepair)

    fun bundleFor(role: OrchestrationAgentRole): OrchestrationModelReleaseBundle =
        all.single { it.role == role }
}
