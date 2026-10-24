@file:OptIn(DelicateCryptographyApi::class)

package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.azphalt.platformAzphaltCryptographyProvider
import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographySystem
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The setup file: one document holding everything the user filled in during setup, so a fresh
 * install can be set up again from it. Settings, projects, workflow definitions and roles are plain
 * JSON in [SetupFileDocument.setupJson]; credentials are sealed under a passphrase in
 * [SetupFileDocument.secrets] (PBKDF2-HMAC-SHA256 -> AES-256-GCM). The plain part is the cipher's
 * associated data, so a wrong passphrase or an edited file fails before anything is written.
 * Run history (runs, events, artifacts, memory contents) is never part of it.
 */

const val SETUP_FILE_EXTENSION: String = ".aivesetup"
const val SETUP_FILE_MIME_TYPE: String = "application/vnd.theaive.setup"
const val SETUP_FILE_FORMAT: String = "aive.setup"
const val SETUP_FILE_FORMAT_VERSION: Int = 1

/** Why a setup file could not be read. The UI maps each to its own message. */
enum class SetupFileFailure {
    NotASetupFile,
    UnsupportedVersion,
    WrongPassphrase,
    CryptoUnavailable,
}

class SetupFileException(val failure: SetupFileFailure, message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** The secret part of a setup file. Never logged; only ever written through the platform's stores. */
@Serializable
data class SetupSecrets(
    /** Provider id ([ProviderCatalog]) to API key. */
    val providerCredentials: Map<String, String> = emptyMap(),
    /** Repository service id ([RepositoryServiceCatalog]) to token. */
    val repositoryCredentials: Map<String, String> = emptyMap(),
    val distributedComputeToken: String? = null,
    val remoteRelayUrl: String? = null,
    /** The platform's own persisted pairing (host token included); opaque to common code. */
    val remoteRelayPairing: String? = null,
) {
    override fun toString(): String =
        "SetupSecrets(providers=${providerCredentials.keys}, repositories=${repositoryCredentials.keys}, " +
            "computeToken=${distributedComputeToken != null}, remoteRelay=${remoteRelayPairing != null})"
}

/** The plain part of a setup file. */
@Serializable
data class SetupFileContents(
    val projects: List<com.hereliesaz.geministrator.domain.Project> = emptyList(),
    val definitions: List<com.hereliesaz.geministrator.domain.WorkflowDefinition> = emptyList(),
    val roles: List<com.hereliesaz.geministrator.domain.RoleDefinition> = emptyList(),
    /** Workflow-store schema the three lists above were written with, so older files migrate. */
    val workflowSchemaVersion: Int = com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION,
    /** Raw values of the setup-only Settings keys (theme, memory layer, Store installs, ...). */
    val settings: Map<String, String> = emptyMap(),
    /** Registered inference models, agents and data descriptors (no records), as JSON. */
    val inferenceRegistrations: String? = null,
    /** Null when the platform has no crash-report toggle. */
    val crashReportingEnabled: Boolean? = null,
)

@Serializable
internal data class SetupFileDocument(
    val format: String = SETUP_FILE_FORMAT,
    val formatVersion: Int = SETUP_FILE_FORMAT_VERSION,
    val createdAtEpochMillis: Long,
    /** [SetupFileContents] as JSON; kept as a string so its exact bytes authenticate. */
    val setupJson: String,
    val secrets: SealedSetupSecrets,
)

@Serializable
internal data class SealedSetupSecrets(
    val kdf: String = KDF_NAME,
    val iterations: Int,
    val salt: String,
    val cipher: String = CIPHER_NAME,
    val iv: String,
    val ciphertext: String,
)

/** A decoded, authenticated setup file. Nothing has been written yet. */
data class DecodedSetupFile(
    val createdAtEpochMillis: Long,
    val contents: SetupFileContents,
    val secrets: SetupSecrets,
)

private const val KDF_NAME = "PBKDF2-HMAC-SHA256"
private const val CIPHER_NAME = "AES-256-GCM"
private const val SALT_BYTES = 16
private const val IV_BYTES = 12
private const val MIN_ITERATIONS = 310_000
/** Upper bound on a file's own iteration count, so a crafted file cannot hang the import. */
private const val MAX_ITERATIONS = 10_000_000

object SetupFileCodec {
    const val ITERATIONS: Int = MIN_ITERATIONS

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        allowStructuredMapKeys = true
    }

    suspend fun encode(
        contents: SetupFileContents,
        secrets: SetupSecrets,
        passphrase: String,
        createdAtEpochMillis: Long,
    ): String {
        require(passphrase.isNotEmpty()) { "A passphrase is required" }
        val setupJson = json.encodeToString(SetupFileContents.serializer(), contents)
        val random = CryptographySystem.getDefaultRandom()
        val salt = random.nextBytes(SALT_BYTES)
        val iv = random.nextBytes(IV_BYTES)
        val ciphertext = crypto {
            aesGcm(passphrase, salt, ITERATIONS).encryptWithIv(
                iv,
                json.encodeToString(SetupSecrets.serializer(), secrets).encodeToByteArray(),
                setupJson.encodeToByteArray(),
            )
        }
        return json.encodeToString(
            SetupFileDocument.serializer(),
            SetupFileDocument(
                createdAtEpochMillis = createdAtEpochMillis,
                setupJson = setupJson,
                secrets = SealedSetupSecrets(
                    iterations = ITERATIONS,
                    salt = Base64.encode(salt),
                    iv = Base64.encode(iv),
                    ciphertext = Base64.encode(ciphertext),
                ),
            ),
        )
    }

    /** Validates and decrypts a setup file; throws [SetupFileException] without side effects. */
    suspend fun decode(encoded: String, passphrase: String): DecodedSetupFile {
        val document = try {
            json.decodeFromString(SetupFileDocument.serializer(), encoded)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw SetupFileException(SetupFileFailure.NotASetupFile, "Not a The Aive setup file: ${failure.detail()}", failure)
        }
        if (document.format != SETUP_FILE_FORMAT) {
            throw SetupFileException(SetupFileFailure.NotASetupFile, "Not a The Aive setup file (format=${document.format})")
        }
        if (document.formatVersion > SETUP_FILE_FORMAT_VERSION || document.formatVersion < 1) {
            throw SetupFileException(
                SetupFileFailure.UnsupportedVersion,
                "Setup file version ${document.formatVersion} is not supported (max $SETUP_FILE_FORMAT_VERSION)",
            )
        }
        val sealed = document.secrets
        if (sealed.kdf != KDF_NAME || sealed.cipher != CIPHER_NAME || sealed.iterations !in 1..MAX_ITERATIONS) {
            throw SetupFileException(SetupFileFailure.UnsupportedVersion, "Setup file encryption is not supported")
        }
        val (salt, iv, ciphertext) = try {
            Triple(Base64.decode(sealed.salt), Base64.decode(sealed.iv), Base64.decode(sealed.ciphertext))
        } catch (failure: IllegalArgumentException) {
            throw SetupFileException(SetupFileFailure.NotASetupFile, "Setup file encoding is damaged", failure)
        }
        val contents = try {
            json.decodeFromString(SetupFileContents.serializer(), document.setupJson)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw SetupFileException(SetupFileFailure.NotASetupFile, "Setup file settings are unreadable: ${failure.detail()}", failure)
        }
        val cipher = crypto { aesGcm(passphrase, salt, sealed.iterations) }
        val plaintext = run {
            try {
                cipher.decryptWithIv(iv, ciphertext, document.setupJson.encodeToByteArray())
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                // GCM cannot tell a wrong passphrase from a tampered file; both mean "do not trust it".
                throw SetupFileException(SetupFileFailure.WrongPassphrase, "Wrong passphrase or damaged setup file", failure)
            }
        }
        val secrets = try {
            json.decodeFromString(SetupSecrets.serializer(), plaintext.decodeToString())
        } catch (failure: Exception) {
            throw SetupFileException(SetupFileFailure.NotASetupFile, "Setup file credentials are unreadable", failure)
        }
        return DecodedSetupFile(document.createdAtEpochMillis, contents, secrets)
    }

    private suspend fun aesGcm(passphrase: String, salt: ByteArray, iterations: Int) = run {
        val provider = platformAzphaltCryptographyProvider()
        val keyBytes = provider.get(PBKDF2)
            .secretDerivation(SHA256, iterations, 256.bits, salt)
            .deriveSecretToByteArray(passphrase.encodeToByteArray())
        provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes).cipher()
    }

    /**
     * Maps a missing algorithm (a platform without PBKDF2/AES-GCM; the library throws
     * [IllegalStateException]) to [SetupFileFailure.CryptoUnavailable].
     */
    private inline fun <T> crypto(block: () -> T): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: IllegalStateException) {
        throw SetupFileException(SetupFileFailure.CryptoUnavailable, "Setup file encryption is not available here", failure)
    }
}

/**
 * The parser's own reason, shortened, for the import error's details. Only for the plain part of a
 * file: a message about decrypted credentials could quote them.
 */
internal fun Throwable.detail(): String = (message ?: this::class.simpleName ?: "unknown").take(300)

fun String.asSetupFileName(): String {
    val clean = trim()
        .ifEmpty { "aive-setup" }
        .replace(Regex("[\\/:*?\"<>|]+"), "-")
        .trim('.', ' ')
        .ifEmpty { "aive-setup" }
    return if (clean.endsWith(SETUP_FILE_EXTENSION, ignoreCase = true)) clean else clean + SETUP_FILE_EXTENSION
}
