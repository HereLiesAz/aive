package com.hereliesaz.geministrator

import androidx.compose.runtime.staticCompositionLocalOf
import com.hereliesaz.geministrator.azphalt.AuthoredWorkflowStore
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.azphalt.SettingsAuthoredWorkflowStore
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.workflow.WorkflowComposer
import com.hereliesaz.geministrator.workflow.WorkflowCompositionService

enum class WorkflowLibraryOrigin {
    Installed,
    Authored,
    Role,
}

data class WorkflowLibraryEntry(
    val key: String,
    val definition: WorkflowDefinition,
    val origin: WorkflowLibraryOrigin,
    val packageId: String? = null,
    val role: RoleDefinition? = null,
)

class WorkflowLibraryHost(
    private val persistence: WorkflowPersistence,
    private val storeService: AzphaltStoreService?,
    private val authoredStore: AuthoredWorkflowStore = SettingsAuthoredWorkflowStore(),
    private val launchWorkflow: suspend (WorkflowDefinition, List<RoleDefinition>) -> Unit,
    private val onPackagesChanged: () -> Unit,
) {
    private val composition = WorkflowCompositionService(persistence)

    suspend fun entries(): List<WorkflowLibraryEntry> {
        val installedPackages = storeService?.installed().orEmpty()
        val packageByDefinition = buildMap<String, String> {
            installedPackages
                .filter { it.kind == "workflow" }
                .forEach { pkg -> pkg.workflowDefinitionIds.forEach { put(it, pkg.packageId) } }
        }
        val authoredIds = authoredStore.all()
        val definitionsById = persistence.definitions.all().associateBy { it.id }

        val installedEntries = packageByDefinition.mapNotNull { (id, packageId) ->
            definitionsById[WorkflowDefinitionId(id)]?.let { definition ->
                WorkflowLibraryEntry(
                    key = "installed:$packageId:$id",
                    definition = definition,
                    origin = WorkflowLibraryOrigin.Installed,
                    packageId = packageId,
                )
            }
        }
        val authoredEntries = authoredIds.mapNotNull { id ->
            definitionsById[id]?.let { definition ->
                WorkflowLibraryEntry(
                    key = "authored:${id.value}",
                    definition = definition,
                    origin = WorkflowLibraryOrigin.Authored,
                )
            }
        }
        val roleEntries = resolveRoleCollection(persistence.roles.all())
            .filter(RoleDefinition::enabled)
            .map { role ->
                WorkflowLibraryEntry(
                    key = "role:${role.id.value}",
                    definition = WorkflowComposer.roleAsWorkflow(role),
                    origin = WorkflowLibraryOrigin.Role,
                    packageId = installedPackages.firstOrNull { pkg -> pkg.roles.any { it.id == role.id } }?.packageId,
                    role = role,
                )
            }

        return (installedEntries + authoredEntries + roleEntries)
            .distinctBy(WorkflowLibraryEntry::key)
            .sortedWith(
                compareBy<WorkflowLibraryEntry>(
                    { it.origin.ordinal },
                    { it.definition.name.lowercase() },
                    { it.definition.id.value },
                ),
            )
    }

    suspend fun installedRoles(): List<RoleDefinition> =
        resolveRoleCollection(persistence.roles.all())
            .filter(RoleDefinition::enabled)
            .sortedBy { it.name.lowercase() }

    suspend fun saveAuthored(definition: WorkflowDefinition): WorkflowDefinition {
        val saved = composition.save(definition)
        authoredStore.add(saved.id)
        return saved
    }

    suspend fun saveAuthoredAs(
        definition: WorkflowDefinition,
        id: WorkflowDefinitionId,
        name: String,
    ): WorkflowDefinition {
        require(id.value.isNotBlank()) { "Workflow id is required" }
        require(name.isNotBlank()) { "Workflow name is required" }
        val installedIds = storeService?.installed().orEmpty()
            .filter { it.kind == "workflow" }
            .flatMap { it.workflowDefinitionIds }
            .toSet()
        require(id.value !in installedIds) { "Cannot overwrite an installed workflow definition" }
        val saved = composition.saveAs(definition, id, name.trim())
        authoredStore.add(saved.id)
        return saved
    }

    suspend fun removeAuthored(id: WorkflowDefinitionId) {
        authoredStore.remove(id)
    }

    suspend fun run(definition: WorkflowDefinition) {
        composition.validated(definition)
        val neededRoleIds = definition.tasks.mapNotNullTo(linkedSetOf()) { task ->
            task.roleId ?: (task.executor as? com.hereliesaz.geministrator.domain.TaskExecutor.RoleAgent)?.roleId
        }
        val packageRoles = storeService?.installed().orEmpty()
            .asSequence()
            .filter { it.kind == "workflow" && definition.id.value in it.workflowDefinitionIds }
            .flatMap { it.roles.asSequence() }
            .filter { it.id in neededRoleIds }
            .distinctBy(RoleDefinition::id)
            .toList()
        launchWorkflow(definition, packageRoles)
    }

    fun packagesChanged() = onPackagesChanged()
}

val LocalWorkflowLibraryHost = staticCompositionLocalOf<WorkflowLibraryHost?> { null }
