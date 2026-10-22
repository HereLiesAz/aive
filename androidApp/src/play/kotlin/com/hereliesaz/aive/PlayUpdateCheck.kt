package com.hereliesaz.aive

import androidx.activity.ComponentActivity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun createPlayUpdateCheck(activity: ComponentActivity): PlayUpdateCheck? {
    val appUpdateManager = AppUpdateManagerFactory.create(activity)
    return object : PlayUpdateCheck {
        override suspend fun isUpdateAvailable(): Boolean =
            suspendCancellableCoroutine { continuation ->
                val task = appUpdateManager.appUpdateInfo
                task.addOnSuccessListener { info ->
                    if (continuation.isActive) {
                        continuation.resume(info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE)
                    }
                }
                task.addOnFailureListener { failure ->
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
                task.addOnCanceledListener { continuation.cancel() }
            }
    }
}
