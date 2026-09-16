package com.hereliesaz.geministrator.azphalt

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AzphaltPackageRuntimeTest {
    @Test
    fun boundedArchiveReaderInflatesEveryFile() = runBlocking {
        val archive = zipOf(
            "manifest.json" to "{\"id\":\"com.example\"}".encodeToByteArray(),
            "LICENSE" to "MIT".encodeToByteArray(),
            "workflows/release.json" to "{\"name\":\"Release\"}".encodeToByteArray(),
        )

        val entries = KmpZipAzphaltArchiveReader().read(archive)

        assertEquals(setOf("manifest.json", "LICENSE", "workflows/release.json"), entries.keys)
        assertContentEquals("MIT".encodeToByteArray(), entries.getValue("LICENSE"))
    }

    @Test
    fun boundedArchiveReaderRejectsUnsafePathBeforeReturningPayload() = runBlocking {
        val archive = zipOf("../manifest.json" to "{}".encodeToByteArray())

        assertFailsWith<IllegalArgumentException> {
            KmpZipAzphaltArchiveReader().read(archive)
        }
    }

    @Test
    fun boundedArchiveReaderRejectsInflationPastEntryBudget() = runBlocking {
        val archive = zipOf("payload.bin" to ByteArray(64) { it.toByte() })

        assertFailsWith<IllegalArgumentException> {
            KmpZipAzphaltArchiveReader(maxEntryBytes = 32).read(archive)
        }
    }

    @Test
    fun cryptographyVerifierAcceptsValidEd25519AndRejectsTampering() = runBlocking {
        val provider = CryptographyProvider.Default
        val algorithm = provider.get(EdDSA)
        val keys = algorithm.keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
        val publicKey = keys.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.DER)
        val message = "signed azphalt manifest".encodeToByteArray()
        val signature = keys.privateKey.signatureGenerator().generateSignature(message)
        val verifier = CryptographyAzphaltEd25519Verifier(provider)

        assertTrue(verifier.verify(publicKey, message, signature))
        assertFalse(verifier.verify(publicKey, "tampered manifest".encodeToByteArray(), signature))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        val zip = ZipOutputStream(output)
        try {
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data, 0, data.size)
                zip.closeEntry()
            }
        } finally {
            zip.close()
        }
        return output.toByteArray()
    }
}
