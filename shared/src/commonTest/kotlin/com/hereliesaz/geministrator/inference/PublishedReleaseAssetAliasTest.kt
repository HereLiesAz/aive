package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.memory.MemoryEpoch8LocalModelLibrary
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublishedReleaseAssetAliasTest {
    private val base = "https://github.com/HereLiesAz/aive/releases/download/memory-layer-epoch8/"

    @Test
    fun currentAssetNamesMapToTheNamesAlreadyPublishedRemotely() {
        assertEquals("haive-memory-layer-fp16-epoch8.tar.gz", publishedReleaseAssetName("aive-memory-layer-fp16-epoch8.tar.gz"))
        assertEquals(
            "haive-specialist_08_association_linker-onnx-epoch8.tar.gz",
            publishedReleaseAssetName("aive-specialist_08_association_linker-onnx-epoch8.tar.gz"),
        )
        assertEquals("haive-orch_planner-int8-epoch8.tar.gz.part001", publishedReleaseAssetName("aive-orch_planner-int8-epoch8.tar.gz.part001"))
        assertEquals(base + "haive-memory-layer-fp16-epoch8.tar.gz", publishedReleaseAssetUrl(base + "aive-memory-layer-fp16-epoch8.tar.gz"))
    }

    @Test
    fun assetsOutsideThePublishedFamiliesHaveNoAlias() {
        assertNull(publishedReleaseAssetName("aive-orchestration-tool-router-int8.tar.gz"))
        assertNull(publishedReleaseAssetUrl(base + "other.bin"))
        assertNull(publishedReleaseAssetUrl("not-a-url"))
    }

    @Test
    fun everyAssetTheCatalogDownloadsHasAnAliasWhenItIsInAPublishedFamily() {
        val library = MemoryEpoch8LocalModelLibrary.library
        val names = library.allSpecialists().flatMap { specialist ->
            specialist.sharedBaseVariants + specialist.mergedVariants + listOfNotNull(specialist.adapter)
        }.map { it.assetName }.distinct()
        assertEquals(true, names.isNotEmpty())
        names.forEach { name -> assertEquals(true, publishedReleaseAssetName(name) != null, name) }
    }

    @Test
    fun aMissingCurrentAssetFallsBackToThePublishedNameAndAPresentOneDoesNot() = runTest {
        val missing = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val present = HttpClient(MockEngine { respond("x", HttpStatusCode.PartialContent) })
        val url = base + "aive-memory-layer-fp16-epoch8.tar.gz"

        assertEquals(base + "haive-memory-layer-fp16-epoch8.tar.gz", resolveReleaseAssetUrl(missing, url))
        assertEquals(url, resolveReleaseAssetUrl(present, url))
        assertEquals(base + "other.bin", resolveReleaseAssetUrl(missing, base + "other.bin"))
    }
}
