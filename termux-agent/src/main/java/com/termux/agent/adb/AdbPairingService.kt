package com.termux.agent.adb

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.termux.agent.start.AgentPrivilegeOrchestrator
import com.termux.agent.start.AgentSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "TermuxAdbPairingSvc"

@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adbMdns: AdbMdns? = null
    private var started = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Terminal Agent ADB Pairing",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            ACTION_START -> onStartSearch()
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_PAIRING_CODE)?.toString()?.trim().orEmpty()
                val host = intent.getStringExtra(KEY_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(KEY_PORT, -1)
                if (port > 0 && code.isNotEmpty()) {
                    onPairingCode(code, host, port)
                } else {
                    onStartSearch()
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            else -> return START_NOT_STICKY
        }

        if (notification != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }.onFailure {
                Log.w(TAG, "startForeground failed, posting normal notification", it)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun onStartSearch(): Notification {
        if (!started) {
            started = true
            adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING) { (host, port) ->
                if (port > 0 && host.isNotEmpty()) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, createInputNotification(host, port))
                }
            }.apply { start() }
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Searching for Wireless Debugging pairing...")
            .setContentText("Enable Wireless Debugging -> Pair device with pairing code")
            .addAction(stopAction())
            .build()
    }

    private fun onPairingCode(code: String, host: String, port: Int): Notification {
        scope.launch {
            val result = runCatching {
                val key = AdbKey(this@AdbPairingService, PreferenceAdbKeyStore(AgentSettings.prefs(this@AdbPairingService)))
                AdbPairingClient(host, port, code, key).use { it.start() }
            }
            val success = result.getOrDefault(false)
            if (success) {
                stopSearch()
                runCatching {
                    AgentPrivilegeOrchestrator.ensureStarted(this@AdbPairingService)
                }
            }
            postResultNotification(success, result.exceptionOrNull())
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pairing Terminal Agent with ADB...")
            .build()
    }

    private fun postResultNotification(success: Boolean, error: Throwable?) {
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true)
        if (success) {
            builder.setContentTitle("Terminal Agent paired")
                .setContentText("ADB pairing succeeded. You can run `tdev status` in Terminal.")
        } else {
            builder.setContentTitle("Terminal Agent pairing failed")
                .setContentText(error?.message ?: "Wrong pairing code or port closed")
                .addAction(retryAction())
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    private fun createInputNotification(host: String, port: Int): Notification {
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel("Enter 6-digit pairing code")
            .build()
        val replyIntent = Intent(this, AdbPairingService::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(KEY_HOST, host)
            .putExtra(KEY_PORT, port)
        val replyPending = PendingIntent.getService(
            this, 1, replyIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        val replyAction = Notification.Action.Builder(null, "Enter pairing code", replyPending)
            .addRemoteInput(remoteInput)
            .build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pairing service found ($host:$port)")
            .setContentText("Tap Enter pairing code and submit the 6-digit code")
            .addAction(replyAction)
            .addAction(stopAction())
            .build()
    }

    private fun stopAction(): Notification.Action {
        val pending = PendingIntent.getService(
            this, 2, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null, "Stop", pending).build()
    }

    private fun retryAction(): Notification.Action {
        val pending = PendingIntent.getService(
            this, 3, startIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null, "Retry", pending).build()
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
        adbMdns = null
    }

    override fun onDestroy() {
        stopSearch()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_CHANNEL = "termux_agent_pairing"
        const val NOTIFICATION_ID = 41002
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_REPLY = "reply"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_HOST = "pairing_host"
        private const val KEY_PORT = "pairing_port"

        fun startIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP)
    }
}
