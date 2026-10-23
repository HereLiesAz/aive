package com.hereliesaz.aive

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds out whether a newer version exists and sends the user to this build's own channel (see
 * [updateChannel]): the Play build asks the Play API and opens the Play Store listing; the GitHub
 * build reads the GitHub release feed and opens the release page. The two channels ship different
 * packages, so an update is never offered from the other channel.
 */
internal class AndroidUpdateCoordinator(
    private val activity: ComponentActivity,
) {
    private val context = activity.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val playCheck: PlayUpdateCheck? = createPlayUpdateCheck(activity)

    var state: AndroidUpdateState by mutableStateOf(AndroidUpdateState.Idle)
        private set

    suspend fun checkForUpdates(force: Boolean = false) {
        val channel = updateChannel(isPlayBuild = playCheck != null, source = installSource(context))
        if (channel == UpdateChannel.None) return
        // A debug build is installed over adb, so it has no channel to compare against.
        if (channel == UpdateChannel.Github && (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) return

        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong(KEY_LAST_CHECK, 0L)
        if (!force && now - lastCheck < CHECK_INTERVAL_MILLIS) return

        state = AndroidUpdateState.Checking
        val result: Result<AndroidUpdateState> = try {
            Result.success(
                when (channel) {
                    UpdateChannel.Play ->
                        if (playCheck!!.isUpdateAvailable()) AndroidUpdateState.PlayUpdateAvailable
                        else AndroidUpdateState.Idle
                    else -> {
                        val release = withContext(Dispatchers.IO) { GithubReleaseFeed.latestAndroidRelease() }
                        if (release != null && isNewerVersion(release.version, currentVersion())) {
                            AndroidUpdateState.GithubUpdateAvailable(release.version, release.pageUrl)
                        } else {
                            AndroidUpdateState.Idle
                        }
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            state = AndroidUpdateState.Idle
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        // Only a completed check starts the quiet period: an offline launch or a rate-limited
        // answer must not hide updates for hours.
        if (result.isSuccess) preferences.edit().putLong(KEY_LAST_CHECK, now).apply()
        // Only a check the user asked for reports a failure; the launch check stays silent offline.
        state = result.getOrElse { failure ->
            if (force) AndroidUpdateState.Error(failure.message ?: "Update check failed") else AndroidUpdateState.Idle
        }
    }

    fun openPlayStore() {
        val packageId = activity.packageName
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(market) }.getOrElse {
            activity.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageId"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openGithubRelease() {
        val url = (state as? AndroidUpdateState.GithubUpdateAvailable)?.pageUrl
            ?: GithubReleaseFeed.RELEASES_PAGE_URL
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun dismiss() {
        state = AndroidUpdateState.Idle
    }

    private fun currentVersion(): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"

    private companion object {
        const val PREFERENCES_NAME = "aive.updater.v2"
        const val KEY_LAST_CHECK = "last-check"
        const val CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
    }
}
