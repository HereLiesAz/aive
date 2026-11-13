package com.hereliesaz.geministrator.distributed

import com.hereliesaz.geministrator.net.boundedBodyText
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The only signature scheme a compute node identity uses: ECDSA over P-256 with SHA-256. Public
 * keys travel as Base64 X.509 SubjectPublicKeyInfo (DER), signatures as Base64 ASN.1 DER. Android
 * Keystore and every JCA provider support it, so the private key can stay in the platform keystore.
 */
const val COMPUTE_NODE_SIGNATURE_ALGORITHM: String = "ES256"

/**
 * A compute node's own key pair. The private key never leaves the implementation: Android keeps it
 * in the Android Keystore, desktop in the OS credential store.
 */
interface ComputeNodeSigner {
    /** Base64 (standard, padded) X.509 SubjectPublicKeyInfo of the P-256 public key. */
    val publicKey: String

    /** ECDSA P-256 / SHA-256 signature of [message], ASN.1 DER encoded. */
    fun sign(message: ByteArray): ByteArray
}

/** Sent once, with the shared pool token, to record a node's public key with the pool. */
@Serializable
data class ComputeNodeEnrolmentRequest(
    val protocolVersion: Int = DISTRIBUTED_COMPUTE_PROTOCOL_VERSION,
    val nodeId: String,
    val displayName: String,
    val algorithm: String = COMPUTE_NODE_SIGNATURE_ALGORITHM,
    val publicKey: String,
    val timestampEpochMillis: Long,
    /** Proof of possession: the node's signature over [ComputeNodeAuth.enrolmentMessage]. */
    val signature: String,
)

@Serializable
data class ComputeNodeEnrolmentResponse(
    val nodeId: String,
    val enrolledAtEpochMillis: Long,
)

/** The relay's error body for plain HTTP endpoints. */
@Serializable
data class ComputeRelayHttpError(
    val code: String,
    val message: String,
)

/**
 * A node the pool knows, as the relay records it. Attribution counts are kept by the relay from
 * authenticated worker sessions, so a node cannot claim another node's work.
 */
@Serializable
data class EnrolledComputeNode(
    val nodeId: String,
    val displayName: String,
    /** First 16 hex digits of the SHA-256 of the node's public key. */
    val publicKeyFingerprint: String,
    val enrolledAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long? = null,
    val revokedAtEpochMillis: Long? = null,
    val revokedByNodeId: String? = null,
    val leasesCompleted: Int = 0,
    val leasesFailed: Int = 0,
) {
    val revoked: Boolean get() = revokedAtEpochMillis != null
}

class ComputeNodeEnrolmentException(
    val code: String,
    message: String,
) : IllegalStateException(message)

/**
 * The signed-request scheme shared by the relay and its clients.
 *
 * Enrolment (`POST /v1/compute/{poolId}/enrol`) is the only request that carries the shared pool
 * token. Every session afterwards opens with a request signed by the node's own key: node ID,
 * timestamp and a single-use nonce, valid for [MAX_CLOCK_SKEW_MILLIS] either side of the relay's
 * clock. The relay rejects a nonce it has already seen in that window.
 */
object ComputeNodeAuth {
    const val HEADER_NODE_ID: String = "X-Aive-Node-Id"
    const val HEADER_TIMESTAMP: String = "X-Aive-Node-Timestamp"
    const val HEADER_NONCE: String = "X-Aive-Node-Nonce"
    const val HEADER_SIGNATURE: String = "X-Aive-Node-Signature"
    const val HEADER_OWNER_TOKEN: String = "X-Aive-Owner-Token"

    /** A signed request is accepted this long before or after the relay's clock. */
    const val MAX_CLOCK_SKEW_MILLIS: Long = 5L * 60L * 1_000L

    const val MIN_NONCE_LENGTH: Int = 16
    const val MAX_NONCE_LENGTH: Int = 128
    const val MAX_FIELD_LENGTH: Int = 256

