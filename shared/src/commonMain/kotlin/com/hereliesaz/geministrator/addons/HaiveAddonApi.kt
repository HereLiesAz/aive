package com.hereliesaz.geministrator.addons

// Host mediation types (placeholders to simulate the mediated contract pattern without raw implementations)
interface AddonAppApi {
    val version: String
}

interface AddonProjectApi {
    val currentProjectId: String?
}

interface AddonRepositoryApi {
    fun getInfo(): Any // Mediated DTO
    fun requestBranchCreation(name: String)
    fun requestReview(prId: String)
}

interface AddonCompanyApi {
    fun listRoles(): List<Any> // Mediated DTO
    fun contributeAgent(agentDef: Any) // Mediated DTO
}

interface AddonWorkflowApi {
    fun registerDefinition(def: Any)
    fun launch(id: String, projectId: String)
}

interface AddonRunApi {
    fun getOwnRuns(): List<Any>
    fun controlRun(runId: String, action: String)
}

interface AddonArtifactApi {
    fun listOwnArtifacts(runId: String): List<Any>
    fun writeArtifact(runId: String, data: Any)
}

interface AddonApprovalApi {
    fun requestApproval(gateId: String)
}

interface AddonEventApi {
    fun getOwnEvents(): List<Any>
}

interface AddonProviderApi {
    fun listProviders(): List<Any> // Mediated capability DTOs, no credentials
}

interface AddonExecutorApi {
    fun listExecutors(): List<Any>
}

interface AddonPackageApi {
    fun resolveDependencies(): List<Any>
}

interface AddonSettingsApi {
    fun getScopedSetting(key: String): String?
    fun setScopedSetting(key: String, value: String)
}

interface AddonUiApi {
    fun provideScreen(): AddonScreen
}

interface HaiveAddonApi {
    val app: AddonAppApi
    val project: AddonProjectApi
    val repository: AddonRepositoryApi
    val company: AddonCompanyApi
    val workflows: AddonWorkflowApi
    val runs: AddonRunApi
    val artifacts: AddonArtifactApi
    val approvals: AddonApprovalApi
    val events: AddonEventApi
    val providers: AddonProviderApi
    val executors: AddonExecutorApi
    val packages: AddonPackageApi
    val settings: AddonSettingsApi
    val ui: AddonUiApi
}
