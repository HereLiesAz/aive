package com.hereliesaz.aive

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

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
            title = { Text(stringResource(R.string.update_ready_title, state.version)) },
            text = {
                Text(
                    stringResource(R.string.update_ready_body),
                )
            },
            confirmButton = {
                TextButton(onClick = onInstallGithubUpdate) { Text(stringResource(R.string.update_install)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
            },
        )

        AndroidUpdateState.PlayUpdateAvailable -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_play_title)) },
            text = {
                Text(stringResource(R.string.update_play_body))
            },
            confirmButton = {
                TextButton(onClick = onOpenPlayStore) { Text(stringResource(R.string.update_open_play_store)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
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
