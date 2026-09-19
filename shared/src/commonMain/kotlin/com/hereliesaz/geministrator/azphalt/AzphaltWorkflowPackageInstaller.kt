package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.workflow.WorkflowFragment
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bytes that have already passed container integrity and embedded-signature verification. */
data class VerifiedAzphaltPackage internal constructor(
    val manifest: AzphaltManifest,
    val payload: Map<String, ByteArray>,
    val signed: Boolean,
    val signerPublicKey: String? = null,
)

@Serializable
data class InstalledAzphaltWorkflowScreen(
    val id: String,
    val name: String? = null,
    val path: String,
    val placements: List<String> = emptyList(),
    val payloadText: String,
)

data class AzphaltWorkflowInstallPlan(
    val packageId: String,
    val packageKind: String,
    val version: String,
    val name: String,
    val signed: Boolean,
    val signerPublicKey: String?,
    val trusted: Boolean,
    val trustReason: String,
    val publisherChanged: Boolean,
    val pinnedPublisherKey: String?,
    val definitions: List<WorkflowDefinition>,
    val fragments: List<WorkflowFragment>,
    val roles: List<RoleDefinition>,
    val dependencies: List<AzphaltWorkflowDependency>,
    val screens: List<InstalledAzphaltWorkflowScreen>,
    val requestedHostPermissions: Set<String>,
    val unsupportedHostPermissions: Set<String>,
)

@Serializable
data class InstalledAzphaltWorkflowPackage(
    val packageId: String,
    /** `workflow` or `role`. Defaults for records written before standalone role packages existed. */
    val kind: String = "workflow",
    val version: String,
    val repositoryUrl: String,
    val workflowDefinitionIds: List<String> = emptyList(),
    /** Reusable subgraphs exported by this package for workflow composition. */
    val fragments: List<WorkflowFragment> = emptyList(),
    /** Roles are globally reusable after install regardless of which package kind supplied them. */
    val roles: List<RoleDefinition> = emptyList(),
    val screens: List<InstalledAzphaltWorkflowScreen> = emptyList(),
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
    private val json: Json = SettingsWorkflowPersistence.defaultJson,
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
        // Kept for on-device compatibility; this store now records both workflow and role packages.
        const val DEFAULT_STORAGE_KEY = "haive.azphalt.installed-workflows.v1"
        private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(
            InstalledAzphaltWorkflowPackage.serializer(),
        )
    }
}

