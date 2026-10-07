package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.orchestration.DecisionInformedOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.OrchestrationSpecialistCatalog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopOrchestrationSpecialistInstallerTest {
    private val root = Files.createTempDirectory("aive-orchestration").toFile()
    private val httpClient = HttpClient(CIO)
    // The released catalog may be empty; the installer is exercised against a fixture with the decision model.
    private val fixture = OrchestrationSpecialistCatalog.parse(
        """{"specialists":[{"specialistId":"${OrchestrationSpecialistCatalog.DECISIONS_SPECIALIST_ID}","mergedVariants":[""" +
            """{"logicalArtifactId":"orchestration:decisions:int8","foundationModelId":"google/bert_uncased_L-4_H-256_A-4",""" +
            """"releaseRepository":"HereLiesAz/aive","releaseTag":"orchestration-decisions-v1",""" +
            """"assetName":"aive-orchestration-decisions-int8.tar.gz","sha256":"${"a".repeat(64)}",""" +
            """"format":"onnx","precision":"int8","kind":"MergedModel","capabilities":["orchestration-decisions"]}]}]}""",
    )
    private val installer = DesktopOrchestrationSpecialistInstaller(httpClient, root) { fixture }
    private val decisions = DesktopOrchestrationDecisionModel(installer::decisionModelRoot)

    @AfterTest
    fun cleanUp() {
        httpClient.close()
        root.deleteRecursively()
    }

    @Test
    fun releasedCatalogResolvesArtifactsForDesktop() {
        assertTrue(installer.releasedArtifacts().isNotEmpty())
        assertFalse(installer.hasAnyInstalledReleasedArtifact())
        assertSame(
            DeterministicLocalOrchestrationUtilities,
            DesktopOrchestrationSpecialists.utilities(installer, DesktopLocalOrchestrationModelExecutor(installer), decisions),
        )
    }

    @Test
    fun installedArtifactsSwitchUtilitiesOnAndRemovalSwitchesThemOff() = runBlocking {
        installer.releasedArtifacts().forEach { artifact ->
            val name = artifact.logicalArtifactId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (artifact.kind == LocalModelArtifactKind.Adapter) {
                val file = File(root, "adapters/$name.safetensors").apply { parentFile.mkdirs(); writeText("x") }
                File(file.path + ".sha256").writeText(artifact.sha256)
            } else {
                File(root, name).apply {
                    mkdirs()
                    listOf("model.onnx", "tokenizer.json", "config.json").forEach { File(this, it).writeText("x") }
                    File(this, ".installed-sha256").writeText(artifact.sha256)
                }
            }
        }
        assertTrue(installer.allReleasedInstalled())
        assertTrue(decisions.isInstalled())
        assertIs<DecisionInformedOrchestrationUtilities>(
            DesktopOrchestrationSpecialists.utilities(installer, DesktopLocalOrchestrationModelExecutor(installer), decisions),
        )

        installer.removeReleased()

        assertFalse(installer.hasAnyInstalledReleasedArtifact())
    }

    @Test
    fun aRecordedDigestThatDiffersFromTheCatalogIsNotInstalled() {
        val artifact = installer.releasedArtifacts().first { it.kind != LocalModelArtifactKind.Adapter }
        File(root, artifact.logicalArtifactId.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply {
            mkdirs()
            listOf("model.onnx", "tokenizer.json", "config.json").forEach { File(this, it).writeText("x") }
            File(this, ".installed-sha256").writeText("0".repeat(64))
        }
        assertFalse(installer.hasAnyInstalledReleasedArtifact())
    }
}
