package com.hereliesaz.geministrator.events

import com.hereliesaz.geministrator.Sha256

/**
 * What the journal keeps of a model request: the SHA-256 of its text and the text's length in characters.
 * The prompt itself is never recorded (docs/THREAT_MODEL.md: prompts can carry the keeper's own material).
 * A fingerprint says two calls were sent the same text; it cannot be turned back into the text.
 */
data class RequestFingerprint(val sha256: String, val chars: Int) {
    /** The first eight hex digits: enough to tell two requests apart in a log line. */
    val short: String get() = shortFingerprint(sha256)
}

/** The first eight hex digits of a request fingerprint. */
fun shortFingerprint(sha256: String): String = sha256.take(8)

/** The fingerprint of [text] as UTF-8 bytes (a multiplatform SHA-256, no JVM-only call). */
fun fingerprintRequestText(text: String): RequestFingerprint =
    RequestFingerprint(Sha256.hex(text.encodeToByteArray()), text.length)
