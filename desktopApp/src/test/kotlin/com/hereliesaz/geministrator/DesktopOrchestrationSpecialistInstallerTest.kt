package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopOrchestrationSpecialistInstallerTest {
    private val root = Files.createTempDirectory("aive-orchestration").toFile()
    private val httpClient = HttpClient(CIO)
    private val installer = DesktopOrchestrationSpecialistInstaller(httpClient, root)

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
            DesktopOrchestrationSpecialists.utilities(installer, DesktopLocalOrchestrationModelExecutor(installer)),
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
        assertNotSame(
            DeterministicLocalOrchestrationUtilities,
            DesktopOrchestrationSpecialists.utilities(installer, DesktopLocalOrchestrationModelExecutor(installer)),
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
