package com.hereliesaz.aive.remote

import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The remote-relay pairing The Aive owns: the relay it uses, the room, both relay tokens and the
 * key that seals every frame. [code] is the part IDEaz gets (no host token); the host token never
 * leaves this device. See docs/Remote-API.md, "Relay".
 */
internal class RemoteRelayPairing(
    val relay: String,
    val room: String,
    val hostToken: String,
    val clientToken: String,
    key: ByteArray,
) {
    val key: ByteArray = key.copyOf()

    init {
        require(this.key.size == KEY_BYTES) { "Pairing key must be 256 bits" }
        require(ROOM_ID.matches(room)) { "Invalid room id" }
        require(isStrongRelayToken(hostToken) && isStrongRelayToken(clientToken) && hostToken != clientToken) {
            "Invalid relay tokens"
        }
    }

    /** `aive1:` + base64url (no padding) of the UTF-8 JSON IDEaz reads. */
    val code: String
        get() = CODE_PREFIX + ENCODER.encodeToString(
            buildJsonObject {
                put("v", 1)
                put("relay", relay)
                put("room", room)
                put("clientToken", clientToken)
                put("key", ENCODER.encodeToString(key))
            }.toString().encodeToByteArray(),
        )

    /** The persisted form: everything, including the host token. Only ever written to the Keystore store. */
    fun toStorage(): String = buildJsonObject {
        put("relay", relay)
        put("room", room)
        put("hostToken", hostToken)
        put("clientToken", clientToken)
        put("key", ENCODER.encodeToString(key))
    }.toString()

    companion object {
        const val CODE_PREFIX = "aive1:"
        const val KEY_BYTES = 32
        private val ROOM_ID = Regex("^[A-Za-z0-9_-]{16,64}$")
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()

        /** A fresh pairing on [relay] (already normalised by [normalizeRelayUrl]). */
        fun generate(relay: String, random: SecureRandom = SecureRandom()): RemoteRelayPairing {
            val hostToken = randomToken(random)
            var clientToken = randomToken(random)
            while (clientToken == hostToken) clientToken = randomToken(random)
            return RemoteRelayPairing(
                relay = relay,
                room = ENCODER.encodeToString(randomBytes(random, 16)),
                hostToken = hostToken,
                clientToken = clientToken,
                key = randomBytes(random, KEY_BYTES),
            )
        }

        fun fromStorage(stored: String): RemoteRelayPairing? = runCatching {
            val json = Json.parseToJsonElement(stored).jsonObject
            RemoteRelayPairing(
                relay = json.text("relay"),
                room = json.text("room"),
                hostToken = json.text("hostToken"),
                clientToken = json.text("clientToken"),
                key = DECODER.decode(json.text("key")),
            )
        }.getOrNull()

        /** What a client reads from a pairing code. Used by tests and as the reference decoder. */
        fun decodeCode(code: String): PairingCodeContents {
            require(code.startsWith(CODE_PREFIX)) { "Not an aive1 pairing code" }
            val json = Json.parseToJsonElement(
                DECODER.decode(code.removePrefix(CODE_PREFIX)).decodeToString(),
            ).jsonObject
            require((json["v"] as? JsonPrimitive)?.intOrNull == 1) { "Unsupported pairing code version" }
            val key = DECODER.decode(json.text("key"))
            require(key.size == KEY_BYTES) { "Pairing key must be 256 bits" }
            return PairingCodeContents(json.text("relay"), json.text("room"), json.text("clientToken"), key)
        }

        private fun JsonObject.text(name: String): String {
            val value = this[name] as? JsonPrimitive
            require(value != null && value.isString) { "Missing $name" }
            return value.content
        }

        /** Base64url of 32 random bytes, regenerated in the vanishing case the relay would call it weak. */
        private fun randomToken(random: SecureRandom): String {
            while (true) {
                val token = ENCODER.encodeToString(randomBytes(random, 32))
                if (isStrongRelayToken(token)) return token
            }
        }

        private fun randomBytes(random: SecureRandom, size: Int) = ByteArray(size).also(random::nextBytes)
    }
}

internal class PairingCodeContents(val relay: String, val room: String, val clientToken: String, val key: ByteArray)

/** The relay's own token rule (relay/src/protocol.js): 43 to 128 base64url characters, 16 distinct. */
internal fun isStrongRelayToken(token: String): Boolean =
    Regex("^[A-Za-z0-9_-]{43,128}$").matches(token) && token.toSet().size >= 16

/**
 * The relay origin to use for [input]: https, or plain http only for loopback (tests, a local
 * `wrangler dev`), with no credentials, path, query or fragment. Throws [IllegalArgumentException]
 * with a user-facing message otherwise.
 */
internal fun normalizeRelayUrl(input: String): String {
    val text = input.trim().trimEnd('/')
    val uri = try {
        URI(text)
    } catch (_: java.net.URISyntaxException) {
        throw IllegalArgumentException("Relay URL is not valid")
    }
    val host = uri.host?.lowercase()
    require(host != null && uri.rawUserInfo == null) { "Relay URL is not valid" }
    require(uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
        "Relay URL must be just the address, such as https://relay.example.com"
    }
    val loopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
    require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
        "Relay URL must start with https:// (plain http is only allowed for localhost)"
    }
    return text
}
