package com.druk.lmplayground.apiserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.druk.lmplayground.MainActivity
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service hosting the local OpenAI-compatible API server.
 *
 * Runs in the main app process (it drives inference through the app-scoped
 * InferenceClient the UI already uses). Being a foreground service keeps the
 * process — and the open WiFi socket — alive while the app is backgrounded.
 * Watches the WiFi connection and rebinds / tears down as it changes so the
 * server is always bound to the current WiFi interface only.
 */
class ApiServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generator = ChatGenerator()
    private var server: OpenAiApiServer? = null
    private var wifiRegistration: WifiInterface.Registration? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundNotification()
                if (wifiRegistration == null) {
                    wifiRegistration = WifiInterface.watchWifi(this) { reconcile() }
                }
                reconcile()
            }
        }
        return START_STICKY
    }

    /** (Re)bind the server to the current WiFi address, or stop it if WiFi is gone. */
    @Synchronized
    private fun reconcile() {
        val prefs = StoragePreferences(this)
        if (!prefs.apiServerEnabled) {
            stopServerAndSelf()
            return
        }
        val port = prefs.apiServerPort
        val ip = WifiInterface.currentWifiIpv4(this)

        if (ip == null) {
            stopServerInstance()
            val status = ApiServerController.Status(
                enabled = true, running = false, url = null,
                message = getString(R.string.api_server_status_no_wifi),
            )
            ApiServerController.publishStatus(status)
            updateNotification(getString(R.string.api_server_status_no_wifi))
            return
        }

        val current = server
        val desiredUrl = "http://${ip.hostAddress}:$port"
        // Already bound to the same address:port? Nothing to do.
        if (current != null && current.listeningUrl == desiredUrl) {
            ApiServerController.publishStatus(
                ApiServerController.Status(enabled = true, running = true, url = desiredUrl),
            )
            return
        }

        stopServerInstance()
        try {
            val newServer = OpenAiApiServer(
                hostAddress = ip,
                port = port,
                apiKeyProvider = { StoragePreferences(this).apiServerKey },
                generator = generator,
                scope = scope,
            ).also { it.listeningUrl = desiredUrl }
            newServer.start(SOCKET_READ_TIMEOUT_MS, false)
            server = newServer
            ApiServerController.publishStatus(
                ApiServerController.Status(enabled = true, running = true, url = desiredUrl),
            )
            updateNotification(getString(R.string.api_server_notification_running, desiredUrl))
            Log.i(TAG, "API server listening on $desiredUrl")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start API server", t)
            val msg = getString(R.string.api_server_status_bind_failed, t.message ?: "")
            ApiServerController.publishStatus(
                ApiServerController.Status(enabled = true, running = false, url = null, message = msg),
            )
            updateNotification(msg)
        }
    }

    private fun stopServerInstance() {
        server?.let { runCatching { it.stop() } }
        server = null
    }

    private fun stopServerAndSelf() {
        stopServerInstance()
        wifiRegistration?.unregister()
        wifiRegistration = null
        ApiServerController.publishStatus(ApiServerController.Status(enabled = false, running = false))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServerInstance()
        wifiRegistration?.unregister()
        wifiRegistration = null
        scope.cancel()
        super.onDestroy()
    }

    // --- Foreground notification ---

    private fun startForegroundNotification() {
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.api_server_notification_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.api_server_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
            },
        )
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.api_server_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        private const val TAG = "ApiServerService"
        private const val CHANNEL_ID = "api_server"
        private const val NOTIFICATION_ID = 0x1A1A_5E27.toInt()
        private const val SOCKET_READ_TIMEOUT_MS = 60_000
        const val ACTION_START = "com.druk.lmplayground.apiserver.START"
        const val ACTION_STOP = "com.druk.lmplayground.apiserver.STOP"

        fun startIntent(context: Context): Intent =
            Intent(context, ApiServerService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, ApiServerService::class.java).setAction(ACTION_STOP)
    }
}
