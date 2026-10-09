package com.hereliesaz.geministrator.relay

import com.hereliesaz.geministrator.distributed.DistributedExecutionProgress
import com.hereliesaz.geministrator.distributed.DistributedExecutionResult
import com.hereliesaz.geministrator.distributed.DistributedTaskEnvelope
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persistable relay lease state. Node sockets are deliberately not persisted.
 *
 * On process restart unfinished leases are restored as unclaimed because any worker WebSocket that
 * owned the old claim was necessarily severed by the relay restart. The worker-side cancellation
 * guard treats that transport loss as cancellation, so re-offering the lease cannot overlap a still
 * authoritative worker from the dead relay process.
 */
@Serializable
data class PersistedRelayLease(
    val envelope: DistributedTaskEnvelope,
    val originNodeId: String,
    val workerNodeId: String? = null,
    val progress: DistributedExecutionProgress? = null,
    val result: DistributedExecutionResult? = null,
    val completedAtEpochMillis: Long? = null,
)

/** Persistence boundary for one relay deployment. Implementations must be crash-safe. */
interface RelayStateStore {
    fun load(poolId: String): List<PersistedRelayLease>
    fun save(poolId: String, leases: List<PersistedRelayLease>)
    fun delete(poolId: String)
}

object NoopRelayStateStore : RelayStateStore {
    override fun load(poolId: String): List<PersistedRelayLease> = emptyList()
    override fun save(poolId: String, leases: List<PersistedRelayLease>) = Unit
    override fun delete(poolId: String) = Unit
}

/**
 * AES-256-GCM file-backed relay state.
 *
 * The relay bearer token is already the deployment's shared secret; a domain-separated SHA-256
 * derivation turns it into an at-rest key without storing another secret. Pool IDs are hashed for
 * filenames and also authenticated as GCM associated data, so moving one pool's ciphertext under
 * another pool's filename fails authentication.
 */
class EncryptedFileRelayStateStore(
    private val directory: Path,
    relayToken: String,
    private val random: SecureRandom = SecureRandom(),
) : RelayStateStore {
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(
            ("aive-compute-relay-state-v1\u0000" + relayToken).toByteArray(StandardCharsets.UTF_8),
        ),
        "AES",
    )

    init {
        require(relayToken.isNotBlank()) { "relayToken must not be blank" }
        Files.createDirectories(directory)
    }

    override fun load(poolId: String): List<PersistedRelayLease> {
        val path = pathFor(poolId)
        if (!Files.exists(path)) return emptyList()
        val bytes = Files.readAllBytes(path)
        require(bytes.size >= MAGIC.size + NONCE_BYTES + 16) { "Relay state file is truncated" }
        require(bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Relay state file has an unknown format"
        }
        val nonce = bytes.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
        val ciphertext = bytes.copyOfRange(MAGIC.size + NONCE_BYTES, bytes.size)
        val plaintext = cipher(Cipher.DECRYPT_MODE, nonce, poolId).doFinal(ciphertext)
        return json.decodeFromString(
            ListSerializer(PersistedRelayLease.serializer()),
            plaintext.toString(StandardCharsets.UTF_8),
        )
    }

    override fun save(poolId: String, leases: List<PersistedRelayLease>) {
        if (leases.isEmpty()) {
            delete(poolId)
            return
        }
        Files.createDirectories(directory)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val plaintext = json.encodeToString(
            ListSerializer(PersistedRelayLease.serializer()),
            leases,
        ).toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce, poolId).doFinal(plaintext)
        val payload = ByteBuffer.allocate(MAGIC.size + nonce.size + ciphertext.size)
            .put(MAGIC)
            .put(nonce)
            .put(ciphertext)
            .array()

        val target = pathFor(poolId)
        val temp = Files.createTempFile(directory, target.fileName.toString(), ".tmp")
        try {
            Files.write(temp, payload)
            restrictOwnerOnly(temp)
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                channel.force(true)
            }
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictOwnerOnly(target)
            forceDirectoryMetadata()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun delete(poolId: String) {
        Files.deleteIfExists(pathFor(poolId))
    }

    fun stateFile(poolId: String): Path = pathFor(poolId)

    private fun cipher(mode: Int, nonce: ByteArray, poolId: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(("pool:" + poolId).toByteArray(StandardCharsets.UTF_8))
        }

    private fun pathFor(poolId: String): Path {
        require(poolId.isNotBlank()) { "poolId must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(poolId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return directory.resolve("$digest.relay")
    }

    private fun forceDirectoryMetadata() {
        // Directory fsync is supported on the Unix filesystems commonly used for relay deployments.
        // Some platforms (notably Windows) reject opening a directory as a channel, so this remains
        // best-effort there while the already-forced file contents and atomic replace still apply.
        runCatching {
            FileChannel.open(directory, StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        }
    }

    private fun restrictOwnerOnly(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
    }

    private companion object {
        val MAGIC: ByteArray = "AIVERLY1".toByteArray(StandardCharsets.US_ASCII)
        const val NONCE_BYTES: Int = 12
        val json: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            classDiscriminator = "type"
        }
    }
}
