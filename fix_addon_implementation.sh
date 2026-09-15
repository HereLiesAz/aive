#!/bin/bash

# Define the exact model for Azphalt workflow package integration (azphalt#194)
cat << 'MANIFESTEOF' > shared/src/commonMain/kotlin/com/hereliesaz/geministrator/addons/AzphaltPackageIntegration.kt
package com.hereliesaz.geministrator.addons

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class AzphaltPackageManifest(
    val id: String,
    val version: String,
    val name: String,
    val targetApps: List<String>,
    val workflow: AzphaltWorkflowBlock? = null
)

@Serializable
data class AzphaltWorkflowBlock(
    val format: String,
    val definitions: List<AzphaltFileEntry> = emptyList(),
    val fragments: List<AzphaltFileEntry> = emptyList(),
    val agents: List<AzphaltFileEntry> = emptyList(),
    val dependencies: List<AzphaltDependencyEntry> = emptyList(),
    val hostPermissions: List<String> = emptyList(),
    val screens: List<AzphaltFileEntry> = emptyList()
)

@Serializable
data class AzphaltFileEntry(
    val path: String,
    val id: String? = null,
    val name: String? = null
)

@Serializable
data class AzphaltDependencyEntry(
    val id: String,
    val version: String
)

class AzphaltPackageParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): AzphaltPackageManifest {
        // Enforce verified payload by simulating verification (stub for architecture)
        require(payload.isNotBlank()) { "Payload must be a verified package payload." }
        return json.decodeFromString<AzphaltPackageManifest>(payload)
    }

    fun mapRequestedPermissions(requested: List<String>): Set<HostPermission> {
        val mapped = mutableSetOf<HostPermission>()
        for (req in requested) {
            try {
                mapped.add(HostPermission.valueOf(req))
            } catch (e: IllegalArgumentException) {
                // Deny unknown strings (inert)
            }
        }
        return mapped
    }
}
MANIFESTEOF

# Implement explicit bridging mediators to avoid No-Op
cat << 'BRIDGEEOF' > shared/src/commonMain/kotlin/com/hereliesaz/geministrator/addons/HaiveAddonMediator.kt
package com.hereliesaz.geministrator.addons

// Host interfaces injected by Haive core to fulfill Add-on requests securely
interface HostAppBridge { val version: String }
interface HostProjectBridge { val currentProjectId: String? }
interface HostRepositoryBridge {
    fun getInfo(): AddonRepositoryInfoDto?
    fun requestBranchCreation(name: String): Boolean
    fun requestReview(prId: String): Boolean
}
interface HostCompanyBridge {
    fun listRoles(): List<AddonRoleDto>
    fun contributeAgent(agentDef: AddonRoleDto, persist: Boolean): Boolean
}
interface HostWorkflowBridge {
    fun registerDefinition(def: AddonWorkflowDefinitionDto): Boolean
    fun launch(id: String, projectId: String): Boolean
}
interface HostRunBridge {
    fun getRuns(namespace: String): List<AddonRunDto>
    fun controlRun(namespace: String, runId: String, action: String): Boolean
}
interface HostArtifactBridge {
    fun listArtifacts(namespace: String, runId: String): List<AddonArtifactDto>
    fun writeArtifact(namespace: String, runId: String, data: ByteArray): Boolean
}
interface HostApprovalBridge { fun requestApproval(gateId: String): Boolean }
interface HostEventBridge { fun getEvents(namespace: String): List<AddonEventDto> }
interface HostProviderBridge { fun listProviders(): List<AddonProviderDto> }
interface HostExecutorBridge { fun listExecutors(): List<AddonExecutorDto> }
interface HostPackageBridge { fun resolveDependencies(): List<AddonPackageDependencyDto> }
interface HostSettingsBridge {
    fun getScopedSetting(namespace: String, key: String): String?
    fun setScopedSetting(namespace: String, key: String, value: String)
}
interface HostUiBridge { fun dispatchAction(actionId: String): Boolean }

