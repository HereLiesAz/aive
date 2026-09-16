package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bytes that have already passed container integrity/signature verification. */
data class VerifiedAzphaltPackage(
    val manifest: AzphaltManifest,
    val payload: Map<String, ByteArray>,
    val signed: Boolean,
    val signerPublicKey: String? = null,
)

data class AzphaltWorkflowInstallPlan(
    val packageId: String,
    val version: String,
    val name: String,
    val signed: Boolean,
    val signerPublicKey: String?,
    val definitions: List<WorkflowDefinition>,
    val roles: List<RoleDefinition>,
    val dependencies: List<AzphaltWorkflowDependency>,
    val screens: List<AzphaltWorkflowScreenEntry>,
    val requestedHostPermissions: Set<String>,
)

@Serializable
data class InstalledAzphaltWorkflowPackage(
    val packageId: String,
    val version: String,
    val repositoryUrl: String,
    val workflowDefinitionIds: List<String>,
    val roleIds: List<String>,
    val dependencies: List<AzphaltWorkflowDependency> = emptyList(),
    val approvedHostPermissions: List<String> = emptyList(),
    val signed: Boolean = false,
    val signerPublicKey: String? = null,
    val installedAtEpochMillis: Long,
)

interface AzphaltInstallStore {
    suspend fun get(packageId: String): InstalledAzphaltWorkflowPackage?
    suspend fun all(): List<InstalledAzphaltWorkflowPackage>
    suspend fun put(value: InstalledAzphaltWorkflowPackage)
}