class AzphaltWorkflowPackageInstaller(
    private val persistence: WorkflowPersistence,
    private val installStore: AzphaltInstallStore,
    private val publisherPins: AzphaltPublisherPinStore = SettingsAzphaltPublisherPinStore(),
    private val json: Json = SettingsWorkflowPersistence.defaultJson,
) {
    /** Validate all Haive-facing semantics and decode every referenced payload before mutation. */
    fun inspect(verification: AzphaltPackageVerification): AzphaltWorkflowInstallPlan {
        val pkg = verification.packageContents
        val manifest = pkg.manifest
        require(manifest.kind == "workflow" || manifest.kind == "role") {
            "Azphalt package ${manifest.id} is kind ${manifest.kind}; Haive installs workflow and role packages"
        }
        require(manifest.targetApps.isEmpty() || HAIVE_AZPHALT_HOST_ID in manifest.targetApps) {
            "Azphalt package ${manifest.id} targets ${manifest.targetApps.joinToString()}, not $HAIVE_AZPHALT_HOST_ID"
        }

        val definitions: List<WorkflowDefinition>
        val fragments: List<WorkflowFragment>
        val roles: List<RoleDefinition>
        val dependencies: List<AzphaltWorkflowDependency>
        val screens: List<InstalledAzphaltWorkflowScreen>
        val requested: Set<String>

        if (manifest.kind == "workflow") {
            val conformanceErrors = AzphaltWorkflowConformance.validate(manifest)
            require(conformanceErrors.isEmpty()) {
                "Invalid Azphalt workflow package ${manifest.id}: ${conformanceErrors.joinToString("; ")}"
            }
            val workflow = requireNotNull(manifest.workflow) {
                "Workflow package ${manifest.id} does not contain a workflow manifest"
            }
            require(workflow.format == HAIVE_WORKFLOW_FORMAT) {
                "Unsupported workflow format ${workflow.format}; expected $HAIVE_WORKFLOW_FORMAT"
            }
            require(workflow.definitions.isNotEmpty()) { "Workflow package must contain at least one definition" }
            validateEntries(workflow.definitions.map { it.id to it.path }, "workflow definition", manifest.files, pkg.payload)
            validateEntries(workflow.fragments.map { it.id to it.path }, "workflow fragment", manifest.files, pkg.payload)
            validateEntries(workflow.agents.map { it.id to it.path }, "agent role", manifest.files, pkg.payload)
            validateEntries(workflow.screens.map { it.id to it.path }, "screen", manifest.files, pkg.payload)

            definitions = workflow.definitions.map { entry ->
                decodeUtf8<WorkflowDefinition>(pkg.payload.getValue(entry.path), entry.path).also { definition ->
                    require(definition.id.value == entry.id) {
                        "Workflow payload ${entry.path} id ${definition.id.value} does not match manifest id ${entry.id}"
                    }
                }
            }
            fragments = workflow.fragments.map { entry ->
                decodeUtf8<WorkflowFragment>(pkg.payload.getValue(entry.path), entry.path).also { fragment ->
                    require(fragment.id.value == entry.id) {
                        "Workflow fragment ${entry.path} id ${fragment.id.value} does not match manifest id ${entry.id}"
                    }
                }
            }
            roles = workflow.agents.map { entry ->
                decodeUtf8<RoleDefinition>(pkg.payload.getValue(entry.path), entry.path).also { role ->
                    require(role.id.value == entry.id) {
                        "Role payload ${entry.path} id ${role.id.value} does not match manifest id ${entry.id}"
                    }
                }
            }
            screens = workflow.screens.map { entry ->
                val text = pkg.payload.getValue(entry.path).decodeToString()
                if (entry.path.endsWith(".json", ignoreCase = true)) {
                    try {
                        json.parseToJsonElement(text)
                    } catch (failure: Exception) {
                        throw IllegalArgumentException("Invalid declarative screen JSON ${entry.path}: ${failure.message}", failure)
                    }
                }
                InstalledAzphaltWorkflowScreen(
                    id = entry.id,
                    name = entry.name,
                    path = entry.path,
                    placements = entry.placements,
                    payloadText = text,
                )
            }
            dependencies = workflow.dependencies
            requested = workflow.hostPermissions.toSet()
        } else {
            val conformanceErrors = AzphaltRoleConformance.validate(manifest)
            require(conformanceErrors.isEmpty()) {
                "Invalid Azphalt role package ${manifest.id}: ${conformanceErrors.joinToString("; ")}"
            }
            val roleManifest = requireNotNull(manifest.role) {
                "Role package ${manifest.id} does not contain a role manifest"
            }
            require(roleManifest.format == HAIVE_ROLE_FORMAT) {
                "Unsupported role format ${roleManifest.format}; expected $HAIVE_ROLE_FORMAT"
            }
            require(roleManifest.roles.isNotEmpty()) { "Role package must contain at least one role" }
            validateEntries(roleManifest.roles.map { it.id to it.path }, "swarm role", manifest.files, pkg.payload)
            roles = roleManifest.roles.map { entry ->
                decodeUtf8<RoleDefinition>(pkg.payload.getValue(entry.path), entry.path).also { role ->
                    require(role.id.value == entry.id) {
                        "Role payload ${entry.path} id ${role.id.value} does not match manifest id ${entry.id}"
                    }
                }
            }
            definitions = emptyList()
            fragments = emptyList()
            dependencies = emptyList()
            screens = emptyList()
            requested = emptySet()
        }

        require(definitions.map { it.id }.toSet().size == definitions.size) { "Package contains duplicate definition ids" }
        require(fragments.map { it.id }.toSet().size == fragments.size) { "Package contains duplicate fragment ids" }
        require(roles.map { it.id }.toSet().size == roles.size) { "Package contains duplicate role ids" }

        return AzphaltWorkflowInstallPlan(
            packageId = manifest.id,
            packageKind = manifest.kind,
            version = manifest.version,
            name = manifest.name,
            signed = pkg.signed,
            signerPublicKey = pkg.signerPublicKey,
            trusted = verification.trusted,
            trustReason = verification.trustReason,
            publisherChanged = verification.publisherChanged,
            pinnedPublisherKey = verification.pinnedPublisherKey,
            definitions = definitions,
            fragments = fragments,
            roles = roles,
            dependencies = dependencies,
            screens = screens,
            requestedHostPermissions = requested,
            unsupportedHostPermissions = requested - SUPPORTED_HOST_PERMISSIONS,
        )
    }

    /**
     * Install a previously inspected package after explicit trust and host-permission decisions.
     * Workflow definitions enter the shared workflow library, and every supplied role enters the
     * shared role library so it can be assigned by any installed or authored workflow.
     */
    suspend fun install(
        plan: AzphaltWorkflowInstallPlan,
        repositoryUrl: String,
        approvedHostPermissions: Set<String>,
        nowEpochMillis: Long,
        allowUntrustedSigner: Boolean = false,
        allowPublisherChange: Boolean = false,
    ): InstalledAzphaltWorkflowPackage {
        require(approvedHostPermissions.all { it in plan.requestedHostPermissions && it in SUPPORTED_HOST_PERMISSIONS }) {
            "Approved host permissions must be a supported subset of the package request"
        }
        require(!plan.publisherChanged || allowPublisherChange) {
            "Publisher key changed for ${plan.packageId}; explicit publisher-change approval is required"
        }
        require(!plan.signed || plan.trusted || allowUntrustedSigner) {
            "Signed package publisher is not trusted: ${plan.trustReason}"
        }

        val previous = installStore.get(plan.packageId)
        if (plan.packageKind == "workflow") {
            val ownedDefinitions = previous?.takeIf { it.kind == "workflow" }?.workflowDefinitionIds.orEmpty().toSet()
            plan.definitions.forEach { definition ->
                val existing = persistence.definitions.get(definition.id)
                require(existing == null || definition.id.value in ownedDefinitions) {
                    "Workflow id ${definition.id.value} already exists and is not owned by ${plan.packageId}"
                }
            }
            plan.definitions.forEach { persistence.definitions.put(it) }
        }

        installReusableRoles(plan, previous)

        val installed = InstalledAzphaltWorkflowPackage(
            packageId = plan.packageId,
            kind = plan.packageKind,
            version = plan.version,
            repositoryUrl = AzphaltRepositoryClient.normalizeRepositoryUrl(repositoryUrl),
            workflowDefinitionIds = plan.definitions.map { it.id.value },
            fragments = plan.fragments,
            roles = plan.roles,
            screens = plan.screens,
            dependencies = plan.dependencies,
            approvedHostPermissions = approvedHostPermissions.sorted(),
            signed = plan.signed,
            signerPublicKey = plan.signerPublicKey,
            installedAtEpochMillis = nowEpochMillis,
        )
        installStore.put(installed)
        if (plan.signerPublicKey != null && (plan.pinnedPublisherKey == null || allowPublisherChange)) {
            publisherPins.pin(plan.packageId, plan.signerPublicKey)
        }
        return installed
    }

    private suspend fun installReusableRoles(
        plan: AzphaltWorkflowInstallPlan,
        previous: InstalledAzphaltWorkflowPackage?,
    ) {
        if (plan.roles.isEmpty()) return
        val builtInsById = BuiltInRoles.all.associateBy(RoleDefinition::id)
        val previouslyOwned = previous?.roles.orEmpty().associateBy(RoleDefinition::id)

        plan.roles.forEach { role ->
            val builtIn = builtInsById[role.id]
            require(builtIn == null || sameReusableRole(builtIn, role)) {
                "Role id ${role.id.value} collides with a different built-in Haive role"
            }
            val existing = persistence.roles.get(role.id)
            val previousRole = previouslyOwned[role.id]
            require(
                existing == null ||
                    sameReusableRole(existing, role) ||
                    (previousRole != null && sameReusableRole(existing, previousRole))
            ) {
                "Role id ${role.id.value} already exists with a different reusable-role definition"
            }
        }

        plan.roles.forEach { role ->
            val existing = persistence.roles.get(role.id)
            val preferredProvider = existing?.preferredProviderId ?: role.preferredProviderId
            persistence.roles.put(role.copy(enabled = true, preferredProviderId = preferredProvider))
        }
    }

    private fun sameReusableRole(left: RoleDefinition, right: RoleDefinition): Boolean =
        left.copy(enabled = true, preferredProviderId = null) ==
            right.copy(enabled = true, preferredProviderId = null)

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
                '\\' !in path &&
                path.split('/').none { it.isBlank() || it == "." || it == ".." }
    }
}
