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
        assertFailsWith<RuntimeException> { mediator.app.version }
        assertFailsWith<RuntimeException> { mediator.repository.getInfo() }
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

        val historyStr = settings.getStringOrNull("addon_tombstones")
        assertTrue(historyStr!!.contains("addon1"))
        assertTrue(historyStr.contains("\"enabled\":false"))
    }
}
