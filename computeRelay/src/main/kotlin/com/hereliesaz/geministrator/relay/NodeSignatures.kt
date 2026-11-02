package com.hereliesaz.geministrator.relay

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** ECDSA P-256 / SHA-256 verification of compute node signatures (`ES256`, DER, SPKI keys). */
internal object NodeSignatures {
    /** The P-256 public key in [encoded] (Base64 SPKI), or null when it is anything else. */
    fun decodePublicKey(encoded: String): PublicKey? = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        (key as? ECPublicKey)?.takeIf { it.params.curve.field.fieldSize == 256 }
    }.getOrNull()

    fun verify(publicKey: PublicKey, message: ByteArray, signatureBase64: String): Boolean = runCatching {
        val signature = Base64.getDecoder().decode(signatureBase64)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }
    }.getOrDefault(false)

    /** First 16 hex digits of the SHA-256 of the encoded key, for people to tell keys apart. */
    fun fingerprint(encodedPublicKey: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(Base64.getDecoder().decode(encodedPublicKey))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrDefault("unknown")
}