    const val ERROR_UNAUTHORIZED: String = "unauthorized"
    const val ERROR_NODE_NOT_ENROLLED: String = "node-not-enrolled"
    const val ERROR_NODE_REVOKED: String = "node-revoked"
    const val ERROR_NODE_ID_TAKEN: String = "node-id-taken"
    const val ERROR_BAD_SIGNATURE: String = "bad-signature"
    const val ERROR_STALE_REQUEST: String = "stale-request"
    const val ERROR_REPLAYED_REQUEST: String = "replayed-request"
    const val ERROR_POOL_FULL: String = "pool-full"
    const val ERROR_BAD_REQUEST: String = "bad-request"

    /** Non-blank, bounded, and free of control characters, so a field cannot forge a line of a signed message. */
    fun isValidField(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_FIELD_LENGTH && value.none { it.isISOControl() }

    fun isValidNonce(nonce: String): Boolean =
        nonce.length in MIN_NONCE_LENGTH..MAX_NONCE_LENGTH && nonce.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun connectMessage(
        poolId: String,
        nodeId: String,
        timestampEpochMillis: Long,
        nonce: String,
    ): ByteArray {
        require(isValidField(poolId)) { "poolId is not a valid identity field" }
        require(isValidField(nodeId)) { "nodeId is not a valid identity field" }
        require(isValidNonce(nonce)) { "nonce is not valid" }
        return listOf("aive-compute-connect", "v$DISTRIBUTED_COMPUTE_PROTOCOL_VERSION", poolId, nodeId, timestampEpochMillis.toString(), nonce)
            .joinToString("\n")
            .encodeToByteArray()
    }

    fun enrolmentMessage(
        poolId: String,
        nodeId: String,
        displayName: String,
        publicKey: String,
        timestampEpochMillis: Long,
    ): ByteArray {
        require(isValidField(poolId)) { "poolId is not a valid identity field" }
        require(isValidField(nodeId)) { "nodeId is not a valid identity field" }
        require(isValidField(displayName)) { "displayName is not a valid identity field" }
        require(publicKey.isNotBlank() && publicKey.none { it.isISOControl() }) { "publicKey is not valid" }
        return listOf(
            "aive-compute-enrol",
            "v$DISTRIBUTED_COMPUTE_PROTOCOL_VERSION",
            COMPUTE_NODE_SIGNATURE_ALGORITHM,
            poolId,
            nodeId,
            displayName,
            publicKey,
            timestampEpochMillis.toString(),
        ).joinToString("\n").encodeToByteArray()
    }

    /** Headers that open one relay session as [nodeId]. Each call needs a fresh [nonce]. */
    @OptIn(ExperimentalEncodingApi::class)
    fun signedConnectHeaders(
        signer: ComputeNodeSigner,
        poolId: String,
        nodeId: String,
        timestampEpochMillis: Long,
        nonce: String = newNonce(),
    ): Map<String, String> = mapOf(
        HEADER_NODE_ID to nodeId,
        HEADER_TIMESTAMP to timestampEpochMillis.toString(),
        HEADER_NONCE to nonce,
        HEADER_SIGNATURE to Base64.Default.encode(signer.sign(connectMessage(poolId, nodeId, timestampEpochMillis, nonce))),
    )

    @OptIn(ExperimentalEncodingApi::class)
    fun enrolmentRequest(
        signer: ComputeNodeSigner,
        poolId: String,
        nodeId: String,
        displayName: String,
        timestampEpochMillis: Long,
    ): ComputeNodeEnrolmentRequest = ComputeNodeEnrolmentRequest(
        nodeId = nodeId,
        displayName = displayName,
        publicKey = signer.publicKey,
        timestampEpochMillis = timestampEpochMillis,
        signature = Base64.Default.encode(
            signer.sign(enrolmentMessage(poolId, nodeId, displayName, signer.publicKey, timestampEpochMillis)),
        ),
    )

    /** 128 random bits as hex. A nonce only has to be unique, not secret. */
    fun newNonce(random: Random = Random.Default): String =
        random.nextBytes(16).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** The enrolment endpoint for a relay given by its WebSocket (or HTTP) base URL. */
    fun enrolmentUrl(relayBaseUrl: String, poolId: String): String {
        val trimmed = relayBaseUrl.trim().trimEnd('/')
        val http = when {
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            else -> trimmed
        }
        return "$http/v1/compute/$poolId/enrol"
    }
}

/** Enrols a node's public key with a pool. This is the only use of the shared pool token. */
class ComputeNodeEnroller(
    private val httpClient: HttpClient,
    private val relayBaseUrl: String,
    private val poolId: String,
    private val poolToken: String,
    private val signer: ComputeNodeSigner,
    private val nowEpochMillis: () -> Long,
    private val json: Json = RelayDistributedComputeClient.defaultJson,
    /** The pool owner's token, when the keeper set one: sent with the enrolment so an owner can re-enrol a revoked node. */
    private val ownerToken: String? = null,
) {
    suspend fun enrol(nodeId: String, displayName: String): ComputeNodeEnrolmentResponse {
        require(poolToken.isNotBlank()) { "The pool token is required to enrol this device" }
        require(isSecureRelayUrl(relayBaseUrl)) {
            "The pool token is only sent over wss:// or https:// (plain ws:// and http:// are allowed for localhost only)"
        }
        val request = ComputeNodeAuth.enrolmentRequest(signer, poolId, nodeId, displayName, nowEpochMillis())
        val response = httpClient.post(ComputeNodeAuth.enrolmentUrl(relayBaseUrl, poolId)) {
            header(HttpHeaders.Authorization, "Bearer $poolToken")
            ownerToken?.takeIf(String::isNotBlank)?.let { header(ComputeNodeAuth.HEADER_OWNER_TOKEN, it) }
            setBody(
                TextContent(
                    json.encodeToString(ComputeNodeEnrolmentRequest.serializer(), request),
                    ContentType.Application.Json,
                ),
            )
        }
        val body = response.boundedBodyText()
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString(ComputeRelayHttpError.serializer(), body) }.getOrNull()
            throw ComputeNodeEnrolmentException(
                code = error?.code ?: ("http-" + response.status.value),
                message = error?.message ?: ("Enrolment failed with HTTP " + response.status.value),
            )
        }
        return json.decodeFromString(ComputeNodeEnrolmentResponse.serializer(), body)
    }
}

