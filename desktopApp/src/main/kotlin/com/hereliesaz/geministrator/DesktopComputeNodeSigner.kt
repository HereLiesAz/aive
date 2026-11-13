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

/** The system store could not be read right now; the node identity was left untouched. */
internal class IdentityUnreadableException :
    IllegalStateException("The system store could not be read, so this device's compute identity was left unchanged. Unlock it and try again.")

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

        /**
         * The stored identity, or a new one handed to [write] when there is none (or the stored
         * value is not a key pair). An unreadable store (locked, timed out) never creates or
         * overwrites an identity: it throws [IdentityUnreadableException] so the caller retries later.
         */
        fun loadOrCreate(
            read: () -> StoredSecret,
            write: (String) -> Unit,
        ): DesktopComputeNodeSigner {
            when (val stored = read()) {
                is StoredSecret.Found -> decode(stored.value)?.let { return it }
                StoredSecret.Unreadable -> throw IdentityUnreadableException()
                StoredSecret.Absent -> Unit
            }
            return generate().also { write(it.encoded()) }
        }

        fun loadOrCreate(store: DesktopDistributedComputeCredentialStore): DesktopComputeNodeSigner =
            loadOrCreate(read = store::readSecret, write = store::writeToken)
    }
}
