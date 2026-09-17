package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzphaltCompositionLibraryTest {
    @Test
    fun paletteContainsOnlyInstalledStoreWorkflowsButEveryPersistedRole() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val installed = workflow("installed", "Installed")
        val generated = workflow("generated", "Generated project workflow")
        val role = RoleDefinition(
            id = RoleDefinitionId("investigator"),
            name = "Investigator",
            description = "Investigates evidence.",
            instructions = "Collect and verify evidence.",
        )
        persistence.definitions.put(installed)
        persistence.definitions.put(generated)
        persistence.roles.put(role)

        val library = AzphaltCompositionLibrary(persistence) {
            listOf(
                InstalledAzphaltWorkflowPackage(
                    packageId = "com.example.installed",
                    kind = "workflow",
                    version = "1.0.0",
                    repositoryUrl = AZPHALT_STORE_URL,
                    workflowDefinitionIds = listOf("installed"),
                    roles = listOf(role),
                    installedAtEpochMillis = 1L,
                ),
            )
        }

        val components = library.components()

        assertNotNull(components.singleOrNull { it.id == "workflow:installed" })
        assertNull(components.singleOrNull { it.id == "workflow:generated" })
        val roleComponent = assertNotNull(components.singleOrNull { it.id == "role:investigator" })
        assertEquals(WorkflowCompositionComponentKind.Role, roleComponent.kind)
        assertEquals(role.id, roleComponent.workflow?.tasks?.single()?.roleId)
    }

    @Test
    fun installedWorkflowLookupRejectsPersistedButUninstalledDefinitions() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        persistence.definitions.put(workflow("installed", "Installed"))
        persistence.definitions.put(workflow("generated", "Generated"))
        val library = AzphaltCompositionLibrary(persistence) {
            listOf(
                InstalledAzphaltWorkflowPackage(
                    packageId = "com.example.installed",
                    kind = "workflow",
                    version = "1.0.0",
                    repositoryUrl = AZPHALT_STORE_URL,
                    workflowDefinitionIds = listOf("installed"),
                    installedAtEpochMillis = 1L,
                ),
            )
        }

        assertNotNull(library.installedWorkflow(WorkflowDefinitionId("installed")))
        assertNull(library.installedWorkflow(WorkflowDefinitionId("generated")))
    }

    private fun workflow(id: String, name: String) = WorkflowDefinition(
        id = WorkflowDefinitionId(id),
        name = name,
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("$id-task"),
                name = "Task",
                objective = "Do the task",
                roleId = null,
                executor = TaskExecutor.HumanApproval(),
            ),
        ),
    )
}
