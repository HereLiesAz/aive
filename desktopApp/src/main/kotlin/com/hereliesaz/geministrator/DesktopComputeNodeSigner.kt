package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.distributed.ComputeNodeSigner
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * This desktop's compute node identity: a P-256 key pair (JCA). The private key is kept in the OS
 * credential store through [DesktopDistributedComputeCredentialStore.forNodeIdentity], with the same
 * plaintext-preferences fallback as the relay token when no store works.
 */
internal class DesktopComputeNodeSigner(private val keyPair: KeyPair) : ComputeNodeSigner {
    override val publicKey: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(message)
            sign()
        }

    /** `<SPKI>.<PKCS#8>`, both Base64; the form the credential store keeps. */
    fun encoded(): String =
        publicKey + SEPARATOR + Base64.getEncoder().encodeToString(keyPair.private.encoded)

    companion object {
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val SEPARATOR = "."

        fun generate(): DesktopComputeNodeSigner =
            DesktopComputeNodeSigner(
                KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(),
            )

        /** The signer in [encoded], or null when it is not a key pair this class wrote. */
        fun decode(encoded: String): DesktopComputeNodeSigner? = runCatching {
            val (publicPart, privatePart) = encoded.trim().split(SEPARATOR).takeIf { it.size == 2 } ?: return null
            val factory = KeyFactory.getInstance("EC")
            DesktopComputeNodeSigner(
                KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicPart))),
                    factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privatePart))),
                ),
            )
        }.getOrNull()

        /** The stored identity, or a new one handed to [write] when there is none (or it is unreadable). */
        fun loadOrCreate(
            read: () -> String?,
            write: (String) -> Unit,
        ): DesktopComputeNodeSigner {
            read()?.let(::decode)?.let { return it }
            return generate().also { write(it.encoded()) }
        }

        fun loadOrCreate(store: DesktopDistributedComputeCredentialStore): DesktopComputeNodeSigner =
            loadOrCreate(read = store::readToken, write = store::writeToken)
    }
}
