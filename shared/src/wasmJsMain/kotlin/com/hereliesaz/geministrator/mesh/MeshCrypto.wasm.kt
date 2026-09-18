package com.hereliesaz.geministrator.mesh

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await

private external object HaiveMeshCrypto : JsAny {
    val ready: Boolean

    fun randomBase64(size: Int): String

    fun sealBase64(
        keyBase64: String,
        plaintextBase64: String,
        associatedDataBase64: String,
    ): Promise<JsString>

    fun openBase64(
        keyBase64: String,
        nonceBase64: String,
        ciphertextBase64: String,
        associatedDataBase64: String,
    ): Promise<JsString>
}

@OptIn(ExperimentalEncodingApi::class, ExperimentalWasmJsInterop::class)
internal actual fun platformMeshCipherOrNull(key: ByteArray): MeshCipher? =
    if (HaiveMeshCrypto.ready) BrowserMeshCipher(Base64.UrlSafe.encode(key)) else null

@OptIn(ExperimentalEncodingApi::class, ExperimentalWasmJsInterop::class)
internal actual fun platformSecureRandomBytesOrNull(size: Int): ByteArray? =
    runCatching {
        require(size > 0)
        Base64.UrlSafe.decode(HaiveMeshCrypto.randomBase64(size))
    }.getOrNull()

@OptIn(ExperimentalEncodingApi::class, ExperimentalWasmJsInterop::class)
private class BrowserMeshCipher(
    private val keyBase64: String,
) : MeshCipher {
    override suspend fun seal(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): MeshSealedPayload {
        val encoded = HaiveMeshCrypto.sealBase64(
            keyBase64 = keyBase64,
            plaintextBase64 = Base64.UrlSafe.encode(plaintext),
            associatedDataBase64 = Base64.UrlSafe.encode(associatedData),
        ).await().toString()
        val parts = encoded.split('.', limit = 2)
        require(parts.size == 2) { "Browser mesh cipher returned an invalid sealed payload" }
        return MeshSealedPayload(
            nonce = Base64.UrlSafe.decode(parts[0]),
            ciphertext = Base64.UrlSafe.decode(parts[1]),
        )
    }

    override suspend fun open(
        payload: MeshSealedPayload,
        associatedData: ByteArray,
    ): ByteArray =
        Base64.UrlSafe.decode(
            HaiveMeshCrypto.openBase64(
                keyBase64 = keyBase64,
                nonceBase64 = Base64.UrlSafe.encode(payload.nonce),
                ciphertextBase64 = Base64.UrlSafe.encode(payload.ciphertext),
                associatedDataBase64 = Base64.UrlSafe.encode(associatedData),
            ).await().toString(),
        )
}
