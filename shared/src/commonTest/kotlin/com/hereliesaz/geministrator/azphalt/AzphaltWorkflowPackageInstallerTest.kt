package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AzphaltWorkflowPackageInstallerTest {
    private val json = SettingsWorkflowPersistence.defaultJson

    @Test
    fun installRegistersWorkflowButKeepsPackageAgentLocalAndAllowsPermissionSubset() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val store = SettingsAzphaltInstallStore(MapSettings())
        val installer = installer(persistence, store)
        val packageVerification = verification(packageFor(targetApps = listOf(HAIVE_AZPHALT_HOST_ID)))

        val plan = installer.inspect(packageVerification)

        assertEquals(setOf("WorkflowRegister", "WorkflowLaunch"), plan.requestedHostPermissions)
        assertNull(persistence.definitions.get(WorkflowDefinitionId("release")))
        assertNull(persistence.roles.get(RoleDefinitionId("builder")))

        val installed = installer.install(
            plan = plan,
            repositoryUrl = AZPHALT_STORE_URL,
            approvedHostPermissions = setOf("WorkflowRegister"),
            nowEpochMillis = 50L,
        )

        assertNotNull(persistence.definitions.get(WorkflowDefinitionId("release")))
        assertNull(persistence.roles.get(RoleDefinitionId("builder")))
        assertEquals("com.example.release", installed.packageId)
        assertEquals(listOf("release"), installed.workflowDefinitionIds)
        assertEquals(listOf("builder"), installed.roles.map { it.id.value })
        assertEquals(listOf("WorkflowRegister"), installed.approvedHostPermissions)
        assertEquals(installed, store.get("com.example.release"))
    }

    @Test
    fun packageScopedToAnotherHostIsRejectedBeforeAnyPersistence() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val installer = installer(persistence, SettingsAzphaltInstallStore(MapSettings()))

        assertFailsWith<IllegalArgumentException> {
            installer.inspect(verification(packageFor(targetApps = listOf("com.example.other"))))
        }

        assertEquals(emptyList(), persistence.definitions.all())
        assertEquals(emptyList(), persistence.roles.all())
    }

    @Test
    fun signedUntrustedPublisherNeedsExplicitApproval() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val installer = installer(persistence, SettingsAzphaltInstallStore(MapSettings()))
        val untrusted = verification(packageFor(), trusted = false, reason = "unknown signer")
        val plan = installer.inspect(untrusted)

        assertFailsWith<IllegalArgumentException> {
            installer.install(plan, AZPHALT_STORE_URL, emptySet(), 10L)
        }

        installer.install(
            plan,
            AZPHALT_STORE_URL,
            emptySet(),
            10L,
            allowUntrustedSigner = true,
        )
        assertNotNull(persistence.definitions.get(WorkflowDefinitionId("release")))
        Unit
    }

    @Test
    fun packageCannotOverwriteUnownedWorkflowId() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val store = SettingsAzphaltInstallStore(MapSettings())
        val installer = installer(persistence, store)
        persistence.definitions.put(workflow().copy(name = "Local workflow"))
        val plan = installer.inspect(verification(packageFor()))

        assertFailsWith<IllegalArgumentException> {
            installer.install(plan, AZPHALT_STORE_URL, emptySet(), 50L)
        }
        assertEquals("Local workflow", persistence.definitions.get(WorkflowDefinitionId("release"))?.name)
    }

    @Test
    fun samePackageMayUpgradeDefinitionsAndLocalAgentsItAlreadyOwns() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val store = SettingsAzphaltInstallStore(MapSettings())
        val installer = installer(persistence, store)
        val firstPlan = installer.inspect(verification(packageFor(version = "1.0.0")))
        installer.install(firstPlan, AZPHALT_STORE_URL, emptySet(), 10L)

        val updatedDefinition = workflow().copy(name = "Release v2")
        val update = packageFor(version = "2.0.0", workflowDefinition = updatedDefinition)
        val secondPlan = installer.inspect(verification(update))
        installer.install(secondPlan, AZPHALT_STORE_URL, emptySet(), 20L)

        assertEquals("Release v2", persistence.definitions.get(WorkflowDefinitionId("release"))?.name)
        assertEquals("2.0.0", store.get("com.example.release")?.version)
        assertEquals(listOf("builder"), store.get("com.example.release")?.roles?.map { it.id.value })
        assertNull(persistence.roles.get(RoleDefinitionId("builder")))
    }

    private fun installer(
        persistence: InMemoryWorkflowPersistence,
        store: AzphaltInstallStore,
    ) = AzphaltWorkflowPackageInstaller(
        persistence = persistence,
        installStore = store,
        publisherPins = SettingsAzphaltPublisherPinStore(MapSettings()),
    )

    private fun verification(
        pkg: VerifiedAzphaltPackage,
        trusted: Boolean = true,
        reason: String = "test trusted publisher",
    ) = AzphaltPackageVerification(
        packageContents = pkg,
        trusted = trusted,
        trustReason = reason,
        publisherChanged = false,
    )

    private fun packageFor(
        targetApps: List<String> = emptyList(),
        version: String = "1.0.0",
        workflowDefinition: WorkflowDefinition = workflow(),
    ): VerifiedAzphaltPackage {
        val role = role()
        val definitionPath = "workflows/release.json"
        val rolePath = "agents/builder.json"
        return VerifiedAzphaltPackage(
            manifest = AzphaltManifest(
                azphalt = "0.1",
                id = "com.example.release",
                name = "Release Workflow",
                version = version,
                kind = "workflow",
                license = "MIT",
                compat = ">=0.1",
                targetApps = targetApps,
                files = mapOf(
                    definitionPath to "sha256-definition",
                    rolePath to "sha256-role",
                ),
                workflow = AzphaltWorkflowManifest(
                    format = HAIVE_WORKFLOW_FORMAT,
                    definitions = listOf(AzphaltWorkflowPayloadEntry("release", "Release", path = definitionPath)),
                    agents = listOf(AzphaltWorkflowAgentEntry("builder", "Builder", path = rolePath)),
                    hostPermissions = listOf("WorkflowRegister", "WorkflowLaunch"),
                ),
            ),
            payload = mapOf(
                definitionPath to json.encodeToString(workflowDefinition).encodeToByteArray(),
                rolePath to json.encodeToString(role).encodeToByteArray(),
            ),
            signed = true,
            signerPublicKey = "publisher-key",
        )
    }

    private fun role() = RoleDefinition(
        id = RoleDefinitionId("builder"),
        name = "Builder",
        description = "Builds the thing",
        instructions = "Implement the assigned task.",
    )

    private fun workflow() = WorkflowDefinition(
        id = WorkflowDefinitionId("release"),
        name = "Release",
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("build"),
                name = "Build",
                objective = "Build the release",
                roleId = RoleDefinitionId("builder"),
                executor = TaskExecutor.RoleAgent(RoleDefinitionId("builder")),
            ),
        ),
    )
}
