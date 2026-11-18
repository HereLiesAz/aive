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

    @Test
    fun theMergedAppIntegrationModelIsPinnedFromItsRelease() {
        val model = LiteRtLmModelCatalog.adapterModel!!
        assertEquals("6f2ccf749a8ebab4a08a0d17396fe0df0bce9936da37b9f36c88fba159e0dab3", model.sha256)
        assertEquals(528_255_232L, model.sizeBytes)
        assertEquals("litertlm", model.format)
        assertTrue(model.downloadUrl.startsWith("https://github.com/HereLiesAz/aive/releases/download/app-integration-v1/"))
        assertTrue(model.downloadUrl.endsWith(model.fileName))
        assertEquals(model, LiteRtLmModelCatalog.byId(model.id))
        assertEquals(listOf(LiteRtLmModelCatalog.qwen25Instruct05b, model), LiteRtLmModelCatalog.all)
    }

    private val pinned = LiteRtLmModelCatalog.qwen25Instruct05b.copy(
        id = "litertlm:app-integration:q8",
        fileName = "app-integration_q8_ekv1280.litertlm",
        downloadUrl = "https://example.com/app-integration_q8_ekv1280.litertlm",
        sha256 = "f".repeat(64),
    )

    @Test
    fun theMergedModelIsPreferredOnlyWhenPinnedAndInstalled() {
        // Pinned and installed: it wins over the stock model.
        assertEquals(
            LiteRtLmModelChoice("/m/merged", adapterModel = true),
            LiteRtLmModelSelection.choose(pinned, { "/m/merged" }, { "/m/stock" }),
        )
        // Pinned but not installed: the stock model runs.
        assertEquals(
            LiteRtLmModelChoice("/m/stock", adapterModel = false),
            LiteRtLmModelSelection.choose(pinned, { null }, { "/m/stock" }),
        )
        // Not pinned: an installed file is never looked at.
        var asked = false
        assertEquals(
            LiteRtLmModelChoice("/m/stock", adapterModel = false),
            LiteRtLmModelSelection.choose(null, { asked = true; "/m/merged" }, { "/m/stock" }),
        )
        assertTrue(!asked)
        // Nothing installed: no model.
        assertEquals(null, LiteRtLmModelSelection.choose(pinned, { null }, { null }))
        assertEquals(null, LiteRtLmModelSelection.choose(null, { null }, { null }))
    }
}
