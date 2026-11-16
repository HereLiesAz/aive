package com.hereliesaz.geministrator.addons

import kotlinx.serialization.Serializable

@Serializable
data class AddonRepositoryInfoDto(val id: String, val branch: String, val owner: String, val name: String)

@Serializable
data class AddonRoleDto(val id: String, val name: String, val description: String)

@Serializable
data class AddonWorkflowDefinitionDto(val id: String, val name: String)

@Serializable
data class AddonRunDto(val id: String, val status: String, val progress: Float)

@Serializable
data class AddonArtifactDto(val id: String, val name: String, val size: Long)

@Serializable
data class AddonEventDto(val id: String, val type: String, val timestamp: Long)

@Serializable
data class AddonProviderDto(val id: String, val label: String, val capabilities: List<String>)

@Serializable
data class AddonExecutorDto(val id: String, val type: String, val capabilities: List<String>)

@Serializable
data class AddonPackageDependencyDto(val id: String, val version: String, val resolved: Boolean)

interface AddonAppApi {
    val version: String
}

interface AddonProjectApi {
    val currentProjectId: String?
}

interface AddonRepositoryApi {
    fun getInfo(): AddonRepositoryInfoDto?
    fun requestBranchCreation(name: String): Boolean
    fun requestReview(prId: String): Boolean
}

interface AddonCompanyApi {
    fun listRoles(): List<AddonRoleDto>
    fun contributeAgent(agentDef: AddonRoleDto): Boolean
}

interface AddonWorkflowApi {
    fun registerDefinition(def: AddonWorkflowDefinitionDto): Boolean
    fun launch(id: String, projectId: String): Boolean
}

interface AddonRunApi {
    fun getOwnRuns(): List<AddonRunDto>
    fun controlRun(runId: String, action: String): Boolean
}

interface AddonArtifactApi {
    fun listOwnArtifacts(runId: String): List<AddonArtifactDto>
    fun writeArtifact(runId: String, data: ByteArray): Boolean
}

interface AddonApprovalApi {
    fun requestApproval(gateId: String): Boolean
}

interface AddonEventApi {
    fun getOwnEvents(): List<AddonEventDto>
}

interface AddonProviderApi {
    fun listProviders(): List<AddonProviderDto>
}

interface AddonExecutorApi {
    fun listExecutors(): List<AddonExecutorDto>
}

interface AddonPackageApi {
    fun resolveDependencies(): List<AddonPackageDependencyDto>
}

interface AddonSettingsApi {
    fun getScopedSetting(key: String): String?
    fun setScopedSetting(key: String, value: String)
}

interface AddonUiApi {
    fun provideScreen(): AddonScreen
    fun dispatchAction(namespace: String, actionId: String): Boolean
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
