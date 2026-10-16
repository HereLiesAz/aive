package com.hereliesaz.aive.remote

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class RemoteRelayPairingTest {
    private val relay = "https://relay.example.com"

    @Test
    fun aPairingCodeCarriesEverythingButTheHostToken() {
        val pairing = RemoteRelayPairing.generate(relay)
        val code = pairing.code
        assertTrue(code.startsWith("aive1:"))
        val body = code.removePrefix("aive1:")
        assertFalse(body.contains('=') || body.contains('+') || body.contains('/'), "base64url without padding")

        val json = Json.parseToJsonElement(Base64.getUrlDecoder().decode(body).decodeToString()).jsonObject
        assertEquals(setOf("v", "relay", "room", "clientToken", "key"), json.keys)
        assertEquals("1", (json["v"] as JsonPrimitive).content)
        assertFalse(code.contains(pairing.hostToken))
        assertFalse(Base64.getUrlDecoder().decode(body).decodeToString().contains(pairing.hostToken))

        val decoded = RemoteRelayPairing.decodeCode(code)
        assertEquals(relay, decoded.relay)
        assertEquals(pairing.room, decoded.room)
        assertEquals(pairing.clientToken, decoded.clientToken)
        assertContentEquals(pairing.key, decoded.key)
        assertEquals(32, decoded.key.size)
    }

    @Test
    fun generatedSecretsObeyTheRelaysRules() {
        repeat(20) {
            val pairing = RemoteRelayPairing.generate(relay)
            assertTrue(pairing.room.length in 16..64)
            assertTrue(isStrongRelayToken(pairing.hostToken))
            assertTrue(isStrongRelayToken(pairing.clientToken))
            assertNotEquals(pairing.hostToken, pairing.clientToken)
            assertTrue(pairing.hostToken.length >= 43)
        }
        assertNotEquals(RemoteRelayPairing.generate(relay).room, RemoteRelayPairing.generate(relay).room)
        assertFalse(isStrongRelayToken("a".repeat(43)))
        assertFalse(isStrongRelayToken("short"))
    }

    @Test
    fun storageKeepsTheHostTokenAndRoundTrips() {
        val pairing = RemoteRelayPairing.generate(relay)
        val restored = assertNotNull(RemoteRelayPairing.fromStorage(pairing.toStorage()))
        assertEquals(pairing.hostToken, restored.hostToken)
        assertEquals(pairing.code, restored.code)
        assertNull(RemoteRelayPairing.fromStorage("not json"))
        assertNull(RemoteRelayPairing.fromStorage("{}"))
    }

    @Test
    fun malformedPairingCodesAreRejected() {
        assertFailsWith<IllegalArgumentException> { RemoteRelayPairing.decodeCode("aive2:abc") }
        val badVersion = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"v":2,"relay":"$relay","room":"r","clientToken":"t","key":"k"}""".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { RemoteRelayPairing.decodeCode("aive1:$badVersion") }
    }

    @Test
    fun relayUrlsMustBeHttpsOrLoopback() {
        assertEquals(RelayProtocol.DEFAULT_RELAY_URL, normalizeRelayUrl(" ${RelayProtocol.DEFAULT_RELAY_URL}/ "))
        assertEquals("http://localhost:8787", normalizeRelayUrl("http://localhost:8787"))
        assertEquals("http://127.0.0.1:8787", normalizeRelayUrl("http://127.0.0.1:8787/"))
        listOf(
            "http://relay.example.com",
            "ftp://relay.example.com",
            "https://user:pw@relay.example.com",
            "https://relay.example.com/path",
            "https://relay.example.com?x=1",
            "relay.example.com",
            "",
        ).forEach { bad -> assertFailsWith<IllegalArgumentException>(bad) { normalizeRelayUrl(bad) } }
    }

    @Test
    fun framesFollowTheDocumentedWireFormat() {
        val key = ByteArray(32) { it.toByte() }
        val cipher = RelayFrameCipher(key, "room-0123456789abcdef")
        val sealed = cipher.seal(RelayProtocol.DIR_CLIENT_TO_HOST, 7, """{"t":"ping"}""".encodeToByteArray())
        assertTrue(sealed.startsWith("7."))

        // Decrypt by hand, exactly as the spec describes, to prove IDEaz can interoperate.
        val raw = Base64.getUrlDecoder().decode(sealed.substringAfter('.'))
        val manual = Cipher.getInstance("AES/GCM/NoPadding")
        manual.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        manual.updateAAD("aive-relay/1|room-0123456789abcdef|c2h|7".encodeToByteArray())
        assertEquals("""{"t":"ping"}""", manual.doFinal(raw, 12, raw.size - 12).decodeToString())
        assertEquals(12 + 12 + 16, raw.size)

        assertEquals("""{"t":"ping"}""", cipher.open(RelayProtocol.DIR_CLIENT_TO_HOST, sealed).plaintext.decodeToString())
        assertEquals(7L, cipher.open(RelayProtocol.DIR_CLIENT_TO_HOST, sealed).seq)
    }

    @Test
    fun everySealUsesAFreshNonce() {
        val cipher = RelayFrameCipher(ByteArray(32) { 1 }, "room-0123456789abcdef")
        val a = cipher.seal(RelayProtocol.DIR_HOST_TO_CLIENT, 1, "x".encodeToByteArray())
        val b = cipher.seal(RelayProtocol.DIR_HOST_TO_CLIENT, 1, "x".encodeToByteArray())
        assertNotEquals(a, b)
    }

    @Test
    fun framesBoundToAnotherRoomDirectionSequenceOrKeyAreRejected() {
        val key = ByteArray(32) { 9 }
        val room = "room-0123456789abcdef"
        val cipher = RelayFrameCipher(key, room)
        val sealed = cipher.seal(RelayProtocol.DIR_CLIENT_TO_HOST, 3, "hello".encodeToByteArray())

        assertFailsWith<RelayProtocolException> { cipher.open(RelayProtocol.DIR_HOST_TO_CLIENT, sealed) }
        assertFailsWith<RelayProtocolException> { RelayFrameCipher(key, "room-other-0123456789").open(RelayProtocol.DIR_CLIENT_TO_HOST, sealed) }
        assertFailsWith<RelayProtocolException> { RelayFrameCipher(ByteArray(32), room).open(RelayProtocol.DIR_CLIENT_TO_HOST, sealed) }
        // Same ciphertext under a different sequence prefix.
        assertFailsWith<RelayProtocolException> { cipher.open(RelayProtocol.DIR_CLIENT_TO_HOST, "4." + sealed.substringAfter('.')) }
        // One flipped bit anywhere in nonce, ciphertext or tag.
        val raw = Base64.getUrlDecoder().decode(sealed.substringAfter('.'))
        for (index in listOf(0, 13, raw.size - 1)) {
            val tampered = raw.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            val data = "3." + Base64.getUrlEncoder().withoutPadding().encodeToString(tampered)
            assertFailsWith<RelayProtocolException> { cipher.open(RelayProtocol.DIR_CLIENT_TO_HOST, data) }
        }
    }

    @Test
    fun malformedFramesAreRejected() {
        val cipher = RelayFrameCipher(ByteArray(32) { 5 }, "room-0123456789abcdef")
        listOf("", "nodot", ".abc", "0.abc", "01.abc", "-1.abc", "1.", "1.!!!!", "1.${"A".repeat(10)}", "a.abc", "1.2.3").forEach { bad ->
            assertFailsWith<RelayProtocolException>(bad) { cipher.open(RelayProtocol.DIR_CLIENT_TO_HOST, bad) }
        }
        assertFailsWith<IllegalArgumentException> { cipher.seal(RelayProtocol.DIR_HOST_TO_CLIENT, 0, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> {
            cipher.seal(RelayProtocol.DIR_HOST_TO_CLIENT, 1, ByteArray(RelayProtocol.MAX_PLAINTEXT_BYTES + 1))
        }
        // The largest allowed plaintext round-trips.
        val big = ByteArray(RelayProtocol.MAX_PLAINTEXT_BYTES) { 'a'.code.toByte() }
        val sealed = cipher.seal(RelayProtocol.DIR_HOST_TO_CLIENT, 1, big)
        assertEquals(big.size, cipher.open(RelayProtocol.DIR_HOST_TO_CLIENT, sealed).plaintext.size)
    }

    @Test
    fun theReplayGuardWantsStrictlyIncreasingSequencesPerSession() {
        var now = 1_000_000L
        val guard = RelayReplayGuard(clock = { now })
        val a = "aaaaaaaaaaaaaaaa"
        val b = "bbbbbbbbbbbbbbbb"
        guard.accept(a, 1, now)
        guard.accept(a, 2, now)
        guard.accept(a, 5, now)
        assertFailsWith<RelayProtocolException> { guard.accept(a, 5, now) }
        assertFailsWith<RelayProtocolException> { guard.accept(a, 3, now) }
        guard.accept(b, 1, now)
        guard.accept(a, 6, now)
        assertFailsWith<RelayProtocolException> { guard.accept("not-a-session-id", 1, now) }
        assertFailsWith<RelayProtocolException> { guard.accept("AAAAAAAAAAAAAAAA", 1, now) }
    }

    @Test
    fun theReplayGuardRejectsStaleAndFutureTimestamps() {
        val now = 10_000_000_000L
        val guard = RelayReplayGuard(clock = { now })
        val sid = "0123456789abcdef"
        val tenMinutes = 10 * 60 * 1000L
        guard.accept(sid, 1, now - tenMinutes)
        guard.accept(sid, 2, now + tenMinutes)
        assertFailsWith<RelayProtocolException> { guard.accept(sid, 3, now - tenMinutes - 1) }
        assertFailsWith<RelayProtocolException> { guard.accept(sid, 3, now + tenMinutes + 1) }
        assertFailsWith<RelayProtocolException> { guard.accept(sid, 3, Long.MIN_VALUE) }
        assertFailsWith<RelayProtocolException> { guard.accept(sid, 3, Long.MAX_VALUE) }
        guard.accept(sid, 3, now)
    }

    @Test
    fun theReplayGuardForgetsTheLeastRecentlyUsedSessions() {
        val now = 5_000_000L
        val guard = RelayReplayGuard(maxSessions = 2, clock = { now })
        val a = "aaaaaaaaaaaaaaaa"
        val b = "bbbbbbbbbbbbbbbb"
        val c = "cccccccccccccccc"
        guard.accept(a, 4, now)
        guard.accept(b, 4, now)
        guard.accept(a, 5, now) // a is now the most recently used
        guard.accept(c, 1, now) // evicts b
        assertFailsWith<RelayProtocolException> { guard.accept(a, 5, now) }
        guard.accept(b, 1, now) // forgotten, so accepted again
    }

    @Test
    fun linkHelpersBuildAndParseRelayFrames() {
        val pairing = RemoteRelayPairing.generate("https://relay.example.com")
        assertEquals("wss://relay.example.com/v1/rooms/${pairing.room}?role=host", hostWebSocketUrl(pairing))
        val local = RemoteRelayPairing.generate("http://localhost:8787")
        assertEquals("ws://localhost:8787/v1/rooms/${local.room}?role=host", hostWebSocketUrl(local))

        assertEquals("""{"to":"c1","data":"1.abc"}""", relayFrameJson("c1", "1.abc"))
        assertEquals("c1" to "1.abc", parseRelayFrame("""{"from":"c1","data":"1.abc"}"""))
        assertNull(parseRelayFrame("""{"event":"host-online"}"""))
        assertNull(parseRelayFrame("""{"from":"c1","data":5}"""))
        assertNull(parseRelayFrame("[1]"))
        assertNull(parseRelayFrame("nope"))

        assertTrue(isMissingRoom(RuntimeException("Handshake exception, expected status code 101 but was: 404")))
        assertTrue(isMissingRoom(RuntimeException("outer", IllegalStateException("No such room"))))
        assertFalse(isMissingRoom(RuntimeException("expected status code 101 but was: 401")))
        assertFalse(isMissingRoom(RuntimeException("port 14040 refused")))
    }
}
