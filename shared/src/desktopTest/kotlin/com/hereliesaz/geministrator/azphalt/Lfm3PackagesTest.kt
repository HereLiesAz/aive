package com.hereliesaz.geministrator.azphalt

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two LFM3 store packages (docs/azphalt-packages/lfm3-1-7b and lfm3-vl-2b): each is a model asset the app can list and
 * install, pinned to a Hugging Face commit by https URL, SHA-256 and exact size, with its licence shipped and its manifest
 * agreeing with the files.
 */
class Lfm3PackagesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/azphalt-packages") }.first { it.isDirectory }

    private fun manifest(slug: String): AzphaltManifest = json.decodeFromString(File(root, "$slug/manifest.json").readText())

    @Test
    fun theLicenceDigestMatchesTheFileShipped() {
        for (slug in listOf("lfm3-1-7b", "lfm3-vl-2b")) {
            val digest = MessageDigest.getInstance("SHA-256").digest(File(root, "$slug/LICENSE").readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("sha256-$digest", manifest(slug).files.getValue("LICENSE"), slug)
        }
    }

    @Test
    fun eachIsAPinnedRemoteModelAssetTheShopLists() {
        val expected = mapOf(
            "lfm3-1-7b" to Triple("LFM3-1.7B_mixed_int4_ekv2048.litertlm", 1_441_620_928L, "0400d8ffdb00e4bbe1209120e2c7baf05322a8c900839b0a94cc28f599011196"),
            "lfm3-vl-2b" to Triple("LFM3-VL-2B_int4.litertlm", 2_071_658_496L, "94ae3287c3fd09983dc549d16e81404fc0d8d47a37f4ccc3247b0b8e0a6959d0"),
        )
        for ((slug, pin) in expected) {
            val manifest = manifest(slug)
            assertEquals("asset", manifest.kind)
            assertTrue("com.hereliesaz.aive" in manifest.targetApps, slug)
            val asset = manifest.assets.orEmpty().single()
            assertEquals("model", asset.type)
            assertEquals("on-device-llm", asset.role)
            val url = assertNotNull(asset.remoteUrl)
            assertTrue(url.startsWith("https://huggingface.co/litert-community/"), url)
            assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(url), "pinned to a commit, not a branch: $url")
            assertTrue(url.endsWith("/${pin.first}"), url)
            assertEquals(pin.second, asset.byteSize)
            assertEquals(pin.third, azphaltSha256Hex(asset.checksum))
            val license = asset.modelLicense?.jsonObject
            assertNotNull(license)
            assertEquals("conditional", license.getValue("commercialUse").jsonPrimitive.content)
            assertTrue("10 million" in license.getValue("terms").jsonPrimitive.content)
            val summary = json.decodeFromString<AzphaltPackageSummary>(
                """{"id":"${manifest.id}","name":"${manifest.name}","version":"${manifest.version}","kind":"asset","types":["model"]}""",
            )
            assertTrue(summary.isModelAssetPackage() && summary.isShownInShop(), slug)
        }
    }

    @Test
    fun theParamsSayHowToTalkToEachModel() {
        val text = manifest("lfm3-1-7b").assets.orEmpty().single().params?.jsonObject
        assertNotNull(text)
        assertEquals("lfm3", text.getValue("chatFormat").jsonPrimitive.content)
        assertEquals("lfm3-xml", text.getValue("toolFormat").jsonPrimitive.content)
        assertEquals(2048, text.getValue("contextTokens").jsonPrimitive.int)
        assertEquals(listOf("text"), text.getValue("modalities").jsonArray.map { it.jsonPrimitive.content })

        val vision = manifest("lfm3-vl-2b").assets.orEmpty().single().params?.jsonObject
        assertNotNull(vision)
        assertEquals(listOf("text", "image"), vision.getValue("modalities").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("litert-lm", manifest("lfm3-vl-2b").assets.orEmpty().single().requirements?.jsonObject?.getValue("runtime")?.jsonPrimitive?.content)
    }
}
