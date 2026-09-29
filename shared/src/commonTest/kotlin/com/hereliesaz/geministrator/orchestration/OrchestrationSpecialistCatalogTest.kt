package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.inference.LocalModelLoadPlan
import com.hereliesaz.geministrator.inference.LocalModelRuntimeCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrchestrationSpecialistCatalogTest {
    @Test
    fun releasedCatalogParses() {
        OrchestrationSpecialistCatalog.released()
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
}
