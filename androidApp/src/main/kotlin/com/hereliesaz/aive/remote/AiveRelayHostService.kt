package com.hereliesaz.aive.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hereliesaz.aive.MainActivity
import com.hereliesaz.aive.R
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the encrypted link to the user's own IDEaz open while Remote
 * access is on (Settings → Remote access; off by default). It holds the host WebSocket to the
 * relay ([RelayHostLink]) and answers sealed prompts and launches through the same
 * [AiveRemoteHandler] the bound [AiveRemoteService] uses. The persistent notification's Stop
 * action turns Remote access off, which deletes the room and forgets the pairing; a service the
 * system merely stops leaves both in place. Protocol: docs/Remote-API.md, "Relay".
 */
class AiveRelayHostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var link: Job? = null
    private var httpClient: HttpClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            turnOff()
            return START_NOT_STICKY
        }
        // Within seconds of startForegroundService, whatever happens next.
        try {
            enterForeground(getString(R.string.remote_access_notification_connecting))
        } catch (_: RuntimeException) {
            // Not allowed to run in the foreground right now (background start, missing grant).
            stopSelf()
            return START_NOT_STICKY
        }
        val store = RemoteRelayStore(this)
        val pairing = if (store.enabled) store.pairing() else null
        if (pairing == null) {
            AiveRelayHostState.setStatus(null)
            stopSelf()
            return START_NOT_STICKY
        }
        if (link?.isActive != true) {
            val client = HttpClient(CIO) { install(WebSockets) }
            httpClient = client
            val handler = AiveRemoteHandler(this)
            link = scope.launch { RelayHostLink(pairing, handler, client, ::publishStatus).run() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        httpClient?.close()
        AiveRelayHostState.setStatus(null)
        super.onDestroy()
    }

    /** The user pressed Stop: turn Remote access off, which revokes the pairing. */
    private fun turnOff() {
        RemoteRelayAdmin.deleteRoom(RemoteRelayStore(this).disable())
        AiveRelayHostState.settingsChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishStatus(text: String) {
        AiveRelayHostState.setStatus(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun enterForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_access_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, AiveRelayHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.haive_monochrome)
            .setContentTitle(getString(R.string.remote_access_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.remote_access_notification_stop), stop)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.hereliesaz.aive.action.REMOTE_RELAY_STOP"
        private const val CHANNEL_ID = "aive.remote-access"
        private const val NOTIFICATION_ID = 7301

        /** Starts the link as a foreground service; the caller has already checked Remote access is on. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AiveRelayHostService::class.java))
            } catch (_: RuntimeException) {
                // The system refused a foreground start from the background; the next app open retries.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AiveRelayHostService::class.java))
        }
    }
}
