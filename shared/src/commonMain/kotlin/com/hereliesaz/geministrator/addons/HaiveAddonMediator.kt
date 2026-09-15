package com.hereliesaz.geministrator.addons

class HaiveAddonMediator(private val addonId: String, private val grantedPermissions: Set<HostPermission>) : HaiveAddonApi {

    private fun checkPermission(permission: HostPermission) {
        if (!grantedPermissions.contains(permission)) {
            throw SecurityException("Add-on $addonId lacks permission $permission")
        }
    }

    override val app = object : AddonAppApi {
        override val version: String get() {
            checkPermission(HostPermission.AppRead)
            return "0.9.3"
        }
    }

    override val project = object : AddonProjectApi {
        override val currentProjectId: String? get() {
            checkPermission(HostPermission.ProjectRead)
            return null
        }
    }

    override val repository = object : AddonRepositoryApi {
        override fun getInfo(): AddonRepositoryInfoDto? {
            checkPermission(HostPermission.RepositoryRead)
            return null
        }
        override fun requestBranchCreation(name: String): Boolean {
            checkPermission(HostPermission.RepositoryWrite)
            return false
        }
        override fun requestReview(prId: String): Boolean {
            checkPermission(HostPermission.RepositoryWrite)
            return false
        }
    }

    override val company = object : AddonCompanyApi {
        override fun listRoles(): List<AddonRoleDto> {
            checkPermission(HostPermission.CompanyRead)
            return emptyList()
        }
        override fun contributeAgent(agentDef: AddonRoleDto): Boolean {
            checkPermission(HostPermission.CompanyContribute)
            return false
        }
    }

    override val workflows = object : AddonWorkflowApi {
        override fun registerDefinition(def: AddonWorkflowDefinitionDto): Boolean {
            checkPermission(HostPermission.WorkflowRegister)
            return false
        }
        override fun launch(id: String, projectId: String): Boolean {
            checkPermission(HostPermission.WorkflowLaunch)
            return false
        }
    }

    override val runs = object : AddonRunApi {
        override fun getOwnRuns(): List<AddonRunDto> {
            checkPermission(HostPermission.RunReadOwn)
            return emptyList()
        }
        override fun controlRun(runId: String, action: String): Boolean {
            checkPermission(HostPermission.RunControlOwn)
            return false
        }
    }

    override val artifacts = object : AddonArtifactApi {
        override fun listOwnArtifacts(runId: String): List<AddonArtifactDto> {
            checkPermission(HostPermission.ArtifactReadOwn)
            return emptyList()
        }
        override fun writeArtifact(runId: String, data: ByteArray): Boolean {
            checkPermission(HostPermission.ArtifactWriteOwn)
            return false
        }
    }

    override val approvals = object : AddonApprovalApi {
        override fun requestApproval(gateId: String): Boolean {
            checkPermission(HostPermission.ApprovalOwn)
            return false
        }
    }

    override val events = object : AddonEventApi {
        override fun getOwnEvents(): List<AddonEventDto> {
            checkPermission(HostPermission.EventReadOwn)
            return emptyList()
        }
    }

    override val providers = object : AddonProviderApi {
        override fun listProviders(): List<AddonProviderDto> {
            checkPermission(HostPermission.ProviderRead)
            return emptyList()
        }
    }

    override val executors = object : AddonExecutorApi {
        override fun listExecutors(): List<AddonExecutorDto> {
            checkPermission(HostPermission.ExecutorRead)
            return emptyList()
        }
    }

    override val packages = object : AddonPackageApi {
        override fun resolveDependencies(): List<AddonPackageDependencyDto> {
            checkPermission(HostPermission.PackageResolve)
            return emptyList()
        }
    }

    override val settings = object : AddonSettingsApi {
        override fun getScopedSetting(key: String): String? {
            checkPermission(HostPermission.ScopedSettings)
            return null
        }
        override fun setScopedSetting(key: String, value: String) {
            checkPermission(HostPermission.ScopedSettings)
        }
    }

    override val ui = object : AddonUiApi {
        override fun provideScreen(): AddonScreen {
            checkPermission(HostPermission.UiScreen)
            return AddonScreen("Default", emptyList())
        }
    }
}
