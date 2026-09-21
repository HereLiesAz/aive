package com.hereliesaz.geministrator.addons

import kotlinx.serialization.Serializable

@Serializable
data class AddonInstallation(
    val id: String,
    val version: String,
    val enabled: Boolean,
    val grantedPermissions: Set<HostPermission>,
    val settings: Map<String, String>,
    val importedWorkflowIds: List<String>
)

interface AddonPersistence {
    suspend fun getInstallations(): List<AddonInstallation>
    suspend fun saveInstallation(installation: AddonInstallation)
    suspend fun removeInstallation(id: String)
}
