package com.hereliesaz.aive

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hereliesaz.geministrator.distributed.ComputeNodeSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This device's compute node identity: a P-256 signing key generated inside the Android Keystore.
 * The private key is not exportable; only the public key leaves the Keystore.
 */
internal class AndroidComputeNodeSigner private constructor(
    private val privateKey: PrivateKey,
    override val publicKey: String,
) : ComputeNodeSigner {
    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(message)
            sign()
        }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "aive.distributed-compute.node-identity"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun loadOrCreate(): AndroidComputeNodeSigner {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            val entry = existing ?: run {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
                    initialize(
                        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .build(),
                    )
                }.generateKeyPair()
                keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
            }
            return AndroidComputeNodeSigner(
                privateKey = entry.privateKey,
                publicKey = Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP),
            )
        }
    }
}
