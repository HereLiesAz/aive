package com.hereliesaz.geministrator.memory.procedural

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.Serializable

/**
 * An explicitly supplied UTF-8 procedure source, not a command captured from logs.
 * The content stays inside its producing workflow's sidecar, never inside GRIP prompts.
 * Neither storing nor exporting the source authorizes it to run.
 */
@Serializable
data class ProcedureArtifact(
    val workflowId: String,
    val digest: String,
    val source: String,
) {
    init {
        require(workflowId.isNotBlank() && workflowId.length <= 256)
        require(digest.matches(Regex("sha256:[0-9a-f]{64}")))
        require(source.isNotEmpty() && '\u0000' !in source)
        require(source.encodeToByteArray().size <= MAX_SOURCE_BYTES)
    }

    /** Always recompute; a caller-supplied digest is not proof of content integrity. */
    suspend fun verify() {
        require(digest == fingerprint(source)) { "Procedure source digest does not match its content" }
    }

    companion object {
        const val MAX_SOURCE_BYTES = 65_536

        suspend fun fromSource(workflowId: String, source: String): ProcedureArtifact =
            ProcedureArtifact(workflowId, fingerprint(source), source)

        suspend fun fingerprint(source: String): String {
            require(source.encodeToByteArray().size <= MAX_SOURCE_BYTES)
            val digestBytes = CryptographyProvider.Default.get(SHA256).hasher().hash(source.encodeToByteArray())
            val alphabet = "0123456789abcdef"
            val hex = buildString(digestBytes.size * 2) {
                digestBytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(alphabet[value ushr 4])
                    append(alphabet[value and 0x0f])
                }
            }
            return "sha256:$hex"
        }
    }
}
