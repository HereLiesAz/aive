package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.inference.LocalModelLoadPlan
import com.hereliesaz.geministrator.inference.LocalModelRuntimeCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrchestrationSpecialistCatalogTest {
    @Test
    fun releasedCatalogParses() {
        val library = OrchestrationSpecialistCatalog.released()
        // Whatever the release lists is held whole: one specialist per entry, and every artifact names a real asset and a hex digest.
        val appIntegrationEntries = if (OrchestrationSpecialistCatalog.appIntegration() != null) 1 else 0
        assertEquals(
            Regex("\"specialistId\"").findAll(OrchestrationSpecialistCatalog.RELEASED).count() - appIntegrationEntries,
            library.allSpecialists().size,
        )
        library.allArtifacts().forEach { artifact ->
            assertTrue(Regex("[0-9a-f]{64}").matches(artifact.sha256), "${artifact.logicalArtifactId}: ${artifact.sha256}")
            assertTrue(artifact.assetName.isNotBlank(), artifact.logicalArtifactId)
        }
    }

    /** Same shape the Kaggle notebook writes, including fields the app ignores. */
    @Test
    fun notebookCatalogBecomesALoadableLibrary() {
        val library = OrchestrationSpecialistCatalog.parse(
            """
            {"releaseRepository":"HereLiesAz/aive","releaseTag":"orchestration-utilities-v1","specialists":[
              {"specialistId":"orchestration:tool-router","mergedVariants":[{
                "logicalArtifactId":"orchestration:tool-router:int8","foundationModelId":"Qwen/Qwen2.5-0.5B-Instruct",
                "releaseRepository":"HereLiesAz/aive","releaseTag":"orchestration-utilities-v1",
                "assetName":"aive-orchestration-tool-router-int8.tar.gz","sha256":"${"a".repeat(64)}",
                "format":"onnx","precision":"int8","kind":"MergedModel","capabilities":["orchestration-utility","tool-router"]}],
               "scores":{"onnxInt8":{"test":0.97,"adversarial":1.0,"passed":true}}}]}
            """.trimIndent(),
        )

        assertTrue(library.hasSpecialist("orchestration:tool-router"))
        val plan = library.plan(
            "orchestration:tool-router",
            LocalModelRuntimeCapabilities(runtimeId = "android", supportedFormats = setOf("onnx"), supportedPrecisions = setOf("int8")),
        )
        assertEquals("aive-orchestration-tool-router-int8.tar.gz", assertIs<LocalModelLoadPlan.MergedModel>(plan).model.assetName)
    }

    /** The multi-task release: every passing role points at the same archive. */
    @Test
    fun rolesCanShareOneArtifact() {
        val artifact = """{"logicalArtifactId":"orchestration:utilities:int8","foundationModelId":"Qwen/Qwen2.5-0.5B-Instruct",
            "releaseRepository":"HereLiesAz/aive","releaseTag":"orchestration-utilities-v1",
            "assetName":"aive-orchestration-utilities-int8.tar.gz","sha256":"${"c".repeat(64)}",
            "format":"onnx","precision":"int8","kind":"MergedModel"}"""
        val library = OrchestrationSpecialistCatalog.parse(
            """{"specialists":[
              {"specialistId":"orchestration:tool-router","mergedVariants":[$artifact]},
              {"specialistId":"orchestration:completion-gate","mergedVariants":[$artifact]}]}""",
        )
        val runtime = LocalModelRuntimeCapabilities(runtimeId = "desktop", supportedFormats = setOf("onnx"), supportedPrecisions = setOf("int8"))
        val models = listOf("orchestration:tool-router", "orchestration:completion-gate").map {
            assertIs<LocalModelLoadPlan.MergedModel>(library.plan(it, runtime)).model
        }
        assertEquals(1, models.distinct().size)
        assertEquals(1, library.allArtifacts().size)
    }

    /** Both shapes in one catalog: the runtime's adapter support decides which one loads. */
    @Test
    fun runtimeChoosesBetweenMergedAndSharedBaseAdapter() {
        fun artifact(id: String, kind: String, fmt: String, extra: String = "") =
            """{"logicalArtifactId":"$id","foundationModelId":"Qwen/Qwen2.5-0.5B-Instruct","releaseRepository":"HereLiesAz/aive",
               "releaseTag":"orchestration-utilities-v1","assetName":"$id.bin","sha256":"${"d".repeat(64)}",
               "format":"$fmt","precision":"int8","kind":"$kind"$extra}"""
        val library = OrchestrationSpecialistCatalog.parse(
            """{"specialists":[{"specialistId":"orchestration:tool-router",
                "mergedVariants":[${artifact("orchestration:utilities:int8", "MergedModel", "onnx")}],
                "sharedBaseVariants":[${artifact("orchestration:base:int8", "SharedBase", "onnx")}],
                "adapter":${artifact("orchestration:tool-router:lora:v1", "Adapter", "safetensors", ",\"adapterId\":\"tool-router-v1\"")}}]}""",
        )
        val merged = LocalModelRuntimeCapabilities(runtimeId = "desktop", supportedFormats = setOf("onnx"), supportedPrecisions = setOf("int8"))
        val adapters = merged.copy(supportedFormats = setOf("onnx", "safetensors"), supportsSharedBaseAdapters = true)

        assertIs<LocalModelLoadPlan.MergedModel>(library.plan("orchestration:tool-router", merged))
        val plan = assertIs<LocalModelLoadPlan.SharedBaseAdapter>(library.plan("orchestration:tool-router", adapters))
        assertEquals("tool-router-v1", plan.adapter.adapterId)
    }

    @Test
    fun rejectsIdsOutsideTheOrchestrationFamily() {
        assertFailsWith<IllegalArgumentException> {
            OrchestrationSpecialistCatalog.parse(
                """{"specialists":[{"specialistId":"memory:noun-tagger","mergedVariants":[]}]}""",
            )
        }
    }

    private fun appArtifact(id: String, kind: String, fmt: String, extra: String = "") =
        """{"logicalArtifactId":"$id","foundationModelId":"Qwen/Qwen2.5-0.5B-Instruct","releaseRepository":"HereLiesAz/aive",
           "releaseTag":"app-integration-v1","assetName":"$id.bin","sha256":"${"e".repeat(64)}",
           "format":"$fmt","precision":"${if (kind == "Adapter") "fp16" else "int8"}","kind":"$kind"$extra}"""

    private val appCatalog = """{"specialists":[],"appIntegration":{"specialistId":"app-integration:assistant",
        "sharedBaseVariants":[${appArtifact("orchestration:base:v4:int8", "SharedBase", "onnx")}],
        "adapter":${appArtifact("app-integration:assistant:lora", "Adapter", "safetensors", ",\"adapterId\":\"app-integration-v1\",\"sizeBytes\":18874368")},
        "scores":{"gate1":{"F1":0.97,"F3":{"score":0.931}},"gate2":{"F1":0.95}}}}"""

    private val onnxAdapters = LocalModelRuntimeCapabilities(
        runtimeId = "desktop",
        supportedFormats = setOf("onnx", "safetensors"),
        supportedPrecisions = setOf("int8", "fp16"),
        supportsSharedBaseAdapters = true,
    )

    @Test
    fun theAppIntegrationAdapterIsAFieldOfItsOwn() {
        val release = assertNotNull(OrchestrationSpecialistCatalog.parseAppIntegration(appCatalog))
        assertEquals("app-integration-v1", release.adapter.adapterId)
        assertEquals(18_874_368L, release.adapterSizeBytes)
        assertEquals("gate 1 worst family 0.93, gate 2 worst family 0.95", release.gateSummary)
        val plan = assertNotNull(release.plan(onnxAdapters))
        assertEquals("orchestration:base:v4:int8", plan.base.logicalArtifactId)
        // The orchestration library does not hold it: no orchestration role can reach the adapter.
        assertTrue(OrchestrationSpecialistCatalog.parse(appCatalog).allSpecialists().isEmpty())
        // A runtime that cannot feed adapter tensors has nothing to load.
        assertEquals(null, release.plan(onnxAdapters.copy(supportsSharedBaseAdapters = false)))
        assertTrue(release.artifacts(onnxAdapters.copy(supportsSharedBaseAdapters = false)).isEmpty())
        assertEquals(listOf("orchestration:base:v4:int8", "app-integration:assistant:lora"), release.artifacts(onnxAdapters).map { it.logicalArtifactId })
    }

    @Test
    fun theAppIntegrationEntryRoundTripsThroughTheCatalogShape() {
        val release = assertNotNull(OrchestrationSpecialistCatalog.parseAppIntegration(appCatalog))
        val again = assertNotNull(
            OrchestrationSpecialistCatalog.parseAppIntegration(OrchestrationSpecialistCatalog.encodeAppIntegration(release)),
        )
        assertEquals(release.copy(gateSummary = null), again)
        // A catalog with no field has no release, and an empty catalog still parses.
        assertEquals(null, OrchestrationSpecialistCatalog.parseAppIntegration("""{"specialists":[]}"""))
    }

    @Test
    fun unknownIdsAreStillRefusedAndTheAppIntegrationIdHasOnePlace() {
        // The orchestration check is not loosened: the app-integration id inside `specialists` is refused...
        assertFailsWith<IllegalArgumentException> {
            OrchestrationSpecialistCatalog.parse(
                """{"specialists":[{"specialistId":"app-integration:assistant","mergedVariants":[]}]}""",
            )
        }
        // ...and the field takes no other id, nor an entry without an adapter and a shared base.
        assertFailsWith<IllegalArgumentException> {
            OrchestrationSpecialistCatalog.parseAppIntegration(appCatalog.replace("\"specialistId\":\"app-integration:assistant\"", "\"specialistId\":\"memory:noun-tagger\""))
        }
        assertFailsWith<IllegalArgumentException> {
            OrchestrationSpecialistCatalog.parseAppIntegration(
                """{"appIntegration":{"specialistId":"app-integration:assistant","sharedBaseVariants":[],
                    "adapter":${appArtifact("app-integration:assistant:lora", "Adapter", "safetensors", ",\"adapterId\":\"v1\"")}}}""",
            )
        }
        // An adapter listed as a merged model is refused too.
        assertFailsWith<IllegalArgumentException> {
            OrchestrationSpecialistCatalog.parseAppIntegration(appCatalog.replace("\"kind\":\"Adapter\"", "\"kind\":\"MergedModel\""))
        }
    }

    @Test
    fun theInstallStateReadsWhichArtifactsAreThere() {
        val release = OrchestrationSpecialistCatalog.parseAppIntegration(appCatalog)
        val state = { have: Set<String> ->
            AppIntegrationInstallState.of(release, onnxAdapters) { it.logicalArtifactId in have }
        }
        assertEquals(AppIntegrationInstallState.NotReleased, AppIntegrationInstallState.of(null, onnxAdapters) { true })
        assertEquals(
            AppIntegrationInstallState.NotReleased,
            AppIntegrationInstallState.of(release, onnxAdapters.copy(supportsSharedBaseAdapters = false)) { true },
        )
        assertEquals(AppIntegrationInstallState.NotInstalled, state(emptySet()))
        assertEquals(
            AppIntegrationInstallState.Partial("app-integration:assistant:lora.bin"),
            state(setOf("orchestration:base:v4:int8")),
        )
        assertEquals(
            AppIntegrationInstallState.Partial("orchestration:base:v4:int8.bin"),
            state(setOf("app-integration:assistant:lora")),
        )
        assertEquals(AppIntegrationInstallState.Installed, state(setOf("orchestration:base:v4:int8", "app-integration:assistant:lora")))
    }
}
