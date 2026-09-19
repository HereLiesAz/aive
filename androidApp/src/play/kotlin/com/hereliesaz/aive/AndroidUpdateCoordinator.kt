package com.hereliesaz.aive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidUpdateCoordinator(
    private val activity: ComponentActivity,
) {
    private val context = activity.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var state: AndroidUpdateState by mutableStateOf(AndroidUpdateState.Idle)
        private set

    suspend fun checkForUpdates(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong(KEY_LAST_CHECK, 0L)
        if (!force && now - lastCheck < CHECK_INTERVAL_MILLIS) return

        state = AndroidUpdateState.Checking
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val release = GithubReleaseFeed.latestAndroidRelease()
                val current = currentVersion()
                if (release != null && isNewerVersion(release.version, current)) release.version else null
            }
        }
        preferences.edit().putLong(KEY_LAST_CHECK, now).commit()

        state = result.fold(
            onSuccess = { version ->
                if (version == null) AndroidUpdateState.Idle
                else AndroidUpdateState.PlayUpdateAvailable(version)
            },
            onFailure = { failure ->
                AndroidUpdateState.Error(failure.message ?: "Update check failed")
            },
        )
    }

    fun installDownloadedUpdate() = Unit

    fun onResume() = Unit

    fun openPlayStore() {
        val packageId = "com.hereliesaz.aive"
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

    fun dismiss() {
        state = AndroidUpdateState.Idle
    }

    private fun currentVersion(): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"

    private companion object {
        const val PREFERENCES_NAME = "aive.play-updater.v1"
        const val KEY_LAST_CHECK = "last-check"
        const val CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
    }
}
