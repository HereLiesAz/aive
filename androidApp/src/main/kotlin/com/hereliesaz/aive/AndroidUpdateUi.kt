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

/**
 * True when [candidate] is a later version than [current]. Dotted numeric parts compare in order;
 * a non-numeric or oversized part counts as 0. At equal numbers a final release is newer than a
 * pre-release of it (`1.0.0` > `1.0.0-rc1`), and a pre-release is never newer than the final.
 */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    fun split(value: String): Pair<List<Long>, Boolean> {
        val trimmed = value.trim().removePrefix("v")
        val numbers = trimmed.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
        return numbers to trimmed.contains('-')
    }

    val (left, leftPre) = split(candidate)
    val (right, rightPre) = split(current)
    val size = maxOf(left.size, right.size)
    for (index in 0 until size) {
        val a = left.getOrElse(index) { 0L }
        val b = right.getOrElse(index) { 0L }
        if (a != b) return a > b
    }
    return rightPre && !leftPre
}
