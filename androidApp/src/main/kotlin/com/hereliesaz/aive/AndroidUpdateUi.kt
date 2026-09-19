package com.hereliesaz.aive

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

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
        is AndroidUpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("The Aive ${state.version} is ready") },
            text = {
                Text(
                    "The GitHub build downloaded the update. Android will ask you to confirm installation; your projects, settings, credentials, roles, and workflows remain in place.",
                )
            },
            confirmButton = {
                TextButton(onClick = onInstallGithubUpdate) { Text("Install update") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Later") }
            },
        )

        AndroidUpdateState.PlayUpdateAvailable -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("A The Aive update is available") },
            text = {
                Text("An update is available through Google Play.")
            },
            confirmButton = {
                TextButton(onClick = onOpenPlayStore) { Text("Open Play Store") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Later") }
            },
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
