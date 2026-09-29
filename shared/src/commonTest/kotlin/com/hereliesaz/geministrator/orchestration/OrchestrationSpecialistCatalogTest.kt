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

    @Test
    fun rejectsIdsOutsideTheOrchestrationFamily() {
        assertFailsWith<IllegalArgumentException> {
            OrchestrationSpecialistCatalog.parse(
                """{"specialists":[{"specialistId":"memory:noun-tagger","mergedVariants":[]}]}""",
            )
        }
    }
}
