package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.Sha256
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AzphaltPackageIdValidationTest {
    private fun verifier(entries: Map<String, ByteArray>) = AzphaltPackageVerifier(
        archiveReader = object : AzphaltArchiveReader {
            override suspend fun read(bytes: ByteArray): Map<String, ByteArray> = entries
        },
        ed25519 = object : AzphaltEd25519Verifier {
            override suspend fun verify(publicKeySpki: ByteArray, message: ByteArray, signature: ByteArray) = true
        },
        publisherPins = SettingsAzphaltPublisherPinStore(MapSettings()),
    )

    private fun entries(id: String, version: String): Map<String, ByteArray> {
        val license = "MIT".encodeToByteArray()
        val manifest = """{"azphalt":"1.0","id":"$id","name":"n","version":"$version","kind":"asset","license":"MIT","compat":">=1.0","files":{"LICENSE":"sha256-${Sha256.hex(license)}"}}"""
        return mapOf("manifest.json" to manifest.encodeToByteArray(), "LICENSE" to license)
    }

    @Test
    fun dotSegmentIdsAndVersionsAreRejectedBeforeAnyInstallerSeesThem() = runBlocking {
        for (bad in listOf("..", ".", "../x", "a/b")) {
            assertFailsWith<IllegalArgumentException>("id $bad") { verifier(entries(bad, "1.0.0")).verify(byteArrayOf(1)) }
            assertFailsWith<IllegalArgumentException>("version $bad") { verifier(entries("ok.pkg", bad)).verify(byteArrayOf(1)) }
        }
    }
}
