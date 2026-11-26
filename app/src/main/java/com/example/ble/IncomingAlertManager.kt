package com.example.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.DebugLogger
import com.example.model.ContactCard

/**
 * Handles background proximity triggers when Name Drop is closed or backgrounded:
 * - When Display Over Other Apps (SYSTEM_ALERT_WINDOW) is enabled, launches the full-screen
 *   Name Drop animation & Contact Card directly on top of other apps, with an interactive
 *   system overlay card backup.
 * - Otherwise posts a high-priority notification so the user can open immediately.
 */
object IncomingAlertManager {

    const val EXTRA_FROM_BACKGROUND_PROXIMITY = "extra_from_background_proximity"
    const val EXTRA_BACKGROUND_PEER_NAME = "extra_background_peer_name"
    const val EXTRA_BACKGROUND_RSSI = "extra_background_rssi"
    const val EXTRA_BACKGROUND_KIND = "extra_background_kind"

    private const val CHANNEL_ALERTS = "namedrop_incoming_alerts"
    private const val NOTIFICATION_ID_INCOMING = 2042
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeOverlayView: LinearLayout? = null

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val channel = NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a nearby phone drops a Contact Card, photo, or video"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun triggerBackgroundProximityLaunch(
        context: Context,
        peerName: String,
        rssi: Int,
        kind: String = "contact"
    ) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(EXTRA_FROM_BACKGROUND_PROXIMITY, true)
            putExtra(EXTRA_BACKGROUND_PEER_NAME, peerName)
            putExtra(EXTRA_BACKGROUND_RSSI, rssi)
            putExtra(EXTRA_BACKGROUND_KIND, kind)
        }

        if (Settings.canDrawOverlays(context)) {
            mainHandler.post {
                val launched = runCatching {
                    context.startActivity(launchIntent)
                    true
                }.onFailure {
                    DebugLogger.log("Overlay", "Direct background launch failed: ${it.message}", "WARN")
                }.getOrDefault(false)
                if (!launched) {
                    showOverlayCard(
                        context = context,
                        contact = ContactCard(displayName = peerName, phoneNumber = "Nearby ($rssi dBm)"),
                        onAcceptOpenApp = {},
                        onDismiss = {}
                    )
                }
            }
        } else {
            DebugLogger.log(
                "Overlay",
                "Overlay permission disabled; posting high-priority alert fallback",
                "INFO"
            )
            postTapToOpenNotification(
                context = context,
                title = "Name Drop • $peerName Nearby",
                body = "Tap to open Name Drop animation & accept ($rssi dBm)",
                customIntent = launchIntent
            )
        }
    }

    fun showBackgroundContactPopup(
        context: Context,
        contact: ContactCard,
        rssi: Int = -45,
        onAcceptOpenApp: () -> Unit,
        onDismiss: () -> Unit
    ) {
        if (Settings.canDrawOverlays(context)) {
            mainHandler.post {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                    putExtra(EXTRA_FROM_BACKGROUND_PROXIMITY, true)
                    putExtra(EXTRA_BACKGROUND_PEER_NAME, contact.displayName.ifBlank { "Nearby Contact" })
                    putExtra(EXTRA_BACKGROUND_RSSI, rssi)
                    putExtra(EXTRA_BACKGROUND_KIND, "contact")
                }
                val started = runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (!started) {
                    showOverlayCard(context, contact, onAcceptOpenApp, onDismiss)
                }
            }
        } else {
            postTapToOpenNotification(
                context,
                title = "${contact.displayName.ifBlank { "Nearby Phone" }} wants to Name Drop",
                body = "Tap to open Name Drop and accept ${contact.phoneNumber}"
            )
        }
    }

    fun showBackgroundMediaAlert(
        context: Context,
        senderName: String,
        isVideo: Boolean,
        isGroup: Boolean
    ) {
        val kindLabel = if (isVideo) "Video" else "Photo"
        val title = if (isGroup) "Group $kindLabel from $senderName" else "$kindLabel from $senderName"
        if (Settings.canDrawOverlays(context)) {
            triggerBackgroundProximityLaunch(
                context = context,
                peerName = senderName,
                rssi = -44,
                kind = "media"
            )
        } else {
            postTapToOpenNotification(
                context,
                title = title,
                body = "Tap to Accept and save directly to Gallery"
            )
        }
    }

    private fun showOverlayCard(
        context: Context,
        contact: ContactCard,
        onAcceptOpenApp: () -> Unit,
        onDismiss: () -> Unit
    ) {
        dismissOverlay(context)
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dp = { v: Float ->
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    v,
                    context.resources.displayMetrics
                ).toInt()
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22f), dp(20f), dp(22f), dp(20f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FAFFFFFF"))
                    cornerRadius = dp(26f).toFloat()
                    setStroke(dp(2f), Color.parseColor("#0A84FF"))
                }
                elevation = dp(16f).toFloat()
            }

            val eyebrow = TextView(context).apply {
                text = "NAME DROP • NEARBY DEVICE"
                textSize = 11f
                setTextColor(Color.parseColor("#0A84FF"))
                setPadding(0, 0, 0, dp(6f))
            }
            val nameTv = TextView(context).apply {
                text = contact.displayName.ifBlank { "Nearby Contact" }
                textSize = 22f
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, dp(4f))
            }
            val phoneTv = TextView(context).apply {
                text = contact.phoneNumber.ifBlank { "Tap Open to view full-screen Name Drop" }
                textSize = 15f
                setTextColor(Color.parseColor("#6C6C70"))
                setPadding(0, 0, 0, dp(16f))
            }

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val dismissBtn = Button(context).apply {
                text = "Dismiss"
                setTextColor(Color.parseColor("#FF3B30"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F2F2F7"))
                    cornerRadius = dp(24f).toFloat()
                }
                setOnClickListener {
                    dismissOverlay(context)
                    onDismiss()
                }
            }
            val openBtn = Button(context).apply {
                text = "Open Name Drop"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0A84FF"))
                    cornerRadius = dp(24f).toFloat()
                }
                setOnClickListener {
                    dismissOverlay(context)
                    onAcceptOpenApp()
                    val intent = Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                        putExtra(EXTRA_FROM_BACKGROUND_PROXIMITY, true)
                    }
                    context.startActivity(intent)
                }
            }

            val btnParams = LinearLayout.LayoutParams(0, dp(48f), 1f).apply {
                marginEnd = dp(8f)
            }
            val openParams = LinearLayout.LayoutParams(0, dp(48f), 1f)
            btnRow.addView(dismissBtn, btnParams)
            btnRow.addView(openBtn, openParams)

            container.addView(eyebrow)
            container.addView(nameTv)
            container.addView(phoneTv)
            container.addView(btnRow)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(48f)
            }

            wm.addView(container, params)
            activeOverlayView = container
            DebugLogger.log("Overlay", "Displayed background Name Drop overlay over other apps")
        }.onFailure {
            DebugLogger.log("Overlay", "Overlay draw failed: ${it.message}", "ERROR")
        }
    }

    fun dismissOverlay(context: Context) {
        mainHandler.post {
            activeOverlayView?.let { view ->
                runCatching {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeViewImmediate(view)
                }
                activeOverlayView = null
            }
        }
    }

    private fun postTapToOpenNotification(
        context: Context,
        title: String,
        body: String,
        customIntent: Intent? = null
    ) {
        ensureChannels(context)
        val intent = customIntent ?: Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID_INCOMING, notification)
    }
}