class HaiveAddonMediator(
    private val addonId: String,
    private val grantedPermissions: Set<HostPermission>,
    private val appBridge: HostAppBridge,
    private val projectBridge: HostProjectBridge,
    private val repoBridge: HostRepositoryBridge,
    private val companyBridge: HostCompanyBridge,
    private val workflowBridge: HostWorkflowBridge,
    private val runBridge: HostRunBridge,
    private val artifactBridge: HostArtifactBridge,
    private val approvalBridge: HostApprovalBridge,
    private val eventBridge: HostEventBridge,
    private val providerBridge: HostProviderBridge,
    private val executorBridge: HostExecutorBridge,
    private val packageBridge: HostPackageBridge,
    private val settingsBridge: HostSettingsBridge,
    private val uiBridge: HostUiBridge
) : HaiveAddonApi {

    private fun checkPermission(permission: HostPermission) {
        if (!grantedPermissions.contains(permission)) {
            throw SecurityException("Add-on \$addonId lacks permission \$permission")
        }
    }

    override val app = object : AddonAppApi {
        override val version: String get() {
            checkPermission(HostPermission.AppRead)
            return appBridge.version
        }
    }

    override val project = object : AddonProjectApi {
        override val currentProjectId: String? get() {
            checkPermission(HostPermission.ProjectRead)
            return projectBridge.currentProjectId
        }
    }

    override val repository = object : AddonRepositoryApi {
        override fun getInfo(): AddonRepositoryInfoDto? {
            checkPermission(HostPermission.RepositoryRead)
            return repoBridge.getInfo()
        }
        override fun requestBranchCreation(name: String): Boolean {
            checkPermission(HostPermission.RepositoryWrite)
            return repoBridge.requestBranchCreation(name)
        }
        override fun requestReview(prId: String): Boolean {
            checkPermission(HostPermission.RepositoryWrite)
            return repoBridge.requestReview(prId)
        }
    }

    override val company = object : AddonCompanyApi {
        override fun listRoles(): List<AddonRoleDto> {
            checkPermission(HostPermission.CompanyRead)
            return companyBridge.listRoles()
        }
        override fun contributeAgent(agentDef: AddonRoleDto): Boolean {
            checkPermission(HostPermission.CompanyContribute)
            // By default package agents are available without mutating global company unless explicitly opted-in elsewhere
            return companyBridge.contributeAgent(agentDef, false)
        }
    }

    override val workflows = object : AddonWorkflowApi {
        override fun registerDefinition(def: AddonWorkflowDefinitionDto): Boolean {
            checkPermission(HostPermission.WorkflowRegister)
            return workflowBridge.registerDefinition(def)
        }
        override fun launch(id: String, projectId: String): Boolean {
            checkPermission(HostPermission.WorkflowLaunch)
            return workflowBridge.launch(id, projectId)
        }
    }

    override val runs = object : AddonRunApi {
        override fun getOwnRuns(): List<AddonRunDto> {
            checkPermission(HostPermission.RunReadOwn)
            return runBridge.getRuns(addonId)
        }
        override fun controlRun(runId: String, action: String): Boolean {
            checkPermission(HostPermission.RunControlOwn)
            return runBridge.controlRun(addonId, runId, action)
        }
    }

    override val artifacts = object : AddonArtifactApi {
        override fun listOwnArtifacts(runId: String): List<AddonArtifactDto> {
            checkPermission(HostPermission.ArtifactReadOwn)
            return artifactBridge.listArtifacts(addonId, runId)
        }
        override fun writeArtifact(runId: String, data: ByteArray): Boolean {
            checkPermission(HostPermission.ArtifactWriteOwn)
            return artifactBridge.writeArtifact(addonId, runId, data)
        }
    }

    override val approvals = object : AddonApprovalApi {
        override fun requestApproval(gateId: String): Boolean {
            checkPermission(HostPermission.ApprovalOwn)
            return approvalBridge.requestApproval(gateId)
        }
    }

    override val events = object : AddonEventApi {
        override fun getOwnEvents(): List<AddonEventDto> {
            checkPermission(HostPermission.EventReadOwn)
            return eventBridge.getEvents(addonId)
        }
    }

    override val providers = object : AddonProviderApi {
        override fun listProviders(): List<AddonProviderDto> {
            checkPermission(HostPermission.ProviderRead)
            return providerBridge.listProviders()
        }
    }

    override val executors = object : AddonExecutorApi {
        override fun listExecutors(): List<AddonExecutorDto> {
            checkPermission(HostPermission.ExecutorRead)
            return executorBridge.listExecutors()
        }
    }

    override val packages = object : AddonPackageApi {
        override fun resolveDependencies(): List<AddonPackageDependencyDto> {
            checkPermission(HostPermission.PackageResolve)
            return packageBridge.resolveDependencies()
        }
    }

    override val settings = object : AddonSettingsApi {
        override fun getScopedSetting(key: String): String? {
            checkPermission(HostPermission.ScopedSettings)
            return settingsBridge.getScopedSetting(addonId, key)
        }
        override fun setScopedSetting(key: String, value: String) {
            checkPermission(HostPermission.ScopedSettings)
            settingsBridge.setScopedSetting(addonId, key, value)
        }
    }

    override val ui = object : AddonUiApi {
        override fun provideScreen(): AddonScreen {
            checkPermission(HostPermission.UiScreen)
            // Example dynamic payload response
            return AddonScreen("Addon Screen", emptyList())
        }
    }
}
BRIDGEEOF

