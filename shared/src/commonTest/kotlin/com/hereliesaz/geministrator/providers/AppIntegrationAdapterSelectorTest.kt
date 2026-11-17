package com.hereliesaz.geministrator.providers

import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.inference.LocalModelRuntimeCapabilities
import com.hereliesaz.geministrator.orchestration.AppIntegrationRelease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The adapter-or-zeros decision the ONNX small-task generators make, over fake files and a fake loader. */
class AppIntegrationAdapterSelectorTest {
    private fun artifact(id: String, kind: LocalModelArtifactKind, sha: String, adapterId: String? = null) =
        LocalModelArtifactDescriptor(
            logicalArtifactId = id,
            foundationModelId = "Qwen/Qwen2.5-0.5B-Instruct",
            releaseRepository = "HereLiesAz/aive",
            releaseTag = "app-integration-v1",
            assetName = "$id.bin",
            sha256 = sha.repeat(64),
            format = if (kind == LocalModelArtifactKind.Adapter) "safetensors" else "onnx",
            precision = if (kind == LocalModelArtifactKind.Adapter) "fp16" else "int8",
            kind = kind,
            adapterId = adapterId,
        )

    private fun release(adapterSha: String = "a") = AppIntegrationRelease(
        sharedBaseVariants = listOf(artifact("orchestration:base:v4:int8", LocalModelArtifactKind.SharedBase, "b")),
        adapter = artifact("app-integration:assistant:lora", LocalModelArtifactKind.Adapter, adapterSha, "app-integration-v1"),
    )

    private val runtime = LocalModelRuntimeCapabilities(
        runtimeId = "fake",
        supportedFormats = setOf("onnx", "safetensors"),
        supportedPrecisions = setOf("int8", "fp16"),
        supportsSharedBaseAdapters = true,
    )

    private class FakeAdapter(val trainedFor: String?, val sha: String) : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    /** What is on the fake disk: installed files by logical id, and what the loader does with them. */
    private class Disk(
        var release: AppIntegrationRelease?,
        val installed: MutableSet<String> = mutableSetOf(),
        var trainedFor: String? = "orchestration:base:v4:int8",
        var failToLoad: Boolean = false,
    ) {
        val loads = mutableListOf<String>()
        val loaded = mutableListOf<FakeAdapter>()
    }

    private fun selector(disk: Disk, runtime: LocalModelRuntimeCapabilities = this.runtime) =
        AppIntegrationAdapterSelector<String, FakeAdapter>(
            release = { disk.release },
            runtime = runtime,
            installedAdapter = { if (it.logicalArtifactId in disk.installed) "/files/${it.logicalArtifactId}" else null },
            installedBase = { if (it.logicalArtifactId in disk.installed) "/root/${it.logicalArtifactId}" else null },
            load = { path ->
                disk.loads += path
                if (disk.failToLoad) error("bad header")
                FakeAdapter(disk.trainedFor, path).also { disk.loaded += it }
            },
            trainedFor = { it.trainedFor },
        )

    private val both = mutableSetOf("orchestration:base:v4:int8", "app-integration:assistant:lora")

