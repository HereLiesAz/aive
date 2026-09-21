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
interface HostUiBridge {
    fun provideScreen(namespace: String): AddonScreen
    fun dispatchAction(namespace: String, actionId: String): Boolean
}

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
            throw RuntimeException("Add-on $addonId lacks permission $permission")
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
            return uiBridge.provideScreen(addonId)
        }
        override fun dispatchAction(namespace: String, actionId: String): Boolean {
            checkPermission(HostPermission.UiScreen)
            return uiBridge.dispatchAction(namespace, actionId)
        }
    }
}
