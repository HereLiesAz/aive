package com.hereliesaz.aive

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
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
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) return

        val downloaded = downloadedUpdate()
        if (downloaded != null) {
            state = AndroidUpdateState.ReadyToInstall(downloaded.first)
            return
        }

        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong(KEY_LAST_CHECK, 0L)
        if (!force && now - lastCheck < CHECK_INTERVAL_MILLIS) return

        state = AndroidUpdateState.Checking
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val release = GithubReleaseFeed.latestAndroidRelease()
                val current = currentVersion()
                if (release == null || !isNewerVersion(release.version, current)) {
                    null
                } else {
                    release
                }
            }
        }
        preferences.edit().putLong(KEY_LAST_CHECK, now).commit()

        val release = result.getOrElse { failure ->
            state = AndroidUpdateState.Error(failure.message ?: "Update check failed")
            return
        }
        if (release == null) {
            state = AndroidUpdateState.Idle
            return
        }

        state = AndroidUpdateState.Downloading(release.version)
        val downloadedFile = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = GithubReleaseFeed.download(release.downloadUrl)
                val directory = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                    "updates",
                ).apply { mkdirs() }
                val destination = File(directory, "TheAive-${release.version}-android.apk")
                val temporary = File(directory, destination.name + ".tmp")
                temporary.writeBytes(bytes)
                if (destination.exists() && !destination.delete()) {
                    temporary.delete()
                    error("Could not replace downloaded update")
                }
                check(temporary.renameTo(destination)) {
                    temporary.delete()
                    "Could not finalize downloaded update"
                }
                destination
            }
        }.getOrElse { failure ->
            state = AndroidUpdateState.Error(failure.message ?: "Update download failed")
            return
        }

        check(
            preferences.edit()
                .putString(KEY_DOWNLOADED_VERSION, release.version)
                .putString(KEY_DOWNLOADED_PATH, downloadedFile.absolutePath)
                .commit(),
        ) { "Could not persist downloaded update state" }
        state = AndroidUpdateState.ReadyToInstall(release.version)
    }

    fun installDownloadedUpdate() {
        val downloaded = downloadedUpdate() ?: run {
            state = AndroidUpdateState.Idle
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            preferences.edit().putBoolean(KEY_WAITING_FOR_INSTALL_PERMISSION, true).commit()
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
            return
        }
        launchInstaller(downloaded.second)
    }

    fun onResume() {
        if (!preferences.getBoolean(KEY_WAITING_FOR_INSTALL_PERMISSION, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            return
        }
        preferences.edit().putBoolean(KEY_WAITING_FOR_INSTALL_PERMISSION, false).commit()
        downloadedUpdate()?.second?.let(::launchInstaller)
    }

    fun openPlayStore() = Unit

    fun dismiss() {
        state = AndroidUpdateState.Idle
    }

    private fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.updates",
            file,
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun downloadedUpdate(): Pair<String, File>? {
        val version = preferences.getString(KEY_DOWNLOADED_VERSION, null) ?: return null
        val path = preferences.getString(KEY_DOWNLOADED_PATH, null) ?: return null
        if (!isNewerVersion(version, currentVersion())) {
            clearDownloadedUpdate(File(path))
            return null
        }
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) {
            clearDownloadedUpdate(file)
            return null
        }
        return version to file
    }

    private fun clearDownloadedUpdate(file: File) {
        runCatching { file.delete() }
        preferences.edit()
            .remove(KEY_DOWNLOADED_VERSION)
            .remove(KEY_DOWNLOADED_PATH)
            .remove(KEY_WAITING_FOR_INSTALL_PERMISSION)
            .commit()
    }

    private fun currentVersion(): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"

    private companion object {
        const val PREFERENCES_NAME = "aive.github-updater.v1"
        const val KEY_LAST_CHECK = "last-check"
        const val KEY_DOWNLOADED_VERSION = "downloaded-version"
        const val KEY_DOWNLOADED_PATH = "downloaded-path"
        const val KEY_WAITING_FOR_INSTALL_PERMISSION = "waiting-install-permission"
        const val CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
