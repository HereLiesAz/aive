package com.hereliesaz.geministrator.azphalt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AzphaltModelManifestTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun allAdvertisedModelAssetKindsDecodeAndClassify() {
        val types = listOf("onnx", "tflite", "litert", "sherpa-bundle", "model", "task", "vosk-bundle")
        val assetsJson = types.mapIndexed { index, type ->
            if (type.endsWith("-bundle")) {
                """{"type":"$type","role":"speech-to-text","files":[{"name":"member-$index.bin","path":"assets/member-$index.bin"}]}"""
            } else {
                """{"type":"$type","role":"object-detection","path":"assets/model-$index.bin"}"""
            }
        }.joinToString(",")

        val manifest = json.decodeFromString<AzphaltManifest>(
            """
            {
              "azphalt":"0.1",
              "id":"com.example.models",
              "name":"Models",
              "version":"1.0.0",
              "kind":"asset",
              "license":"MIT",
              "compat":">=0.1",
              "files":{"LICENSE":"sha256-a"},
              "assets":[$assetsJson]
            }
            """.trimIndent(),
        )

        val assets = manifest.assets.orEmpty()
        assertEquals(types, assets.map(AzphaltAssetEntry::type))
        assertTrue(assets.all(AzphaltAssetEntry::isModelAsset))
    }

    @Test
    fun modelMetadataDecodesRemoteFilesIoRequirementsAndLicense() {
        val manifest = json.decodeFromString<AzphaltManifest>(
            """
            {
              "azphalt":"0.1",
              "id":"com.example.depth",
              "name":"Depth",
              "version":"1.0.0",
              "kind":"asset",
              "license":"MIT",
              "compat":">=0.1",
              "files":{"LICENSE":"sha256-a"},
              "assets":[{
                "type":"litert",
                "role":"depth",
                "remoteUrl":"https://example.test/depth.tflite",
                "checksum":"sha256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "byteSize":1234,
                "io":{"inputs":[{"name":"image","shape":[1,3,256,256],"dtype":"float32"}]},
                "requirements":{"runtime":"litert","quantization":"int8","minRamMB":512},
                "modelLicense":{"spdx":"Apache-2.0","commercialUse":true}
              }]
            }
            """.trimIndent(),
        )

        val asset = manifest.assets.orEmpty().single()
        assertEquals("litert", asset.type)
        assertEquals("depth", asset.role)
        assertEquals(1234L, asset.byteSize)
        assertTrue(asset.io != null)
        assertTrue(asset.requirements != null)
        assertTrue(asset.modelLicense != null)
    }
}
