package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.providers.AppIntegrationAdapterSelector
import com.hereliesaz.geministrator.orchestration.AppIntegrationInstallState
import com.hereliesaz.geministrator.orchestration.DecisionInformedOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.OrchestrationSpecialistCatalog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    private val baseSha = "b".repeat(64)
    private val adapterSha = "c".repeat(64)
    private val appIntegration = OrchestrationSpecialistCatalog.parseAppIntegration(
        """{"appIntegration":{"specialistId":"app-integration:assistant","sharedBaseVariants":[""" +
            """{"logicalArtifactId":"orchestration:base:v4:int8","foundationModelId":"Qwen/Qwen2.5-0.5B-Instruct",""" +
            """"releaseRepository":"HereLiesAz/aive","releaseTag":"orchestration-base-v4","assetName":"base.tar.gz",""" +
            """"sha256":"$baseSha","format":"onnx","precision":"int8","kind":"SharedBase"}],""" +
            """"adapter":{"logicalArtifactId":"app-integration:assistant:lora","foundationModelId":"Qwen/Qwen2.5-0.5B-Instruct",""" +
            """"releaseRepository":"HereLiesAz/aive","releaseTag":"app-integration-v1","assetName":"adapter.safetensors",""" +
            """"sha256":"$adapterSha","format":"safetensors","precision":"fp16","kind":"Adapter","adapterId":"app-integration-v1","sizeBytes":9}}}""",
    )
    private val installer = DesktopOrchestrationSpecialistInstaller(httpClient, root, appIntegration = { appIntegration }) { fixture }
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
        // "All released" includes the app-integration adapter and its base, which install with the rest.
        assertFalse(installer.allReleasedInstalled())
        installAppIntegration(adapterBase = "orchestration:base:v4:int8")
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

    private fun installAppIntegration(adapterBase: String?, installBase: Boolean = true, installAdapter: Boolean = true) {
        val release = assertNotNull(appIntegration)
        if (installBase) {
            File(root, "orchestration_base_v4_int8").apply {
                mkdirs()
                listOf("model.onnx", "tokenizer.json", "config.json").forEach { File(this, it).writeText("x") }
                File(this, ".installed-sha256").writeText(baseSha)
            }
        }
        if (installAdapter) {
            val file = File(root, "adapters/app-integration_assistant_lora.safetensors")
            file.parentFile.mkdirs()
            val metadata = if (adapterBase == null) """{"format":"aive-lora-inputs"}""" else """{"format":"aive-lora-inputs","base":"$adapterBase"}"""
            val header = """{"__metadata__":$metadata,"onnx::MatMul_1":{"dtype":"F16","shape":[2],"data_offsets":[0,4]}}""".encodeToByteArray()
            val buffer = ByteBuffer.allocate(8 + header.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putLong(header.size.toLong()).put(header).putShort(0x3C00).putShort(0)
            file.writeBytes(buffer.array())
            File(file.path + ".sha256").writeText(release.adapter.sha256)
        }
    }

    private fun selector() = AppIntegrationAdapterSelector<File, DesktopLoraAdapter>(
        release = installer::appIntegrationRelease,
        runtime = DesktopOrchestrationSpecialists.runtimeCapabilities,
        installedAdapter = installer::installed,
        installedBase = installer::installed,
        load = { file -> DesktopLoraAdapter.load(file) },
        trainedFor = { adapter -> adapter.metadata["base"] },
    )

    @Test
    fun theAppIntegrationAdapterInstallsWithTheSpecialistsAndRemovesWithThem() = runBlocking<Unit> {
        // The base comes first and both are part of the one install action; the decision model is still listed.
        assertEquals(
            listOf("orchestration:base:v4:int8", "app-integration:assistant:lora"),
            installer.appIntegrationArtifacts().map { it.logicalArtifactId },
        )
        assertEquals(AppIntegrationInstallState.NotInstalled, installer.appIntegrationState())
        assertFalse(installer.allReleasedInstalled())

        installAppIntegration(adapterBase = "orchestration:base:v4:int8", installBase = false)
        assertEquals(AppIntegrationInstallState.Partial("base.tar.gz"), installer.appIntegrationState())

        installAppIntegration(adapterBase = "orchestration:base:v4:int8")
        assertEquals(AppIntegrationInstallState.Installed, installer.appIntegrationState())
        // The orchestration side still has no artifact installed: the adapter does not switch utilities on.
        assertFalse(installer.hasAnyInstalledReleasedArtifact())

        installer.removeReleased()
        assertEquals(AppIntegrationInstallState.NotInstalled, installer.appIntegrationState())
    }

    @Test
    fun anAdapterWithADigestThatDiffersFromTheCatalogIsNotInstalled() {
        installAppIntegration(adapterBase = null)
        File(root, "adapters/app-integration_assistant_lora.safetensors.sha256").writeText("0".repeat(64))
        assertEquals(AppIntegrationInstallState.Partial("adapter.safetensors"), installer.appIntegrationState())
    }

    @Test
    fun theSelectorReadsTheRealAdapterFileThroughTheOrchestrationLoader() {
        installAppIntegration(adapterBase = "orchestration:base:v4:int8")
        val selector = selector()
        try {
            val chosen = selector.select()
            assertIs<AppIntegrationAdapterSelector.Selection.Adapter<File, DesktopLoraAdapter>>(chosen)
            assertEquals(setOf("onnx::MatMul_1"), chosen.adapter.tensors.keys)
            assertEquals(File(root, "orchestration_base_v4_int8"), chosen.baseRoot)
        } finally {
            selector.close()
        }
    }

    @Test
    fun anAdapterTrainedForAnotherBaseRunsWithZerosAndSaysWhy() {
        installAppIntegration(adapterBase = "orchestration:base:v3:int8")
        val selector = selector()
        try {
            assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select())
            assertEquals(
                "The app-integration adapter was trained for orchestration:base:v3:int8, not orchestration:base:v4:int8, " +
                    "so small tasks run on the plain model.",
                selector.adapterNote(),
            )
        } finally {
            selector.close()
        }
    }

    @Test
    fun withoutAnAdapterThereIsNothingToSay() {
        val selector = selector()
        assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select())
        assertNull(selector.adapterNote())
    }
}
