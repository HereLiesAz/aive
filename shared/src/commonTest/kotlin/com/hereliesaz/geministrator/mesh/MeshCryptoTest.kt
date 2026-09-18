package com.hereliesaz.geministrator.mesh

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class MeshCryptoTest {
    @Test
    fun pairingInvitationRoundTripsAndCipherAuthenticatesPayload() = runBlocking {
        val invitation = MeshPairing.createInvitation(
            relayBaseUrl = "https://relay.example.test",
            roomId = "room-123",
        )
        val encoded = MeshPairing.encode(invitation)
        val decoded = MeshPairing.decode(encoded)
        val credentials = MeshPairing.credentials(decoded)

        assertEquals("room-123", credentials.roomId)
        assertEquals(32, credentials.encryptionKey.size)

        val cipher = credentials.cipher()
        val plaintext = "remote workflow payload".encodeToByteArray()
        val aad = "room-123|phone|desktop".encodeToByteArray()
        val sealed = cipher.seal(plaintext, aad)

        assertContentEquals(
            plaintext,
            cipher.open(sealed, aad),
        )
        assertFails {
            runBlocking {
                cipher.open(sealed, "wrong-route".encodeToByteArray())
            }
        }
    }
}
