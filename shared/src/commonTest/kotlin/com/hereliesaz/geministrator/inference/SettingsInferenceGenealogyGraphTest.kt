package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsInferenceGenealogyGraphTest {
    @Test
    fun graphSurvivesRecreationWithCompleteStructuralAncestry() = runTest {
        val settings = MapSettings()
        val first = SettingsInferenceGenealogyGraph(settings)
        val node = InferenceGenealogyNode(
            invocationId = "invocation-child",
            upstreamInvocationIds = setOf("invocation-parent"),
            upstreamTaskRunIds = setOf(TaskRunId("task-run-1")),
            upstreamArtifactIds = setOf(ArtifactId("artifact-1")),
            memoryAddresses = setOf("memory://episode/1"),
            toolEvidenceIds = setOf("tool-evidence-1"),
            promptFingerprint = "prompt-sha",
            configurationFingerprint = "config-sha",
        )

        first.register(node)

        val recreated = SettingsInferenceGenealogyGraph(settings)
        assertEquals(node, recreated.get(node.invocationId))
        assertEquals(listOf(node), recreated.all())
    }

    @Test
    fun conflictingAncestryIsRejectedAfterRestart() = runTest {
        val settings = MapSettings()
        SettingsInferenceGenealogyGraph(settings).register(
            InferenceGenealogyNode(
                invocationId = "same-invocation",
                upstreamArtifactIds = setOf(ArtifactId("artifact-a")),
            ),
        )

        val recreated = SettingsInferenceGenealogyGraph(settings)
        assertFailsWith<IllegalArgumentException> {
            recreated.register(
                InferenceGenealogyNode(
                    invocationId = "same-invocation",
                    upstreamArtifactIds = setOf(ArtifactId("artifact-b")),
                ),
            )
        }
    }

    @Test
    fun governanceUsesPersistedGraphAfterRestart() = runTest {
        val settings = MapSettings()
        val first = SettingsInferenceGenealogyGraph(settings)
        first.register(InferenceGenealogyNode(invocationId = "root", toolEvidenceIds = setOf("source")))
        first.register(InferenceGenealogyNode(invocationId = "left", upstreamInvocationIds = setOf("root")))
        first.register(InferenceGenealogyNode(invocationId = "right", upstreamInvocationIds = setOf("root")))

        val runtime = InferenceGenealogyGovernanceRuntime(
            graph = SettingsInferenceGenealogyGraph(settings),
        )
        val report = runtime.evaluate(
            GenealogyGovernanceRequest(invocationIds = setOf("left", "right")),
        )

        val sharedAncestry = assertNotNull(
            report.findings.firstOrNull { it.kind == GenealogyGovernanceFindingKind.CommonAncestry },
        )
        assertTrue("root" in sharedAncestry.sharedAncestorInvocationIds)
    }
}
