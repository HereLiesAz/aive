package com.hereliesaz.aive

import android.content.Context
import android.os.Build

/** Where this copy of the app was installed from; decides which update channel applies to it. */
internal enum class InstallSource { Play, Other }

internal const val PLAY_INSTALLER_PACKAGE = "com.android.vending"

internal fun installSourceOf(installerPackage: String?): InstallSource =
    if (installerPackage == PLAY_INSTALLER_PACKAGE) InstallSource.Play else InstallSource.Other

@Suppress("DEPRECATION")
internal fun installSource(context: Context): InstallSource {
    val installer = runCatching {
        val packages = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packages.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            packages.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()
    return installSourceOf(installer)
}

/** Asks Google Play whether a newer version of this app exists. Only the Play build can provide one. */
internal interface PlayUpdateCheck {
    suspend fun isUpdateAvailable(): Boolean
}
