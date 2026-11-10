package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.Sha256
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Archive extraction is platform-specific; every returned entry must already have passed ZIP CRC checks. */
fun interface AzphaltArchiveReader {
    suspend fun read(bytes: ByteArray): Map<String, ByteArray>
}

/** Ed25519 is platform-specific, but the trust-chain policy is common. */
fun interface AzphaltEd25519Verifier {
    suspend fun verify(publicKeySpki: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

@Serializable
data class AzphaltSignature(
    val alg: String,
    val publicKey: String,
    val keyId: String? = null,
    val signature: String,
    val countersignature: AzphaltCountersignature? = null,
)

@Serializable
data class AzphaltCountersignature(
    val publicKey: String,
    val signature: String,
    val keyId: String? = null,
    val countersignature: AzphaltCountersignature? = null,
)

data class AzphaltPackageVerification(
    val packageContents: VerifiedAzphaltPackage,
    val trusted: Boolean,
    val trustReason: String,
    val publisherChanged: Boolean,
    val pinnedPublisherKey: String? = null,
)

interface AzphaltPublisherPinStore {
    suspend fun keyFor(packageId: String): String?
    suspend fun pin(packageId: String, publicKey: String)
}

class SettingsAzphaltPublisherPinStore(
    private val settings: Settings = Settings(),
    private val storageKey: String = DEFAULT_STORAGE_KEY,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AzphaltPublisherPinStore {
    private val mutex = Mutex()

    override suspend fun keyFor(packageId: String): String? = mutex.withLock { readUnlocked()[packageId] }

    override suspend fun pin(packageId: String, publicKey: String) {
        mutex.withLock {
            settings.putString(storageKey, json.encodeToString(MapSerializer, readUnlocked() + (packageId to publicKey)))
        }
    }

    private fun readUnlocked(): Map<String, String> = settings.getStringOrNull(storageKey)
        ?.let { json.decodeFromString(MapSerializer, it) }
        .orEmpty()

    companion object {
        const val DEFAULT_STORAGE_KEY = "haive.azphalt.publisher-pins.v1"
        private val MapSerializer = kotlinx.serialization.builtins.MapSerializer(
            kotlinx.serialization.serializer<String>(),
            kotlinx.serialization.serializer<String>(),
        )
    }
}

/**
 * Verifies the same boundary azphalt's reference `verifyAzp` + `verifyTrust` enforce.
 *
 * 1. archive paths are safe and unique,
 * 2. every manifest payload digest matches SHA-256,
 * 3. no undeclared payload exists except detached signature.json,
 * 4. an embedded Ed25519 signature must verify,
 * 5. identity trust is direct or via a cryptographically intact counter-signature chain,
 * 6. an existing package id may not silently rotate signer keys.
 */
class AzphaltPackageVerifier(
    private val archiveReader: AzphaltArchiveReader,
    private val ed25519: AzphaltEd25519Verifier,
    private val publisherPins: AzphaltPublisherPinStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    suspend fun verify(
        bytes: ByteArray,
        trustedRepositoryKeys: Collection<AzphaltSigningKey> = emptyList(),
    ): AzphaltPackageVerification {
        require(bytes.isNotEmpty()) { "Azphalt package is empty" }
        val entries = archiveReader.read(bytes)
        require(entries.isNotEmpty()) { "Azphalt package contains no entries" }
        entries.keys.forEach { path ->
            require(AzphaltWorkflowPackageInstaller.isSafePackagePath(path)) { "Unsafe package path $path" }
        }
        val manifestBytes = requireNotNull(entries["manifest.json"]) { "azp: manifest.json is missing" }
        val manifest = try {
            json.decodeFromString<AzphaltManifest>(manifestBytes.decodeToString())
        } catch (failure: Exception) {
            throw IllegalArgumentException("azp: manifest.json is not valid Azphalt JSON: ${failure.message}", failure)
        }
        // Ids and versions become directory names on some hosts ("..", "a/b"); reject on the shared repository grammar.
        AzphaltRepositoryClient.requirePackageId(manifest.id)
        AzphaltRepositoryClient.requireVersion(manifest.version)
        require(manifest.files.isNotEmpty()) { "azp: manifest.files is missing or empty" }

        val payload = entries.filterKeys { it != "manifest.json" && it != "signature.json" }
        require("LICENSE" in manifest.files) { "azp: LICENSE is not listed in manifest.files" }
        require("LICENSE" in payload) { "azp: LICENSE payload is missing" }
        require("signature.json" !in manifest.files) { "azp: detached signature.json must not be listed in manifest.files" }
        manifest.files.forEach { (path, expected) ->
            require(AzphaltWorkflowPackageInstaller.isSafePackagePath(path)) { "Unsafe manifest path $path" }
            val data = requireNotNull(payload[path]) { "Missing payload for $path" }
            val actual = "sha256-${Sha256.hex(data)}"
            require(actual.equals(expected, ignoreCase = true)) { "Digest mismatch: $path" }
        }
        val undeclared = payload.keys - manifest.files.keys
        require(undeclared.isEmpty()) { "Unlisted payload entries: ${undeclared.sorted().joinToString()}" }

        val signatureBytes = entries["signature.json"]
        val signature = signatureBytes?.let { raw ->
            try {
                json.decodeFromString<AzphaltSignature>(raw.decodeToString())
            } catch (failure: Exception) {
                throw IllegalArgumentException("signature.json is malformed: ${failure.message}", failure)
            }
        }
        val signed = signature != null
        val signerKey = signature?.publicKey
        val trust = if (signature == null) {
            TrustVerdict(false, "This add-on is not signed, so there is no publisher to trust")
        } else {
            require(signature.alg.equals("ed25519", ignoreCase = true)) { "signature algorithm must be ed25519" }
            val publicKey = Base64.decode(signature.publicKey)
            val signatureRaw = Base64.decode(signature.signature)
            require(ed25519.verify(publicKey, manifestBytes, signatureRaw)) { "Package signature verification failed" }
            verifyTrustChain(signature, trustedRepositoryKeys.map { it.publicKey }.toSet())
        }

        val pinnedKey = publisherPins.keyFor(manifest.id)
        val publisherChanged = pinnedKey != null && signerKey != pinnedKey
        return AzphaltPackageVerification(
            packageContents = VerifiedAzphaltPackage(
                manifest = manifest,
                payload = payload,
                signed = signed,
                signerPublicKey = signerKey,
            ),
            trusted = trust.trusted,
            trustReason = trust.reason,
            publisherChanged = publisherChanged,
            pinnedPublisherKey = pinnedKey,
        )
    }

    suspend fun approvePublisher(verification: AzphaltPackageVerification) {
        val signer = verification.packageContents.signerPublicKey ?: return
        publisherPins.pin(verification.packageContents.manifest.id, signer)
    }

    private suspend fun verifyTrustChain(
        signature: AzphaltSignature,
        trustedKeys: Set<String>,
    ): TrustVerdict {
        if (signature.publicKey in trustedKeys) return TrustVerdict(true, "Signed by a publisher the Store trusts")
        var vouchedKey = signature.publicKey
        var counter = signature.countersignature
        var depth = 0
        while (counter != null) {
            depth += 1
            require(depth <= MAX_CHAIN_DEPTH) { "Counter-signature chain exceeds $MAX_CHAIN_DEPTH hops" }
            val counterKey = Base64.decode(counter.publicKey)
            val vouchedKeyBytes = Base64.decode(vouchedKey)
            val counterSignature = Base64.decode(counter.signature)
            require(ed25519.verify(counterKey, vouchedKeyBytes, counterSignature)) {
                "Counter-signature invalid at hop $depth"
            }
            if (counter.publicKey in trustedKeys) {
                return TrustVerdict(
                    true,
                    if (depth == 1) "Signed by a publisher the Store vouches for" else "Signed by a publisher the Store vouches for, through $depth steps",
                )
            }
            vouchedKey = counter.publicKey
            counter = counter.countersignature
        }
        return TrustVerdict(false, "The signature is genuine, but the Store does not vouch for this publisher")
    }

    private data class TrustVerdict(val trusted: Boolean, val reason: String)

    private companion object {
        const val MAX_CHAIN_DEPTH = 10
    }
}

private object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(value: String): ByteArray {
        val clean = value.filterNot(Char::isWhitespace)
        require(clean.length % 4 == 0) { "Invalid base64 length" }
        val output = ArrayList<Byte>(clean.length * 3 / 4)
        var index = 0
        while (index < clean.length) {
            val c0 = sextet(clean[index])
            val c1 = sextet(clean[index + 1])
            val c2 = if (clean[index + 2] == '=') -1 else sextet(clean[index + 2])
            val c3 = if (clean[index + 3] == '=') -1 else sextet(clean[index + 3])
            output += ((c0 shl 2) or (c1 ushr 4)).toByte()
            if (c2 >= 0) output += (((c1 and 0x0f) shl 4) or (c2 ushr 2)).toByte()
            if (c3 >= 0) output += (((c2 and 0x03) shl 6) or c3).toByte()
            index += 4
        }
        return output.toByteArray()
    }

    private fun sextet(char: Char): Int {
        val index = ALPHABET.indexOf(char)
        require(index >= 0) { "Invalid base64 character" }
        return index
    }
}
