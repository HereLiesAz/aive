package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.memory.MemoryLayerSettings
import com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore
import com.hereliesaz.geministrator.persistence.PersistenceSnapshot
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.persistence.migrate
import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The platform's credential stores as the setup file sees them. Android and desktop wrap their own
 * Keystore/Keychain stores; a platform without one passes null and its setup files carry no secrets.
 */
interface SetupSecretStore {
    fun read(): SetupSecrets

    /**
     * Writes every value present in [secrets] through the real stores, replacing the value for the
     * same id and leaving other ids alone, then reloads whatever the host derived from them (the
     * provider registry), so imported providers register immediately.
     */
    fun write(secrets: SetupSecrets)
}

/** What an import wrote, for the summary shown afterwards. */
data class SetupImportSummary(
    val projects: Int,
    val definitions: Int,
    val roles: Int,
    val settings: Int,
    val providers: Int,
    val repositories: Int,
    val computeToken: Boolean,
    val remoteRelay: Boolean,
)

/**
 * Collects and applies the plain part of a setup file. Only setup is touched: workflow runs,
 * events, artifacts, approval gates, memory contents and UI drafts are neither read nor written.
 * Imports merge: an imported project, definition, role or setting replaces the one with the same
 * id or key, and everything the file does not mention is left alone.
 */