/**
 * Remembers that this device's key is enrolled with a pool, so the pool token is not sent again on
 * every connection. Keyed by relay, pool, node ID and public key: any change enrols afresh.
 */
interface ComputeNodeEnrolmentLedger {
    fun isEnrolled(fingerprint: String): Boolean
    fun recordEnrolled(fingerprint: String)
    fun forget()

    companion object {
        fun fingerprint(relayBaseUrl: String, poolId: String, nodeId: String, publicKey: String): String =
            listOf(relayBaseUrl.trim().trimEnd('/'), poolId, nodeId, publicKey).joinToString("\n")
    }
}

class InMemoryComputeNodeEnrolmentLedger : ComputeNodeEnrolmentLedger {
    private var recorded: String? = null
    override fun isEnrolled(fingerprint: String): Boolean = recorded == fingerprint
    override fun recordEnrolled(fingerprint: String) {
        recorded = fingerprint
    }
    override fun forget() {
        recorded = null
    }
}

class SettingsComputeNodeEnrolmentLedger(
    private val settings: Settings = Settings(),
    private val storageKey: String = DEFAULT_STORAGE_KEY,
) : ComputeNodeEnrolmentLedger {
    override fun isEnrolled(fingerprint: String): Boolean = settings.getStringOrNull(storageKey) == fingerprint
    override fun recordEnrolled(fingerprint: String) = settings.putString(storageKey, fingerprint)
    override fun forget() = settings.remove(storageKey)

    companion object {
        const val DEFAULT_STORAGE_KEY: String = "haive.distributed-compute.enrolment.v1"
    }
}
