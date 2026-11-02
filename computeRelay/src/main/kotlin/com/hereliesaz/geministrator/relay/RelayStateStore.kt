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
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
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
    /** Absent in files written before it existed; restored leases then count from the restore. */
    val acceptedAtEpochMillis: Long? = null,
)

/**
 * A node enrolled with a pool: its public key, revocation and the work attributed to it. Kept apart
 * from lease state because it must outlive every lease: a revocation that a restart forgot would let
 * the revoked device enrol again with the pool token.
 */
@Serializable
data class PersistedRelayNode(
    val nodeId: String,
    val displayName: String,
    /** Base64 X.509 SubjectPublicKeyInfo, P-256. */
    val publicKey: String,
    val enrolledAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long? = null,
    val revokedAtEpochMillis: Long? = null,
    val revokedByNodeId: String? = null,
    val leasesCompleted: Int = 0,
    val leasesFailed: Int = 0,
)

/** Persistence boundary for one relay deployment. Implementations must be crash-safe. */
interface RelayStateStore {
    fun load(poolId: String): List<PersistedRelayLease>
    fun save(poolId: String, leases: List<PersistedRelayLease>)
    fun delete(poolId: String)

    fun loadNodes(poolId: String): List<PersistedRelayNode> = emptyList()
    fun saveNodes(poolId: String, nodes: List<PersistedRelayNode>) = Unit
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
    init {
        require(relayToken.isNotBlank()) { "relayToken must not be blank" }
        Files.createDirectories(directory)
    }

    /**
     * Derived with PBKDF2 from the token and a random per-directory salt kept beside the state, so
     * state files from different deployments do not share a key and a weak token costs an offline
     * attacker the iteration count per guess instead of one hash.
     */
    private val key = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(
            PBEKeySpec(relayToken.toCharArray(), loadOrCreateSalt(), PBKDF2_ITERATIONS, 256),
        ).encoded,
        "AES",
    )

    /** The pre-salt key: files written by earlier versions still load, and are rewritten under [key]. */
    private val legacyKey = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(
            ("aive-compute-relay-state-v1\u0000" + relayToken).toByteArray(StandardCharsets.UTF_8),
        ),
        "AES",
    )

    private fun loadOrCreateSalt(): ByteArray {
        val path = directory.resolve(SALT_FILE)
        if (Files.exists(path)) {
            return Files.readAllBytes(path).also { require(it.size == SALT_BYTES) { "Relay state salt is corrupt" } }
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        try {
            Files.write(path, salt, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            restrictOwnerOnly(path)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            return Files.readAllBytes(path)
        }
        return salt
    }

    override fun load(poolId: String): List<PersistedRelayLease> {
        val plaintext = readSealed(pathFor(poolId), "pool:" + poolId) ?: return emptyList()
        return json.decodeFromString(
            ListSerializer(PersistedRelayLease.serializer()),
            plaintext.toString(StandardCharsets.UTF_8),
        )
    }

    override fun loadNodes(poolId: String): List<PersistedRelayNode> {
        val plaintext = readSealed(nodesPathFor(poolId), "nodes:" + poolId) ?: return emptyList()
        return json.decodeFromString(
            ListSerializer(PersistedRelayNode.serializer()),
            plaintext.toString(StandardCharsets.UTF_8),
        )
    }

    override fun saveNodes(poolId: String, nodes: List<PersistedRelayNode>) {
        if (nodes.isEmpty()) {
            if (Files.deleteIfExists(nodesPathFor(poolId))) forceDirectoryMetadata()
            return
        }
        writeSealed(
            nodesPathFor(poolId),
            "nodes:" + poolId,
            json.encodeToString(ListSerializer(PersistedRelayNode.serializer()), nodes)
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun readSealed(path: Path, associatedData: String): ByteArray? {
        if (!Files.exists(path)) return null
        val bytes = Files.readAllBytes(path)
        require(bytes.size >= MAGIC.size + NONCE_BYTES + 16) { "Relay state file is truncated" }
        require(bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Relay state file has an unknown format"
        }
        val nonce = bytes.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
        val ciphertext = bytes.copyOfRange(MAGIC.size + NONCE_BYTES, bytes.size)
        return try {
            cipher(Cipher.DECRYPT_MODE, nonce, associatedData, key).doFinal(ciphertext)
        } catch (current: javax.crypto.AEADBadTagException) {
            try {
                cipher(Cipher.DECRYPT_MODE, nonce, associatedData, legacyKey).doFinal(ciphertext)
            } catch (_: javax.crypto.AEADBadTagException) {
                throw current
            }
        }
    }

    override fun save(poolId: String, leases: List<PersistedRelayLease>) {
        if (leases.isEmpty()) {
            delete(poolId)
            return
        }
        writeSealed(
            pathFor(poolId),
            "pool:" + poolId,
            json.encodeToString(
                ListSerializer(PersistedRelayLease.serializer()),
                leases,
            ).toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun writeSealed(target: Path, associatedData: String, plaintext: ByteArray) {
        Files.createDirectories(directory)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce, associatedData, key).doFinal(plaintext)
        val payload = ByteBuffer.allocate(MAGIC.size + nonce.size + ciphertext.size)
            .put(MAGIC)
            .put(nonce)
            .put(ciphertext)
            .array()

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
        if (Files.deleteIfExists(pathFor(poolId))) {
            forceDirectoryMetadata()
        }
    }

    fun stateFile(poolId: String): Path = pathFor(poolId)

    fun nodesFile(poolId: String): Path = nodesPathFor(poolId)

    private fun cipher(mode: Int, nonce: ByteArray, associatedData: String, key: SecretKeySpec): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        }

    private fun pathFor(poolId: String): Path = directory.resolve(poolDigest(poolId) + ".relay")

    private fun nodesPathFor(poolId: String): Path = directory.resolve(poolDigest(poolId) + ".nodes")

    private fun poolDigest(poolId: String): String {
        require(poolId.isNotBlank()) { "poolId must not be blank" }
        return MessageDigest.getInstance("SHA-256")
            .digest(poolId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
        const val SALT_BYTES: Int = 16
        const val SALT_FILE: String = "state.salt"
        const val PBKDF2_ITERATIONS: Int = 210_000
        val json: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            // WorkflowRun.taskRuns is keyed by TaskDefinitionId, a structured (non-primitive) key.
            allowStructuredMapKeys = true
        }
    }
}
