package com.hereliesaz.geministrator.azphalt

import kotlinx.serialization.Serializable

@Serializable
data class InstalledAzphaltModelFile(
    val logicalModelId: String,
    val type: String,
    val role: String? = null,
    val relativePath: String,
    val sha256: String,
    val byteSize: Long,
)

@Serializable
data class InstalledAzphaltModelPackage(
    val packageId: String,
    val version: String,
    val repositoryUrl: String,
    val files: List<InstalledAzphaltModelFile>,
    val signed: Boolean,
    val signerPublicKey: String? = null,
    val installedAtEpochMillis: Long,
)

interface AzphaltModelPackageInstaller {
    val supportedAssetTypes: Set<String>

    suspend fun installed(): List<InstalledAzphaltModelPackage>

    suspend fun install(
        prepared: AzphaltPreparedModelInstall,
        nowEpochMillis: Long,
    ): InstalledAzphaltModelPackage

    suspend fun remove(packageId: String)
}
