package com.hereliesaz.geministrator.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiteRtLmModelCatalogTest {
    @Test
    fun theQwenEntryIsPinnedByRevisionSizeAndDigest() {
        val model = LiteRtLmModelCatalog.qwen25Instruct05b
        assertEquals("e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2", model.sha256)
        assertEquals(546_660_344L, model.sizeBytes)
        assertTrue("/resolve/49bfd323c25c9dae2118906e1f3ffd16e0729b13/" in model.downloadUrl)
        assertTrue(model.downloadUrl.endsWith(model.fileName))
        assertEquals(model, LiteRtLmModelCatalog.byId(model.id))
    }

    @Test
    fun idsAreUnique() {
        assertEquals(LiteRtLmModelCatalog.all.size, LiteRtLmModelCatalog.all.map { it.id }.toSet().size)
    }

    @Test
    fun capabilitiesDeclareStandaloneModelsOnly() {
        val caps = LiteRtLmRuntime.capabilities(LiteRtLmRuntime.ANDROID_RUNTIME_ID)
        assertEquals("android-litert-lm", caps.runtimeId)
        assertTrue("task" in caps.supportedFormats && "litertlm" in caps.supportedFormats)
        assertTrue(!caps.supportsSharedBaseAdapters)
    }
}
