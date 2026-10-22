package com.hereliesaz.aive

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hereliesaz.geministrator.AzphaltAlertDialog

internal sealed interface AndroidUpdateState {
    data object Idle : AndroidUpdateState
    data object Checking : AndroidUpdateState
    data object PlayUpdateAvailable : AndroidUpdateState
    data class GithubUpdateAvailable(val version: String, val pageUrl: String) : AndroidUpdateState
    data class Error(val message: String) : AndroidUpdateState
}

@Composable
internal fun AndroidUpdatePrompt(
    state: AndroidUpdateState,
    onOpenGithubRelease: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is AndroidUpdateState.GithubUpdateAvailable -> AzphaltAlertDialog(
            title = stringResource(R.string.update_github_title, state.version),
            text = stringResource(R.string.update_github_body),
            confirmLabel = stringResource(R.string.update_open_github),
            onConfirm = onOpenGithubRelease,
            onDismissRequest = onDismiss,
            dismissLabel = stringResource(R.string.update_later),
        )

        AndroidUpdateState.PlayUpdateAvailable -> AzphaltAlertDialog(
            title = stringResource(R.string.update_play_title),
            text = stringResource(R.string.update_play_body),
            confirmLabel = stringResource(R.string.update_open_play_store),
            onConfirm = onOpenPlayStore,
            onDismissRequest = onDismiss,
            dismissLabel = stringResource(R.string.update_later),
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