    @Test
    fun withNothingReleasedItRunsThePlainBaseAndSaysNothing() {
        val selector = selector(Disk(release = null))
        val chosen = assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select())
        assertNull(chosen.note)
        assertNull(selector.adapterNote())
        assertFalse(selector.available())
    }

    @Test
    fun releasedButNotInstalledIsPlainAndSilent() {
        val selector = selector(Disk(release = release()))
        assertNull(assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select()).note)
        assertNull(selector.adapterNote())
        assertFalse(selector.available())
    }

    @Test
    fun anInstalledAdapterOnItsInstalledBaseFeedsItsTensors() {
        val disk = Disk(release = release(), installed = both)
        val selector = selector(disk)
        assertTrue(selector.available())
        assertEquals("The app-integration adapter is installed and is used from the next task.", selector.adapterNote())

        val chosen = assertIs<AppIntegrationAdapterSelector.Selection.Adapter<String, FakeAdapter>>(selector.select())
        assertEquals("/root/orchestration:base:v4:int8", chosen.baseRoot)
        assertEquals("/files/app-integration:assistant:lora", chosen.adapter.sha)
        assertEquals("Using the app-integration adapter.", selector.adapterNote())

        // The second call reuses the loaded tensors instead of reading the file again.
        assertIs<AppIntegrationAdapterSelector.Selection.Adapter<String, FakeAdapter>>(selector.select())
        assertEquals(1, disk.loads.size)
    }

    @Test
    fun theCacheKeyCarriesTheAdapterIdAndShaSoANewReleaseReprimes() {
        val old = AppIntegrationAdapterSelector.cacheKey(release("a").adapter, "/m/model.onnx")
        val new = AppIntegrationAdapterSelector.cacheKey(release("c").adapter, "/m/model.onnx")
        assertNotEquals(old, new)
        assertTrue("app-integration-v1@" in old && "a".repeat(64) in old)
        assertTrue("/m/model.onnx" in old)

        val disk = Disk(release = release("a"), installed = both)
        val selector = selector(disk)
        val first = assertIs<AppIntegrationAdapterSelector.Selection.Adapter<String, FakeAdapter>>(selector.select())
        assertEquals(old, first.cacheKey("/m/model.onnx"))

        // A new release (new SHA-256) loads the new file, frees the old tensors and changes the key.
        disk.release = release("c")
        val second = assertIs<AppIntegrationAdapterSelector.Selection.Adapter<String, FakeAdapter>>(selector.select())
        assertEquals(new, second.cacheKey("/m/model.onnx"))
        assertEquals(2, disk.loads.size)
        assertTrue(disk.loaded[0].closed)
    }

    @Test
    fun anAdapterWithoutItsBaseRunsPlainAndSaysWhy() {
        val disk = Disk(release = release(), installed = mutableSetOf("app-integration:assistant:lora"))
        val selector = selector(disk)
        val plain = assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select())
        assertTrue("base model is not" in plain.note.orEmpty())
        assertEquals(plain.note, selector.adapterNote())
        assertFalse(selector.available())
        assertTrue(disk.loads.isEmpty())
    }

    @Test
    fun anAdapterBuiltForAnotherBaseFallsBackToZerosAndIsNotReadAgain() {
        val disk = Disk(release = release(), installed = both, trainedFor = "orchestration:base:v3:int8")
        val selector = selector(disk)
        val plain = assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select())
        assertTrue("trained for orchestration:base:v3:int8, not orchestration:base:v4:int8" in plain.note.orEmpty())
        assertTrue(disk.loaded.single().closed)
        assertEquals(plain.note, selector.adapterNote())
        // Remembered by SHA-256: the second call does not parse the file again.
        assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select())
        assertEquals(1, disk.loads.size)
        // A new release is looked at afresh.
        disk.release = release("c")
        disk.trainedFor = "orchestration:base:v4:int8"
        assertIs<AppIntegrationAdapterSelector.Selection.Adapter<String, FakeAdapter>>(selector.select())
    }

    @Test
    fun anAdapterThatDoesNotSayWhatItWasTrainedForIsAccepted() {
        val selector = selector(Disk(release = release(), installed = both, trainedFor = null))
        assertIs<AppIntegrationAdapterSelector.Selection.Adapter<String, FakeAdapter>>(selector.select())
    }

    @Test
    fun anUnreadableAdapterFallsBackToZerosWithAPlainReason() {
        val selector = selector(Disk(release = release(), installed = both, failToLoad = true))
        val plain = assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select())
        assertTrue("could not be read (bad header)" in plain.note.orEmpty())
        assertTrue("plain model" in plain.note.orEmpty())
    }

    @Test
    fun theCallerCanRejectAnAdapterThatLacksInputs() {
        val disk = Disk(release = release(), installed = both)
        val selector = selector(disk)
        val chosen = assertIs<AppIntegrationAdapterSelector.Selection.Adapter<String, FakeAdapter>>(selector.select())
        selector.reject(chosen.plan.adapter, "The app-integration adapter lacks 4 input(s) the base model needs, so small tasks run on the plain model.")
        assertTrue(disk.loaded.single().closed)
        assertTrue("lacks 4 input(s)" in selector.adapterNote().orEmpty())
        assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select())
    }

    @Test
    fun removingTheAdapterReturnsToThePlainBaseAndFreesTheTensors() {
        val disk = Disk(release = release(), installed = both)
        val selector = selector(disk)
        assertIs<AppIntegrationAdapterSelector.Selection.Adapter<String, FakeAdapter>>(selector.select())
        disk.installed.remove("app-integration:assistant:lora")
        assertNull(assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select()).note)
        assertTrue(disk.loaded.single().closed)
        assertNull(selector.adapterNote())
    }

    @Test
    fun aRuntimeThatCannotRunAdaptersStaysPlainAndSaysSo() {
        val selector = selector(Disk(release = release(), installed = both), runtime.copy(supportsSharedBaseAdapters = false))
        val plain = assertIs<AppIntegrationAdapterSelector.Selection.Plain>(selector.select())
        assertTrue("does not fit this runtime" in plain.note.orEmpty())
    }
}
