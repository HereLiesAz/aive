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

/** The one channel an update can come from for this copy of the app. */
internal enum class UpdateChannel { Play, Github, None }

/**
 * The Play build and the GitHub build are different packages, so each only ever updates from its
 * own channel. A Play build asks Play, and only when Play installed it (the Play API answers for
 * nothing else); a Play build installed any other way (adb, internal sharing) has no update source
 * and must not be offered the GitHub APK, which would install as a second app.
 */
internal fun updateChannel(isPlayBuild: Boolean, source: InstallSource): UpdateChannel = when {
    !isPlayBuild -> UpdateChannel.Github
    source == InstallSource.Play -> UpdateChannel.Play
    else -> UpdateChannel.None
}

/**
 * The GitHub APK and the Play build share one application id but are signed with different keys, so Android refuses
 * (INSTALL_FAILED_UPDATE_INCOMPATIBLE) a GitHub APK over a copy Play installed. A copy Play installed is therefore never
 * offered the GitHub release; it is told to update through Play instead.
 */
internal fun githubApkCanReplace(source: InstallSource): Boolean = source != InstallSource.Play

/** Asks Google Play whether a newer version of this app exists. Only the Play build can provide one. */
internal interface PlayUpdateCheck {
    suspend fun isUpdateAvailable(): Boolean
}

/** The application id GitHub-release builds used before the rename; a copy under it is a separate app from this one. */
internal const val LEGACY_GITHUB_PACKAGE = "com.hereliesaz.haive"

/**
 * Whether to tell the keeper, once, that an older copy under [LEGACY_GITHUB_PACKAGE] is still installed beside this
 * GitHub build. Android treats it as a different app: it does not upgrade in place and its private data cannot be read from here.
 */
internal fun legacyAppNoticeDue(isPlayBuild: Boolean, legacyInstalled: Boolean, alreadyShown: Boolean): Boolean =
    !isPlayBuild && legacyInstalled && !alreadyShown

internal fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(packageName, 0)
    true
}.getOrDefault(false)
