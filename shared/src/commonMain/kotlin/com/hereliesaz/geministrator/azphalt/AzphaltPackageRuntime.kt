package com.hereliesaz.geministrator.azphalt

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.zip.ZipInputStream

/**
 * Cross-platform `.azp` archive reader with explicit limits for untrusted store input.
 *
 * Every entry is read through EOF so kmp-zip performs its CRC/authentication check. Directory
 * entries are drained and omitted from the returned payload. Duplicate paths are rejected instead
 * of allowing a later ZIP entry to shadow an earlier manifest or payload.
 */
class KmpZipAzphaltArchiveReader(
    private val maxArchiveBytes: Int = DEFAULT_MAX_ARCHIVE_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val maxTotalInflatedBytes: Long = DEFAULT_MAX_TOTAL_INFLATED_BYTES,
    private val maxPathLength: Int = DEFAULT_MAX_PATH_LENGTH,
) : AzphaltArchiveReader {
    init {
        require(maxArchiveBytes > 0)
        require(maxEntries > 0)
        require(maxEntryBytes > 0)
        require(maxTotalInflatedBytes >= maxEntryBytes)
        require(maxPathLength > 0)
    }

    override suspend fun read(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size <= maxArchiveBytes) {
            "Azphalt package exceeds compressed size limit of $maxArchiveBytes bytes"
        }

        val entries = linkedMapOf<String, ByteArray>()
        val seenPaths = mutableSetOf<String>()
        var entryCount = 0
        var totalInflated = 0L
        val zip = ZipInputStream(bytes)
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= maxEntries) {
                    "Azphalt package exceeds entry limit of $maxEntries"
                }

                val path = entry.name
                require(path.length <= maxPathLength) {
                    "Azphalt package path exceeds $maxPathLength characters"
                }
                if (!entry.isDirectory) {
                    require(AzphaltWorkflowPackageInstaller.isSafePackagePath(path)) {
                        "Unsafe package path $path"
                    }
                    require(seenPaths.add(path)) { "Duplicate package path $path" }
                }
                if (entry.size >= 0L) {
                    require(entry.size <= maxEntryBytes) {
                        "Azphalt package entry $path exceeds size limit of $maxEntryBytes bytes"
                    }
                    require(totalInflated + entry.size <= maxTotalInflatedBytes) {
                        "Azphalt package exceeds total inflated size limit of $maxTotalInflatedBytes bytes"
                    }
                }

                val output = ByteArrayOutputStream(
                    if (entry.size in 0..Int.MAX_VALUE.toLong()) entry.size.toInt() else 8192,
                )
                val buffer = ByteArray(8192)
                var entryInflated = 0L
                while (true) {
                    val read = zip.read(buffer, 0, buffer.size)
                    if (read == -1) break
                    require(read > 0) { "ZIP reader made no progress for $path" }
                    entryInflated += read
                    totalInflated += read
                    require(entryInflated <= maxEntryBytes) {
                        "Azphalt package entry $path exceeds size limit of $maxEntryBytes bytes"
                    }
                    require(totalInflated <= maxTotalInflatedBytes) {
                        "Azphalt package exceeds total inflated size limit of $maxTotalInflatedBytes bytes"
                    }
                    output.write(buffer, 0, read)
                }

                if (entry.isDirectory) {
                    require(entryInflated == 0L) { "ZIP directory entry $path contains data" }
                } else {
                    entries[path] = output.toByteArray()
                }
            }
        } finally {
            zip.close()
        }
        return entries
    }

    companion object {
        const val DEFAULT_MAX_ARCHIVE_BYTES: Int = 64 * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES: Int = 512
        const val DEFAULT_MAX_ENTRY_BYTES: Long = 32L * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_INFLATED_BYTES: Long = 128L * 1024 * 1024
        const val DEFAULT_MAX_PATH_LENGTH: Int = 1024
    }
}

/** Ed25519 verifier backed by cryptography-kotlin's target-appropriate implementation. */
class CryptographyAzphaltEd25519Verifier(
    private val provider: CryptographyProvider,
) : AzphaltEd25519Verifier {
    override suspend fun verify(
        publicKeySpki: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean = runCatching {
        val algorithm = provider.get(EdDSA)
        val publicKey = algorithm
            .publicKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArray(EdDSA.PublicKey.Format.DER, publicKeySpki)
        publicKey.signatureVerifier().tryVerifySignature(message, signature)
    }.getOrDefault(false)
}

/**
 * Platform-selected verifier. Android deliberately uses BouncyCastle so Ed25519 remains available
 * on Haive's API-26 minimum; desktop uses JDK and browser targets use WebCrypto.
 */
fun defaultAzphaltEd25519Verifier(): AzphaltEd25519Verifier =
    CryptographyAzphaltEd25519Verifier(platformAzphaltCryptographyProvider())

internal expect fun platformAzphaltCryptographyProvider(): CryptographyProvider
