package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.workflow.WorkflowComposer
import com.hereliesaz.geministrator.workflow.WorkflowFragment

enum class WorkflowCompositionComponentKind {
    Workflow,
    Fragment,
    Role,
}

data class WorkflowCompositionComponent(
    val id: String,
    val name: String,
    val description: String?,
    val kind: WorkflowCompositionComponentKind,
    val packageId: String? = null,
    val workflow: WorkflowDefinition? = null,
    val fragment: WorkflowFragment? = null,
    val role: RoleDefinition? = null,
)

/**
 * Unified palette used by workflow authoring. Installed workflows and exported fragments are
 * composable components; every role is also exposed as its implicit one-node workflow.
 *
 * Package discovery is injected so the same composer works for Store-backed, sideloaded, and
 * user-authored definitions without making the workflow layer depend on repository transport.
 */
class AzphaltCompositionLibrary(
    private val persistence: WorkflowPersistence,
    private val installedPackages: suspend () -> List<InstalledAzphaltWorkflowPackage>,
) {
    suspend fun components(): List<WorkflowCompositionComponent> {
        val packages = installedPackages()
        val packageByDefinitionId = buildMap<String, String> {
            packages
                .filter { it.kind == "workflow" }
                .forEach { pkg -> pkg.workflowDefinitionIds.forEach { put(it, pkg.packageId) } }
        }
        val installedDefinitionIds = packageByDefinitionId.keys
        val definitions = persistence.definitions.all()
            .filter { it.id.value in installedDefinitionIds }
            .map { definition ->
                WorkflowCompositionComponent(
                    id = "workflow:${definition.id.value}",
                    name = definition.name,
                    description = definition.description,
                    kind = WorkflowCompositionComponentKind.Workflow,
                    packageId = packageByDefinitionId[definition.id.value],
                    workflow = definition,
                )
            }
        val fragments = packages
            .filter { it.kind == "workflow" }
            .flatMap { pkg ->
                pkg.fragments.map { fragment ->
                    WorkflowCompositionComponent(
                        id = "fragment:${pkg.packageId}:${fragment.id.value}",
                        name = fragment.name,
                        description = "Reusable workflow fragment from ${pkg.packageId}",
                        kind = WorkflowCompositionComponentKind.Fragment,
                        packageId = pkg.packageId,
                        fragment = fragment,
                    )
                }
            }
        val packageByRoleId = buildMap<String, String> {
            packages.forEach { pkg ->
                pkg.roles.forEach { role ->
                    if (role.id.value !in this) {
                        put(role.id.value, pkg.packageId)
                    }
                }
            }
        }
        val roles = persistence.roles.all().map { role ->
            WorkflowCompositionComponent(
                id = "role:${role.id.value}",
                name = role.name,
                description = role.description,
                kind = WorkflowCompositionComponentKind.Role,
                packageId = packageByRoleId[role.id.value],
                role = role,
                workflow = WorkflowComposer.roleAsWorkflow(role),
            )
        }
        return (definitions + fragments + roles).sortedWith(
            compareBy<WorkflowCompositionComponent>({ it.kind.ordinal }, { it.name.lowercase() }, { it.id }),
        )
    }

    suspend fun installedWorkflow(id: WorkflowDefinitionId): WorkflowDefinition? {
        val installedIds = installedPackages()
            .asSequence()
            .filter { it.kind == "workflow" }
            .flatMap { it.workflowDefinitionIds.asSequence() }
            .toSet()
        if (id.value !in installedIds) return null
        return persistence.definitions.get(id)
    }

    suspend fun roleWorkflow(id: RoleDefinitionId): WorkflowDefinition? =
        persistence.roles.get(id)?.let(WorkflowComposer::roleAsWorkflow)
}
