package com.hereliesaz.geministrator

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopComputeNodeSignerTest {
    @Test
    fun signaturesVerifyAgainstThePublishedKey() {
        val signer = DesktopComputeNodeSigner.generate()
        val message = "aive-compute-connect\nv2\npool\ndesk\n1\nnonce".encodeToByteArray()

        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(signer.publicKey)))
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(message)
            verify(signer.sign(message))
        }
        assertTrue(verified)
    }

    @Test
    fun theStoredIdentityIsReusedAndAnUnreadableOneIsReplaced() {
        var stored: String? = null
        var writes = 0
        val first = DesktopComputeNodeSigner.loadOrCreate(read = { stored }, write = { stored = it; writes++ })
        val second = DesktopComputeNodeSigner.loadOrCreate(read = { stored }, write = { stored = it; writes++ })

        assertEquals(first.publicKey, second.publicKey)
        assertEquals(1, writes)
        assertNotNull(DesktopComputeNodeSigner.decode(first.encoded()))
        assertNull(DesktopComputeNodeSigner.decode("not a key"))

        stored = "garbage"
        val third = DesktopComputeNodeSigner.loadOrCreate(read = { stored }, write = { stored = it; writes++ })
        assertTrue(third.publicKey != first.publicKey)
        assertEquals(2, writes)
    }
}