class SettingsAzphaltInstallStore(
    private val settings: Settings = Settings(),
    private val storageKey: String = DEFAULT_STORAGE_KEY,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : AzphaltInstallStore {
    private val mutex = Mutex()

    override suspend fun get(packageId: String): InstalledAzphaltWorkflowPackage? =
        mutex.withLock { readUnlocked().firstOrNull { it.packageId == packageId } }

    override suspend fun all(): List<InstalledAzphaltWorkflowPackage> =
        mutex.withLock { readUnlocked() }

    override suspend fun put(value: InstalledAzphaltWorkflowPackage) {
        mutex.withLock {
            val values = readUnlocked().filterNot { it.packageId == value.packageId } + value
            settings.putString(storageKey, json.encodeToString(ListSerializer, values.sortedBy { it.packageId }))
        }
    }

    private fun readUnlocked(): List<InstalledAzphaltWorkflowPackage> {
        val encoded = settings.getStringOrNull(storageKey) ?: return emptyList()
        return json.decodeFromString(ListSerializer, encoded)
    }

    companion object {
        const val DEFAULT_STORAGE_KEY = "haive.azphalt.installed-workflows.v1"
        private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(
            InstalledAzphaltWorkflowPackage.serializer(),
        )
    }
}

class AzphaltWorkflowPackageInstaller(
    private val persistence: WorkflowPersistence,
    private val installStore: AzphaltInstallStore,
    private val json: Json = Json {
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    },
) {
    /** Validate all Haive-facing semantics and decode every referenced workflow/role before mutation. */
    fun inspect(pkg: VerifiedAzphaltPackage): AzphaltWorkflowInstallPlan {
        val manifest = pkg.manifest
        require(manifest.kind == "workflow") { "Azphalt package ${manifest.id} is not a workflow package" }
        require(manifest.targetApps.isEmpty() || HAIVE_AZPHALT_HOST_ID in manifest.targetApps) {
            "Azphalt package ${manifest.id} targets ${manifest.targetApps.joinToString()}, not $HAIVE_AZPHALT_HOST_ID"
        }
        val workflow = requireNotNull(manifest.workflow) {
            "Workflow package ${manifest.id} does not contain a workflow manifest"
        }
        require(workflow.format == HAIVE_WORKFLOW_FORMAT) {
            "Unsupported workflow format ${workflow.format}; expected $HAIVE_WORKFLOW_FORMAT"
        }
        require(workflow.definitions.isNotEmpty()) { "Workflow package must contain at least one definition" }
        validateEntries(workflow.definitions.map { it.id to it.path }, "workflow definition", manifest.files, pkg.payload)
        validateEntries(workflow.agents.map { it.id to it.path }, "agent role", manifest.files, pkg.payload)
        validateEntries(workflow.screens.map { it.id to it.path }, "screen", manifest.files, pkg.payload)

        val definitions = workflow.definitions.map { entry ->
            decodeUtf8<WorkflowDefinition>(pkg.payload.getValue(entry.path), entry.path).also { definition ->
                require(definition.id.value == entry.id) {
                    "Workflow payload ${entry.path} id ${definition.id.value} does not match manifest id ${entry.id}"
                }
            }
        }
        val roles = workflow.agents.map { entry ->
            decodeUtf8<RoleDefinition>(pkg.payload.getValue(entry.path), entry.path).also { role ->
                require(role.id.value == entry.id) {
                    "Role payload ${entry.path} id ${role.id.value} does not match manifest id ${entry.id}"
                }
            }
        }
        require(definitions.map { it.id }.toSet().size == definitions.size) { "Workflow package contains duplicate definition ids" }
        require(roles.map { it.id }.toSet().size == roles.size) { "Workflow package contains duplicate role ids" }
        require(workflow.dependencies.none { it.id == manifest.id }) { "Workflow package must not depend on itself" }
        val unsupportedPermissions = workflow.hostPermissions.toSet() - SUPPORTED_HOST_PERMISSIONS
        require(unsupportedPermissions.isEmpty()) {
            "Workflow package requests unsupported Haive permissions: ${unsupportedPermissions.sorted().joinToString()}"
        }

        return AzphaltWorkflowInstallPlan(
            packageId = manifest.id,
            version = manifest.version,
            name = manifest.name,
            signed = pkg.signed,
            signerPublicKey = pkg.signerPublicKey,
            definitions = definitions,
            roles = roles,
            dependencies = workflow.dependencies,
            screens = workflow.screens,
            requestedHostPermissions = workflow.hostPermissions.toSet(),
        )
    }

    /**
     * Install a previously inspected package after the user approves its host permissions.
     * `WorkflowLaunch` is recorded as approved but never auto-launches anything here.
     */
    suspend fun install(
        plan: AzphaltWorkflowInstallPlan,
        repositoryUrl: String,
        approvedHostPermissions: Set<String>,
        nowEpochMillis: Long,
    ): InstalledAzphaltWorkflowPackage {
        val unapproved = plan.requestedHostPermissions - approvedHostPermissions
        require(unapproved.isEmpty()) {
            "Host permissions still need approval: ${unapproved.sorted().joinToString()}"
        }
        val previous = installStore.get(plan.packageId)
        val ownedDefinitions = previous?.workflowDefinitionIds.orEmpty().toSet()
        val ownedRoles = previous?.roleIds.orEmpty().toSet()

        plan.definitions.forEach { definition ->
            val existing = persistence.definitions.get(definition.id)
            require(existing == null || definition.id.value in ownedDefinitions) {
                "Workflow id ${definition.id.value} already exists and is not owned by ${plan.packageId}"
            }
        }
        plan.roles.forEach { role ->
            val existing = persistence.roles.get(role.id)
            require(existing == null || role.id.value in ownedRoles) {
                "Role id ${role.id.value} already exists and is not owned by ${plan.packageId}"
            }
        }

        // All decoding, permissions, ownership and collision checks happen above this line.
        plan.roles.forEach { persistence.roles.put(it) }
        plan.definitions.forEach { persistence.definitions.put(it) }
        return InstalledAzphaltWorkflowPackage(
            packageId = plan.packageId,
            version = plan.version,
            repositoryUrl = AzphaltRepositoryClient.normalizeRepositoryUrl(repositoryUrl),
            workflowDefinitionIds = plan.definitions.map { it.id.value },
            roleIds = plan.roles.map { it.id.value },
            dependencies = plan.dependencies,
            approvedHostPermissions = approvedHostPermissions.sorted(),
            signed = plan.signed,
            signerPublicKey = plan.signerPublicKey,
            installedAtEpochMillis = nowEpochMillis,
        ).also { installStore.put(it) }
    }

    private inline fun <reified T> decodeUtf8(bytes: ByteArray, path: String): T = try {
        json.decodeFromString(bytes.decodeToString())
    } catch (failure: Exception) {
        throw IllegalArgumentException("Invalid Haive JSON payload $path: ${failure.message}", failure)
    }

    private fun validateEntries(
        entries: List<Pair<String, String>>,
        label: String,
        files: Map<String, String>,
        payload: Map<String, ByteArray>,
    ) {
        val ids = mutableSetOf<String>()
        entries.forEach { (id, path) ->
            require(id.isNotBlank()) { "$label id must not be blank" }
            require(ids.add(id)) { "Duplicate $label id $id" }
            require(isSafePackagePath(path)) { "Unsafe $label path $path" }
            require(path in files) { "$label payload $path is not listed in manifest.files" }
            require(path in payload) { "$label payload $path is missing from verified package" }
        }
    }

    companion object {
        val SUPPORTED_HOST_PERMISSIONS: Set<String> = setOf("WorkflowRegister", "WorkflowLaunch")

        fun isSafePackagePath(path: String): Boolean =
            path.isNotBlank() &&
                !path.startsWith('/') &&
                path.split('/').none { it == ".." || it.isBlank() }
    }
}
