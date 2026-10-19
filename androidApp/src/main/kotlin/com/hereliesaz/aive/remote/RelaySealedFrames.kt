package com.hereliesaz.aive.remote

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Constants of the sealed relay protocol (docs/Remote-API.md, "Sealed frames and messages"). */
internal object RelayProtocol {
    const val DEFAULT_RELAY_URL = "https://aive-relay.hereliesaz.workers.dev"
    const val DIR_CLIENT_TO_HOST = "c2h"
    const val DIR_HOST_TO_CLIENT = "h2c"

    /** Largest plaintext JSON in one frame, in UTF-8 bytes. */
    const val MAX_PLAINTEXT_BYTES = 600_000

    /** Largest prompt accepted over the relay, in UTF-8 bytes. */
    const val MAX_PROMPT_BYTES = 500_000

    /** A message whose timestamp is further than this from the receiver's clock is rejected. */
    const val MAX_CLOCK_SKEW_MILLIS = 10 * 60 * 1000L

    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    fun associatedData(room: String, direction: String, seq: Long): ByteArray =
        "aive-relay/1|$room|$direction|$seq".encodeToByteArray()
}

/** A frame that must be dropped: malformed, unauthentic, replayed or stale. */
internal class RelayProtocolException(message: String) : Exception(message)

/** One opened frame: the sequence number from its prefix and the authenticated plaintext. */
internal class OpenedFrame(val seq: Long, val plaintext: ByteArray)

/**
 * Seals and opens relay frames: `<seq>.<base64url(nonce12 || ciphertext || tag16)>`, AES-256-GCM
 * with the pairing key, a fresh random nonce, and the direction, room and sequence number bound
 * in as associated data so a frame cannot be moved between rooms, directions or positions.
 */
internal class RelayFrameCipher(key: ByteArray, private val room: String, private val random: SecureRandom = SecureRandom()) {
    private val keySpec: SecretKeySpec

    init {
        require(key.size == RemoteRelayPairing.KEY_BYTES) { "Pairing key must be 256 bits" }
        keySpec = SecretKeySpec(key.copyOf(), "AES")
    }

    fun seal(direction: String, seq: Long, plaintext: ByteArray): String {
        require(seq >= 1) { "Sequence numbers start at 1" }
        require(plaintext.size <= RelayProtocol.MAX_PLAINTEXT_BYTES) { "Plaintext is too large" }
        val nonce = ByteArray(RelayProtocol.NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(RelayProtocol.associatedData(room, direction, seq))
        return "$seq." + ENCODER.encodeToString(nonce + cipher.doFinal(plaintext))
    }

    /** Opens [data] sent in [direction]; throws [RelayProtocolException] for anything but an authentic frame. */
    fun open(direction: String, data: String): OpenedFrame {
        val dot = data.indexOf('.')
        if (dot < 1 || dot > MAX_SEQ_DIGITS) throw RelayProtocolException("Malformed frame")
        val seqText = data.substring(0, dot)
        if (!seqText.all { it in '0'..'9' } || (seqText.length > 1 && seqText[0] == '0')) {
            throw RelayProtocolException("Malformed sequence number")
        }
        val seq = seqText.toLongOrNull()?.takeIf { it >= 1 } ?: throw RelayProtocolException("Malformed sequence number")
        if (data.length - dot - 1 > MAX_ENCODED_BYTES) throw RelayProtocolException("Frame too large")
        val bytes = try {
            DECODER.decode(data.substring(dot + 1))
        } catch (_: IllegalArgumentException) {
            throw RelayProtocolException("Malformed frame")
        }
        if (bytes.size < RelayProtocol.NONCE_BYTES + RelayProtocol.TAG_BYTES ||
            bytes.size > RelayProtocol.NONCE_BYTES + RelayProtocol.TAG_BYTES + RelayProtocol.MAX_PLAINTEXT_BYTES
        ) {
            throw RelayProtocolException("Malformed frame")
        }
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                GCMParameterSpec(TAG_BITS, bytes, 0, RelayProtocol.NONCE_BYTES),
            )
            cipher.updateAAD(RelayProtocol.associatedData(room, direction, seq))
            cipher.doFinal(bytes, RelayProtocol.NONCE_BYTES, bytes.size - RelayProtocol.NONCE_BYTES)
        } catch (_: GeneralSecurityException) {
            throw RelayProtocolException("Frame failed authentication")
        }
        return OpenedFrame(seq, plaintext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val MAX_SEQ_DIGITS = 19
        const val MAX_ENCODED_BYTES = (RelayProtocol.NONCE_BYTES + RelayProtocol.TAG_BYTES + RelayProtocol.MAX_PLAINTEXT_BYTES) * 4 / 3 + 4
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}

/**
 * Remembers the last sequence number accepted from each sender session, for at most [maxSessions]
 * sessions (least recently used are forgotten), and rejects a message whose number is not strictly
 * greater or whose timestamp is more than [RelayProtocol.MAX_CLOCK_SKEW_MILLIS] from now.
 */
internal class RelayReplayGuard(
    private val maxSessions: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastSeq = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxSessions
    }

    @Synchronized
    fun accept(sid: String, seq: Long, ts: Long) {
        if (!SESSION_ID.matches(sid)) throw RelayProtocolException("Invalid session id")
        val now = clock()
        if (ts < now - RelayProtocol.MAX_CLOCK_SKEW_MILLIS || ts > now + RelayProtocol.MAX_CLOCK_SKEW_MILLIS) {
            throw RelayProtocolException("Message timestamp is out of range")
        }
        val last = lastSeq[sid]
        if (last != null && seq <= last) throw RelayProtocolException("Replayed or reordered message")
        lastSeq[sid] = seq
    }

    private companion object {
        val SESSION_ID = Regex("^[0-9a-f]{16}$")
    }
}

internal fun randomSessionId(random: SecureRandom = SecureRandom()): String =
    ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
