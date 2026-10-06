package com.hereliesaz.aive

import androidx.compose.runtime.Composable
import com.hereliesaz.geministrator.AzphaltAlertDialog

internal sealed interface AndroidUpdateState {
    data object Idle : AndroidUpdateState
    data object Checking : AndroidUpdateState
    data class Downloading(val version: String) : AndroidUpdateState
    data class ReadyToInstall(val version: String) : AndroidUpdateState
    data object PlayUpdateAvailable : AndroidUpdateState
    data class Error(val message: String) : AndroidUpdateState
}

@Composable
internal fun AndroidUpdatePrompt(
    state: AndroidUpdateState,
    onInstallGithubUpdate: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is AndroidUpdateState.ReadyToInstall -> AzphaltAlertDialog(
            title = "The Aive ${state.version} is ready",
            text = "The GitHub build downloaded the update. Android will ask you to confirm installation; your projects, settings, credentials, roles, and workflows remain in place.",
            confirmLabel = "Install update",
            onConfirm = onInstallGithubUpdate,
            onDismissRequest = onDismiss,
            dismissLabel = "Later",
        )

        AndroidUpdateState.PlayUpdateAvailable -> AzphaltAlertDialog(
            title = "A The Aive update is available",
            text = "An update is available through Google Play.",
            confirmLabel = "Open Play Store",
            onConfirm = onOpenPlayStore,
            onDismissRequest = onDismiss,
            dismissLabel = "Later",
        )

        else -> Unit
    }
}

internal fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parts(value: String): List<Int> = value
        .trim()
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }

    val left = parts(candidate)
    val right = parts(current)
    val size = maxOf(left.size, right.size)
    for (index in 0 until size) {
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a > b
    }
    return false
}
