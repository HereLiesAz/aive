package com.hereliesaz.geministrator.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AddonSystemTest {

    private val providedScreen = AddonScreen(
        title = "Real add-on screen",
        sections = listOf(
            AddonScreenSection.Group(
                title = "Status",
                items = listOf(AddonScreenItem.Text("live")),
            ),
        ),
    )

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
        override fun provideScreen(namespace: String) = providedScreen
        override fun dispatchAction(namespace: String, actionId: String) = true
    }

    private fun mediator(permissions: Set<HostPermission>) = HaiveAddonMediator(
        "testAddon",
        permissions,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
        dummyBridge,
    )

    @Test
    fun testPermissionDenialIsolation() {
        val mediator = mediator(emptySet())
        assertFailsWith<RuntimeException> { mediator.app.version }
        assertFailsWith<RuntimeException> { mediator.repository.getInfo() }
    }

    @Test
    fun testGrantedExecutionAndNamespaceIsolation() {
        val mediator = mediator(setOf(HostPermission.AppRead, HostPermission.ScopedSettings))
        assertEquals("1.0", mediator.app.version)
        assertEquals("val", mediator.settings.getScopedSetting("key"))
    }

    @Test
    fun testCompanyOptInNoSilentMutation() {
        val mediator = mediator(setOf(HostPermission.CompanyContribute))
        val mutatedGlobal = mediator.company.contributeAgent(AddonRoleDto("1", "Ag", "Desc"))
        assertFalse(mutatedGlobal)
    }

    @Test
    fun testUiScreenDelegatesToHostInsteadOfFabricatingEmptyScreen() {
        val mediator = mediator(setOf(HostPermission.UiScreen))
        assertEquals(providedScreen, mediator.ui.provideScreen())
    }

    @Test
    fun testParserRejectsUnknownPermissionsAndDoesNotAutoGrant() {
        val parser = AzphaltPackageParser()
        val mapped = parser.parseRequestedPermissions(listOf("AppRead", "UnknownPermission", "CompanyRead"))
        assertTrue(mapped.contains(HostPermission.AppRead))
        assertTrue(mapped.contains(HostPermission.CompanyRead))
        assertEquals(2, mapped.size)
    }

    @Test
    fun testUnapprovedPermissionsDenied() {
        val parser = AzphaltPackageParser()
        val requested = parser.parseRequestedPermissions(listOf("AppRead", "CompanyRead"))
        val approved = setOf(HostPermission.AppRead)
        val granted = parser.selectGrantedPermissions(requested, approved)
        assertTrue(granted.contains(HostPermission.AppRead))
        assertEquals(1, granted.size)
    }
}
