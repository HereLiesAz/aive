package com.hereliesaz.geministrator.mesh

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class MeshRoomCredentials(
    val relayBaseUrl: String,
    val roomId: String,
    val encryptionKey: ByteArray,
    val relayAuthToken: String,
) {
    init {
        require(relayBaseUrl.startsWith("https://") || relayBaseUrl.startsWith("http://")) {
            "Mesh relay URL must be HTTP(S)"
        }
        require(roomId.isNotBlank())
        require(encryptionKey.size == 32) { "Mesh room key must be 256 bits" }
        require(relayAuthToken.isNotBlank())
    }
}

@Serializable
data class MeshPairingInvitation(
    val version: Int = 1,
    val relayBaseUrl: String,
    val roomId: String,
    val encryptionKeyBase64: String,
    val relayAuthToken: String,
)

data class MeshSealedPayload(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

interface MeshCipher {
    suspend fun seal(plaintext: ByteArray, associatedData: ByteArray): MeshSealedPayload

    suspend fun open(payload: MeshSealedPayload, associatedData: ByteArray): ByteArray
}

internal expect fun platformMeshCipherOrNull(key: ByteArray): MeshCipher?

internal expect fun platformSecureRandomBytesOrNull(size: Int): ByteArray?

object MeshPairing {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createInvitation(
        relayBaseUrl: String,
        roomId: String,
    ): MeshPairingInvitation {
        val key = requireNotNull(platformSecureRandomBytesOrNull(32)) {
            "Secure mesh pairing is unavailable on this platform"
        }
        val relayToken = requireNotNull(platformSecureRandomBytesOrNull(32)) {
            "Secure mesh pairing is unavailable on this platform"
        }
        return MeshPairingInvitation(
            relayBaseUrl = relayBaseUrl.trimEnd('/'),
            roomId = roomId,
            encryptionKeyBase64 = Base64.UrlSafe.encode(key),
            relayAuthToken = Base64.UrlSafe.encode(relayToken),
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun credentials(invitation: MeshPairingInvitation): MeshRoomCredentials {
        require(invitation.version == 1) { "Unsupported mesh pairing invitation version" }
        return MeshRoomCredentials(
            relayBaseUrl = invitation.relayBaseUrl.trimEnd('/'),
            roomId = invitation.roomId,
            encryptionKey = Base64.UrlSafe.decode(invitation.encryptionKeyBase64),
            relayAuthToken = invitation.relayAuthToken,
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(invitation: MeshPairingInvitation): String =
        Base64.UrlSafe.encode(json.encodeToString(MeshPairingInvitation.serializer(), invitation).encodeToByteArray())

    @OptIn(ExperimentalEncodingApi::class)
    fun decode(encoded: String): MeshPairingInvitation =
        json.decodeFromString(
            MeshPairingInvitation.serializer(),
            Base64.UrlSafe.decode(encoded).decodeToString(),
        )
}

fun MeshRoomCredentials.cipher(): MeshCipher =
    requireNotNull(platformMeshCipherOrNull(encryptionKey)) {
        "Encrypted compute mesh is unavailable on this platform"
    }
