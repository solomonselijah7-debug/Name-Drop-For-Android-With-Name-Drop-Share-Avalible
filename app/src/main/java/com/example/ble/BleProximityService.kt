package com.example.ble

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
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.NameDropApp
import com.example.R
import com.example.data.DebugLogger

/**
 * START_STICKY low-power BLE foreground service.
 * On devices with Display Over Other Apps disabled, automatically pauses when the app moves
 * to the background, and resumes when Name Drop is open.
 */
class BleProximityService : Service() {

    companion object {
        private const val CHANNEL_ID = "namedrop_ble_service"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.action.START_BLE"
        const val ACTION_STOP = "com.example.action.STOP_BLE"
        const val EXTRA_LOW_POWER = "extra_low_power"

        fun startService(context: Context, lowPower: Boolean) {
            val readiness = BlePermissions.evaluateReadiness(context)
            if (!readiness.canProceedToContactSetup) {
                DebugLogger.log("Service", "Skipped FGS start: readiness prerequisites incomplete", "WARN")
                return
            }
            val intent = Intent(context, BleProximityService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LOW_POWER, lowPower)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                DebugLogger.log("Service", "Failed starting BleProximityService: ${it.message}", "ERROR")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BleProximityService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.stopService(intent) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NameDropApp.bleManager.stopAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val lowPower = intent?.getBooleanExtra(EXTRA_LOW_POWER, true) ?: true
        val notification = buildNotification(lowPower)
        runCatching {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (BlePermissions.hasForegroundLocation(this)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                type
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        }.onFailure {
            DebugLogger.log("Service", "startForeground failed: ${it.message}", "ERROR")
        }

        NameDropApp.bleManager.startProximity(lowPowerBackground = lowPower)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Name Drop BLE proximity ready with minimal battery impact"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(lowPower: Boolean): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(
                if (lowPower) {
                    getString(R.string.service_notification_body_background)
                } else {
                    getString(R.string.service_notification_body_foreground)
                }
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }
}
