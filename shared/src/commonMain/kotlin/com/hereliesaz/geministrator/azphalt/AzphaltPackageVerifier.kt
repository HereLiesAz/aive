package com.hereliesaz.geministrator.azphalt

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
            TrustVerdict(false, "unsigned: no signer to trust")
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
        if (signature.publicKey in trustedKeys) return TrustVerdict(true, "signer key is directly trusted")
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
                    if (depth == 1) "signer counter-signed by trusted repository" else "signer trusted through $depth counter-signature hops",
                )
            }
            vouchedKey = counter.publicKey
            counter = counter.countersignature
        }
        return TrustVerdict(false, "signer key is not trusted by this repository")
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

private object Sha256 {
    private val initial = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    private val k = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun hex(input: ByteArray): String = digest(input).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8L
        val paddedSize = ((input.size + 9 + 63) / 64) * 64
        val data = ByteArray(paddedSize)
        input.copyInto(data)
        data[input.size] = 0x80.toByte()
        for (i in 0 until 8) data[paddedSize - 1 - i] = (bitLength ushr (i * 8)).toByte()

        val h = initial.copyOf()
        val w = IntArray(64)
        var offset = 0
        while (offset < data.size) {
            for (i in 0 until 16) {
                val p = offset + i * 4
                w[i] = ((data[p].toInt() and 0xff) shl 24) or
                    ((data[p + 1].toInt() and 0xff) shl 16) or
                    ((data[p + 2].toInt() and 0xff) shl 8) or
                    (data[p + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + k[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                hh = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            offset += 64
        }
        return ByteArray(32).also { out ->
            h.forEachIndexed { i, value ->
                out[i * 4] = (value ushr 24).toByte()
                out[i * 4 + 1] = (value ushr 16).toByte()
                out[i * 4 + 2] = (value ushr 8).toByte()
                out[i * 4 + 3] = value.toByte()
            }
        }
    }

    private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
}
