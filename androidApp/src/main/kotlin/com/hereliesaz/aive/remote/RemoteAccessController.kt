package com.hereliesaz.aive.remote

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import com.hereliesaz.aive.R

/**
 * What the Remote access settings do. Turning it on creates a pairing and starts
 * [AiveRelayHostService]; turning it off, or resetting the pairing, deletes the old room on the
 * relay so the old code stops working at once. Each returns a user-facing problem, or null.
 */
internal class RemoteAccessController(context: Context) {
    private val context = context.applicationContext
    val store = RemoteRelayStore(context)

    fun turnOn(): String? = attempt("Could not turn on Remote access") {
        store.enable()
        AiveRelayHostService.start(context)
    }

    fun turnOff() {
        AiveRelayHostService.stop(context)
        RemoteRelayAdmin.deleteRoom(store.disable())
    }

    fun resetPairing(): String? = attempt("Could not reset the pairing") {
        AiveRelayHostService.stop(context)
        val (old, _) = store.reset()
        RemoteRelayAdmin.deleteRoom(old)
        AiveRelayHostService.start(context)
    }

    /** Resumes the link after the app process was recreated while Remote access is on. */
    fun resumeIfEnabled() {
        if (store.enabled) AiveRelayHostService.start(context)
    }

    fun copy(code: String) {
        val clip = ClipData.newPlainText("The Aive pairing code", code)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Keeps the code out of the clipboard preview.
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, R.string.remote_access_copied, Toast.LENGTH_SHORT).show()
        // The code is a bearer secret, so it does not stay on the clipboard: it is cleared after a
        // minute unless something else has been copied since.
        Handler(Looper.getMainLooper()).postDelayed({
            val current = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (current == code) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, CLIPBOARD_CLEAR_MILLIS)
    }

    fun share(code: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code)
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.remote_access_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun attempt(fallback: String, block: () -> Unit): String? =
        try {
            block()
            null
        } catch (problem: Exception) {
            problem.message ?: fallback
        }

    private companion object {
        const val CLIPBOARD_CLEAR_MILLIS = 60_000L
    }
}