class SetupFileTransfer(
    private val persistence: WorkflowPersistence,
    private val settings: Settings = Settings(),
    private val inferenceSettings: Settings = persistence.inferenceSettings,
) {
    suspend fun collect(crashReportingEnabled: Boolean?): SetupFileContents = SetupFileContents(
        projects = persistence.projects.all(),
        definitions = persistence.definitions.all(),
        roles = persistence.roles.all(),
        settings = settings.keys
            .filter(::isSetupSettingsKey)
            .sorted()
            .mapNotNull { key -> settings.getStringOrNull(key)?.let { key to it } }
            .toMap(),
        inferenceRegistrations = inferenceSettings.getStringOrNull(INFERENCE_STATE_KEY)
            ?.let { raw -> runCatching { inferenceRegistrationsOf(json.parseToJsonElement(raw).jsonObject) }.getOrNull() }
            ?.toString(),
        crashReportingEnabled = crashReportingEnabled,
    )

    /**
     * Validates everything first (schema migration, inference JSON), then writes. A file that fails
     * validation writes nothing.
     */
    suspend fun apply(contents: SetupFileContents): SetupImportSummary {
        if (contents.workflowSchemaVersion > SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION) {
            throw SetupFileException(
                SetupFileFailure.UnsupportedVersion,
                "Setup file workflow schema ${contents.workflowSchemaVersion} is not supported",
            )
        }
        val workflow = migrate(
            PersistenceSnapshot(
                version = contents.workflowSchemaVersion,
                projects = contents.projects,
                definitions = contents.definitions,
                roles = contents.roles,
            ),
        )
        val importedSettings = contents.settings.filterKeys(::isSetupSettingsKey)
        val importedInference = contents.inferenceRegistrations?.let { raw ->
            runCatching { inferenceRegistrationsOf(json.parseToJsonElement(raw).jsonObject) }.getOrElse { failure ->
                throw SetupFileException(SetupFileFailure.NotASetupFile, "Setup file inference data is unreadable: ${failure.detail()}", failure)
            }
        }

        // Roles and definitions before projects, so a project never points at a missing workflow.
        workflow.roles.forEach { persistence.roles.put(it) }
        workflow.definitions.forEach { persistence.definitions.put(it) }
        workflow.projects.forEach { persistence.projects.put(it) }
        importedSettings.forEach { (key, value) -> settings.putString(key, value) }
        importedInference?.let(::mergeInferenceRegistrations)
        if (GROUND_KEY in importedSettings) Azphalt.reloadGround()

        return SetupImportSummary(
            projects = workflow.projects.size,
            definitions = workflow.definitions.size,
            roles = workflow.roles.size,
            settings = importedSettings.size + if (importedInference != null) 1 else 0,
            providers = 0,
            repositories = 0,
            computeToken = false,
            remoteRelay = false,
        )
    }

    private fun mergeInferenceRegistrations(imported: JsonObject) {
        val existing = inferenceSettings.getStringOrNull(INFERENCE_STATE_KEY)
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        val merged = existing.toMutableMap()
        if ("version" !in merged) imported["version"]?.let { merged["version"] = it }
        INFERENCE_REGISTRATION_IDS.forEach { (listKey, idKey) ->
            val incoming = (imported[listKey] as? JsonArray).orEmpty()
            if (incoming.isEmpty()) return@forEach
            val incomingIds = incoming.mapNotNull { it.idOf(idKey) }.toSet()
            val kept = (existing[listKey] as? JsonArray).orEmpty().filterNot { it.idOf(idKey) in incomingIds }
            merged[listKey] = JsonArray(kept + incoming)
        }
        inferenceSettings.putString(INFERENCE_STATE_KEY, JsonObject(merged).toString())
    }

    companion object {
        /** The memory-layer settings a file carries, for hosts that hold them in a live controller. */
        fun importedMemorySettings(contents: SetupFileContents): MemoryLayerSettings? =
            contents.settings[MemoryLayerSettingsStore.DEFAULT_KEY]?.let { raw ->
                runCatching { json.decodeFromString(MemoryLayerSettings.serializer(), raw) }.getOrNull()
            }

        internal fun isSetupSettingsKey(key: String): Boolean =
            key in SETUP_SETTINGS_KEYS || key.startsWith(TERRARIUM_LAYOUT_PREFIX)

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

private const val GROUND_KEY = "aive.appearance.ground.v1"
private const val INFERENCE_STATE_KEY = "haive.inference.fabric.v1"
private const val TERRARIUM_LAYOUT_PREFIX = "haive.terrarium.layout.v1."

/**
 * Settings keys that hold setup, each owned by its store: theme ground, memory layer settings,
 * distributed compute configuration (its token is a secret, kept elsewhere), add-on installs,
 * authored workflows, Azphalt installs, publisher pins and installed Store models.
 */
internal val SETUP_SETTINGS_KEYS: Set<String> = setOf(
    GROUND_KEY,
    MemoryLayerSettingsStore.DEFAULT_KEY,
    com.hereliesaz.geministrator.distributed.SettingsDistributedComputeConfigurationStore.DEFAULT_STORAGE_KEY,
    "addon_state",
    "haive.workflows.authored.v1",
    "haive.azphalt.installed-workflows.v1",
    "haive.azphalt.publisher-pins.v1",
    "aive.azphalt.installed-llms.v1",
)

/** Inference registration lists and the field that identifies an entry; records are run history. */
private val INFERENCE_REGISTRATION_IDS = listOf(
    "models" to "logicalModelId",
    "agents" to "providerId",
    "data" to "dataId",
)

private fun inferenceRegistrationsOf(state: JsonObject): JsonObject = JsonObject(
    buildMap {
        state["version"]?.let { put("version", it) }
        INFERENCE_REGISTRATION_IDS.forEach { (listKey, _) -> state[listKey]?.let { put(listKey, it) } }
    },
)

private fun kotlinx.serialization.json.JsonElement.idOf(idKey: String): String? =
    ((this as? JsonObject)?.get(idKey) as? JsonPrimitive)?.contentOrNull

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/** Builds and encrypts a setup file off the main thread (PBKDF2 takes a moment). */
internal suspend fun exportSetupFile(
    persistence: WorkflowPersistence,
    secretStore: SetupSecretStore?,
    crashReportingEnabled: Boolean?,
    passphrase: String,
): String {
    val contents = SetupFileTransfer(persistence).collect(crashReportingEnabled)
    val secrets = secretStore?.read() ?: SetupSecrets()
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        SetupFileCodec.encode(contents, secrets, passphrase, kotlin.time.Clock.System.now().toEpochMilliseconds())
    }
}

/**
 * Decrypts and validates [encoded] first; a wrong passphrase, foreign file or newer format throws
 * [SetupFileException] with nothing written. Then writes the plain part, the credentials (through
 * [secretStore], which rebuilds the provider registry) and the settings held by live objects.
 */
internal suspend fun importSetupFile(
    persistence: WorkflowPersistence,
    secretStore: SetupSecretStore?,
    encoded: String,
    passphrase: String,
    onCrashReportingChange: ((Boolean) -> Unit)?,
    memoryLayer: com.hereliesaz.geministrator.memory.MemoryLayerController?,
): SetupImportSummary {
    val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        SetupFileCodec.decode(encoded, passphrase)
    }
    val summary = SetupFileTransfer(persistence).apply(decoded.contents)
    val secrets = decoded.secrets
    secretStore?.write(secrets)
    decoded.contents.crashReportingEnabled?.let { onCrashReportingChange?.invoke(it) }
    SetupFileTransfer.importedMemorySettings(decoded.contents)?.let { imported ->
        memoryLayer?.updateSettings { imported }
    }
    if (secretStore == null) return summary
    return summary.copy(
        providers = secrets.providerCredentials.size,
        repositories = secrets.repositoryCredentials.size,
        computeToken = secrets.distributedComputeToken != null,
        remoteRelay = secrets.remoteRelayPairing != null,
    )
}