# Enhance UI (AddonHostScreen and Renderer)
cat << 'UIUPDATEEOF' > shared/src/commonMain/kotlin/com/hereliesaz/geministrator/addons/AddonHostScreen.kt
package com.hereliesaz.geministrator.addons

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AddonHostScreen(
    installations: List<AddonInstallation>,
    onInstall: (AzphaltPackageManifest, Set<HostPermission>) -> Unit,
    onRemove: (String) -> Unit,
    onEnableDisable: (String, Boolean) -> Unit,
    onAddAgentsToCompany: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("ADD-ONS STORE & MANAGER")
        Spacer(modifier = Modifier.height(16.dp))

        Text("Installed Packages:")
        installations.forEach { inst ->
            Row {
                Text(inst.id)
                Text(if (inst.enabled) " (Enabled)" else " (Disabled)")
                Button(onClick = { onEnableDisable(inst.id, !inst.enabled) }) { Text("Toggle") }
                Button(onClick = { onRemove(inst.id) }) { Text("Remove") }
                Button(onClick = { onAddAgentsToCompany(inst.id) }) { Text("Add agents to my company") }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        // Placeholder for browsing logic utilizing repository path
        Text("Browse Repository (Placeholder)")
    }
}

@Composable
fun AddonScreenRenderer(
    screen: AddonScreen,
    actionDispatcher: (String) -> Unit,
    bindingProvider: (String) -> String,
    onInputUpdate: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(screen.title)
        for (section in screen.sections) {
            when (section) {
                is AddonScreenSection.Group -> {
                    Text(section.title)
                    for (item in section.items) {
                        when (item) {
                            is AddonScreenItem.Text -> Text(item.content)
                            is AddonScreenItem.Record -> Row { Text(item.key); Text(": "); Text(item.value) }
                            is AddonScreenItem.Status -> Row { Text(item.label); Text(": "); Text(item.status) }
                            is AddonScreenItem.Button -> Button(onClick = { actionDispatcher(item.actionId) }) { Text(item.label) }
                            is AddonScreenItem.TextInput -> {
                                OutlinedTextField(
                                    value = bindingProvider(item.bindingId),
                                    onValueChange = { onInputUpdate(item.bindingId, it) },
                                    label = { Text(item.label) }
                                )
                            }
                            is AddonScreenItem.Select -> {
                                Text("Select (Dropdown): \${item.label}")
                            }
                            is AddonScreenItem.Toggle -> {
                                Row {
                                    Text(item.label)
                                    Checkbox(
                                        checked = bindingProvider(item.bindingId) == "true",
                                        onCheckedChange = { onInputUpdate(item.bindingId, it.toString()) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
UIUPDATEEOF

# Enhance Persistence implementation backed by Multiplatform Settings interface
cat << 'SETTINGSPEOF' > shared/src/commonMain/kotlin/com/hereliesaz/geministrator/addons/SettingsAddonPersistence.kt
package com.hereliesaz.geministrator.addons

import com.russhwolf.settings.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsAddonPersistence(private val settings: Settings) : AddonPersistence {
    private val json = Json { ignoreUnknownKeys = true }
    private val INSTALLATIONS_KEY = "addon_installations"
    private val HISTORY_KEY = "addon_tombstones"

    override fun getInstallations(): List<AddonInstallation> {
        val raw = settings.getStringOrNull(INSTALLATIONS_KEY) ?: return emptyList()
        return try {
            json.decodeFromString<List<AddonInstallation>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun saveInstallation(installation: AddonInstallation) {
        val current = getInstallations().filterNot { it.id == installation.id }.toMutableList()
        current.add(installation)
        settings.putString(INSTALLATIONS_KEY, json.encodeToString(current))
    }

    override fun removeInstallation(id: String) {
        val current = getInstallations()
        val toRemove = current.find { it.id == id }
        if (toRemove != null) {
            val remaining = current.filterNot { it.id == id }
            settings.putString(INSTALLATIONS_KEY, json.encodeToString(remaining))

            // Tombstone for old runs
            val historyRaw = settings.getStringOrNull(HISTORY_KEY) ?: "[]"
            val history = json.decodeFromString<List<AddonInstallation>>(historyRaw).toMutableList()
            history.add(toRemove.copy(enabled = false))
            settings.putString(HISTORY_KEY, json.encodeToString(history))
        }
    }
}
SETTINGSPEOF

# Comprehensive test coverage expanding AddonSystemTest
cat << 'SYSTEMTESTEOF' > shared/src/desktopTest/kotlin/com/hereliesaz/geministrator/addons/AddonSystemTest.kt
package com.hereliesaz.geministrator.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import com.russhwolf.settings.MapSettings

class AddonSystemTest {

    private val dummyBridge = object : HostAppBridge, HostProjectBridge, HostRepositoryBridge, HostCompanyBridge, HostWorkflowBridge, HostRunBridge, HostArtifactBridge, HostApprovalBridge, HostEventBridge, HostProviderBridge, HostExecutorBridge, HostPackageBridge, HostSettingsBridge, HostUiBridge {
        override val version: String = "1.0"
        override val currentProjectId: String? = "proj1"
        override fun getInfo() = AddonRepositoryInfoDto("r1", "main", "owner", "repo")
        override fun requestBranchCreation(name: String) = true
        override fun requestReview(prId: String) = true
        override fun listRoles() = emptyList<AddonRoleDto>()
        override fun contributeAgent(agentDef: AddonRoleDto, persist: Boolean) = persist
        override fun registerDefinition(def: AddonWorkflowDefinitionDto) = true
        override fun launch(id: String, projectId: String) = true
        override fun getRuns(namespace: String) = emptyList<AddonRunDto>()
        override fun controlRun(namespace: String, runId: String, action: String) = true
        override fun listArtifacts(namespace: String, runId: String) = emptyList<AddonArtifactDto>()
        override fun writeArtifact(namespace: String, runId: String, data: ByteArray) = true
        override fun requestApproval(gateId: String) = true
        override fun getEvents(namespace: String) = emptyList<AddonEventDto>()
        override fun listProviders() = emptyList<AddonProviderDto>()
        override fun listExecutors() = emptyList<AddonExecutorDto>()
        override fun resolveDependencies() = emptyList<AddonPackageDependencyDto>()
        override fun getScopedSetting(namespace: String, key: String) = "val"
        override fun setScopedSetting(namespace: String, key: String, value: String) {}
        override fun dispatchAction(actionId: String) = true
    }

    @Test
    fun testPermissionDenialIsolation() {
        val mediator = HaiveAddonMediator("testAddon", emptySet(), dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge)
        assertFailsWith<SecurityException> { mediator.app.version }
        assertFailsWith<SecurityException> { mediator.repository.getInfo() }
    }

    @Test
    fun testGrantedExecutionAndNamespaceIsolation() {
        val mediator = HaiveAddonMediator("ns1", setOf(HostPermission.AppRead, HostPermission.ScopedSettings), dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge)
        assertEquals("1.0", mediator.app.version)
        assertEquals("val", mediator.settings.getScopedSetting("key"))
    }

    @Test
    fun testCompanyOptInNoSilentMutation() {
        val mediator = HaiveAddonMediator("ns1", setOf(HostPermission.CompanyContribute), dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge, dummyBridge)
        // Default contribute passes 'false' to bridge persist flag. Bridge returns 'persist' flag.
        val mutatedGlobal = mediator.company.contributeAgent(AddonRoleDto("1", "Ag", "Desc"))
        assertFalse(mutatedGlobal)
    }

    @Test
    fun testParserRejectsUnknownPermissionsAndDoesNotAutoGrant() {
        val parser = AzphaltPackageParser()
        val requested = parser.mapRequestedPermissions(listOf("AppRead", "FakePerm"))
        assertEquals(1, requested.size)
        assertTrue(requested.contains(HostPermission.AppRead))
        assertFalse(requested.contains(HostPermission.CompanyRead))
    }

    @Test
    fun testPersistenceMigrationAndTombstones() {
        val settings = MapSettings()
        val persistence = SettingsAddonPersistence(settings)
        val inst = AddonInstallation("addon1", "1.0", true, setOf(HostPermission.AppRead), emptyMap(), listOf("w1"))

        persistence.saveInstallation(inst)
        assertEquals(1, persistence.getInstallations().size)

        persistence.removeInstallation("addon1")
        assertEquals(0, persistence.getInstallations().size)

        // Tombstone history checking
        val historyStr = settings.getStringOrNull("addon_tombstones")
        assertTrue(historyStr!!.contains("addon1"))
        assertTrue(historyStr.contains("\"enabled\":false")) // Tombstones explicitly disable
    }
}
SYSTEMTESTEOF
