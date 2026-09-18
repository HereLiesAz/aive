package com.hereliesaz.geministrator.mesh

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual fun platformMeshCipherOrNull(key: ByteArray): MeshCipher? =
    runCatching { JvmMeshCipher(key.copyOf()) }.getOrNull()

internal actual fun platformSecureRandomBytesOrNull(size: Int): ByteArray? =
    runCatching {
        require(size > 0)
        ByteArray(size).also(SecureRandom()::nextBytes)
    }.getOrNull()

private class JvmMeshCipher(
    private val key: ByteArray,
) : MeshCipher {
    init {
        require(key.size == 32)
    }

    override suspend fun seal(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): MeshSealedPayload {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(associatedData)
        return MeshSealedPayload(
            nonce = nonce,
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    override suspend fun open(
        payload: MeshSealedPayload,
        associatedData: ByteArray,
    ): ByteArray {
        require(payload.nonce.size == NONCE_BYTES) { "Invalid mesh nonce length" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, payload.nonce),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(payload.ciphertext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val NONCE_BYTES = 12
    }
}
