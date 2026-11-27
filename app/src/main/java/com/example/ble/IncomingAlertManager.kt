package com.example.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import com.example.ai.GeminiDiagnosticWatcher
import com.example.data.DebugLogger
import com.example.model.ContactCard

/**
 * Handles background proximity triggers when Name Drop is closed or backgrounded:
 * - When Display Over Other Apps (SYSTEM_ALERT_WINDOW) is enabled, mounts a true
 *   TYPE_APPLICATION_OVERLAY floating box on top of the current app (works reliably
 *   even when Background Activity Launches are restricted on Android 10–16, Huawei EMUI,
 *   Samsung One UI, and HTC devices) and logs the exact result to GeminiDiagnosticWatcher.
 * - Falls back cleanly to a high-priority tap-to-open notification if overlay is disabled
 *   or blocked by the OEM.
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
    private var activeOverlayToken: Long = 0L

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

        GeminiDiagnosticWatcher.onOverlayLaunchAttempt()
        if (Settings.canDrawOverlays(context)) {
            mainHandler.post {
                val overlayShown = showOverlayCard(
                    context = context,
                    eyebrowLabel = if (kind == "media") "NAME DROP • INCOMING MEDIA" else "NAME DROP • NEARBY DEVICE",
                    titleText = peerName,
                    subtitleText = "Detected nearby ($rssi dBm) • Tap Open to start animation",
                    savedBadgeText = null,
                    phoneNumber = null,
                    primaryButtonLabel = "Open Name Drop",
                    launchIntent = launchIntent,
                    onAcceptOpenApp = {},
                    onDismiss = {}
                )
                if (overlayShown) {
                    GeminiDiagnosticWatcher.onOverlayLaunchResult(
                        context = context,
                        launchMode = "TYPE_APPLICATION_OVERLAY_PROXIMITY",
                        success = true,
                        detail = "Floating proximity overlay displayed over other apps for $peerName ($rssi dBm)"
                    )
                } else {
                    val activityStarted = runCatching {
                        context.startActivity(launchIntent)
                        true
                    }.getOrDefault(false)
                    if (activityStarted) {
                        GeminiDiagnosticWatcher.onOverlayLaunchResult(
                            context = context,
                            launchMode = "BACKGROUND_ACTIVITY_FALLBACK",
                            success = true,
                            detail = "System overlay rejected; opened MainActivity fallback for $peerName"
                        )
                    } else {
                        GeminiDiagnosticWatcher.onOverlayLaunchResult(
                            context = context,
                            launchMode = "NOTIFICATION_FALLBACK",
                            success = false,
                            detail = "System overlay and background activity launch both blocked on ${Build.MANUFACTURER} ${Build.MODEL}"
                        )
                        postTapToOpenNotification(
                            context = context,
                            title = "Name Drop • $peerName Nearby",
                            body = "Tap to open Name Drop animation & accept ($rssi dBm)",
                            customIntent = launchIntent
                        )
                    }
                }
            }
        } else {
            DebugLogger.log(
                "Overlay",
                "Overlay permission disabled; posting high-priority alert fallback",
                "INFO"
            )
            GeminiDiagnosticWatcher.onOverlayFailure(
                context,
                "Display Over Other Apps disabled when peer ($peerName, $rssi dBm) detected in background"
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
        savedStatusText: String? = "Contact saved",
        onAcceptOpenApp: () -> Unit,
        onDismiss: () -> Unit
    ) {
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

        if (Settings.canDrawOverlays(context)) {
            mainHandler.post {
                val overlayShown = showOverlayCard(
                    context = context,
                    eyebrowLabel = "NAME DROP • CONTACT RECEIVED ($rssi dBm)",
                    titleText = contact.displayName.ifBlank { "Nearby Contact" },
                    subtitleText = contact.phoneNumber.ifBlank { "Tap Open to view full Name Drop card" },
                    savedBadgeText = savedStatusText,
                    phoneNumber = contact.phoneNumber.takeIf { it.isNotBlank() },
                    primaryButtonLabel = "View Card",
                    launchIntent = intent,
                    onAcceptOpenApp = onAcceptOpenApp,
                    onDismiss = onDismiss
                )
                if (overlayShown) {
                    GeminiDiagnosticWatcher.onOverlayLaunchResult(
                        context = context,
                        launchMode = "TYPE_APPLICATION_OVERLAY_CONTACT",
                        success = true,
                        detail = "Floating Contact Card overlay displayed (${contact.displayName})"
                    )
                } else {
                    GeminiDiagnosticWatcher.onOverlayLaunchResult(
                        context = context,
                        launchMode = "TYPE_APPLICATION_OVERLAY_CONTACT",
                        success = false,
                        detail = "Contact overlay blocked on ${Build.MANUFACTURER} ${Build.MODEL}; posted notification fallback"
                    )
                    postTapToOpenNotification(
                        context,
                        title = "${contact.displayName.ifBlank { "Nearby Phone" }} • Contact saved",
                        body = "Tap to view ${contact.phoneNumber.ifBlank { "Contact Card" }}",
                        customIntent = intent
                    )
                }
            }
        } else {
            GeminiDiagnosticWatcher.onOverlayLaunchResult(
                context = context,
                launchMode = "NOTIFICATION_CONTACT_FALLBACK",
                success = false,
                detail = "Display Over Other Apps disabled for background contact popup"
            )
            postTapToOpenNotification(
                context,
                title = "${contact.displayName.ifBlank { "Nearby Phone" }} • Contact saved",
                body = "Tap to open Name Drop and view ${contact.phoneNumber}",
                customIntent = intent
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
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                putExtra(EXTRA_FROM_BACKGROUND_PROXIMITY, true)
                putExtra(EXTRA_BACKGROUND_PEER_NAME, senderName)
                putExtra(EXTRA_BACKGROUND_RSSI, -44)
                putExtra(EXTRA_BACKGROUND_KIND, "media")
            }
            mainHandler.post {
                val shown = showOverlayCard(
                    context = context,
                    eyebrowLabel = "NAME DROP • INCOMING ${kindLabel.uppercase()}",
                    titleText = title,
                    subtitleText = "Tap Accept in Name Drop to save to Gallery",
                    savedBadgeText = null,
                    phoneNumber = null,
                    primaryButtonLabel = "Accept $kindLabel",
                    launchIntent = launchIntent,
                    onAcceptOpenApp = {},
                    onDismiss = {}
                )
                GeminiDiagnosticWatcher.onOverlayLaunchResult(
                    context = context,
                    launchMode = "TYPE_APPLICATION_OVERLAY_MEDIA",
                    success = shown,
                    detail = if (shown) {
                        "Floating incoming $kindLabel alert shown over current app"
                    } else {
                        "Floating media overlay failed on ${Build.MANUFACTURER} ${Build.MODEL}"
                    }
                )
                if (!shown) {
                    postTapToOpenNotification(
                        context = context,
                        title = title,
                        body = "Tap to Accept and save directly to Gallery",
                        customIntent = launchIntent
                    )
                }
            }
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
        eyebrowLabel: String,
        titleText: String,
        subtitleText: String,
        savedBadgeText: String?,
        phoneNumber: String?,
        primaryButtonLabel: String,
        launchIntent: Intent,
        onAcceptOpenApp: () -> Unit,
        onDismiss: () -> Unit
    ): Boolean {
        val token = ++activeOverlayToken
        removeOverlayImmediateOnMain(context, expectedToken = null)
        return runCatching {
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
                setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FCFFFFFF"))
                    cornerRadius = dp(26f).toFloat()
                    setStroke(dp(2f), Color.parseColor("#0A84FF"))
                }
                elevation = dp(18f).toFloat()
            }

            val eyebrow = TextView(context).apply {
                text = eyebrowLabel
                textSize = 11f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#0A84FF"))
                setPadding(0, 0, 0, dp(4f))
            }
            val nameTv = TextView(context).apply {
                text = titleText
                textSize = 21f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, dp(4f))
            }
            val phoneTv = TextView(context).apply {
                text = subtitleText
                textSize = 15f
                setTextColor(Color.parseColor("#6C6C70"))
                setPadding(0, 0, 0, dp(10f))
            }

            container.addView(eyebrow)
            container.addView(nameTv)
            container.addView(phoneTv)

            if (!savedBadgeText.isNullOrBlank()) {
                val badge = TextView(context).apply {
                    text = "✓ $savedBadgeText"
                    textSize = 12f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.parseColor("#166534"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#DCFCE7"))
                        cornerRadius = dp(14f).toFloat()
                    }
                    setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                }
                container.addView(
                    badge,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(12f) }
                )
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
            btnRow.addView(
                dismissBtn,
                LinearLayout.LayoutParams(0, dp(48f), 1f).apply { marginEnd = dp(8f) }
            )

            if (!phoneNumber.isNullOrBlank()) {
                val callBtn = Button(context).apply {
                    text = "Call"
                    setTextColor(Color.parseColor("#111827"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E5E7EB"))
                        cornerRadius = dp(24f).toFloat()
                    }
                    setOnClickListener {
                        dismissOverlay(context)
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(dialIntent) }
                    }
                }
                btnRow.addView(
                    callBtn,
                    LinearLayout.LayoutParams(0, dp(48f), 0.9f).apply { marginEnd = dp(8f) }
                )
            }

            val openBtn = Button(context).apply {
                text = primaryButtonLabel
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0A84FF"))
                    cornerRadius = dp(24f).toFloat()
                }
                setOnClickListener {
                    dismissOverlay(context)
                    onAcceptOpenApp()
                    runCatching { context.startActivity(launchIntent) }
                }
            }
            btnRow.addView(openBtn, LinearLayout.LayoutParams(0, dp(48f), 1.2f))

            container.addView(btnRow)

            val wrapper = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), 0, dp(14f), 0)
                addView(
                    container,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

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

            if (activeOverlayToken != token) return false
            wm.addView(wrapper, params)
            activeOverlayView = wrapper
            DebugLogger.log("Overlay", "Displayed background Name Drop floating overlay over other apps")
            true
        }.onFailure {
            DebugLogger.log("Overlay", "Overlay draw failed: ${it.message}", "ERROR")
            GeminiDiagnosticWatcher.onOverlayFailure(context, "WindowManager overlay failed: ${it.message}")
        }.getOrDefault(false)
    }

    fun dismissOverlay(context: Context) {
        val appCtx = context.applicationContext
        val targetToken = activeOverlayToken
        removeOverlayImmediateOnMain(appCtx, expectedToken = targetToken)
    }

    private fun removeOverlayImmediateOnMain(context: Context, expectedToken: Long?) {
        val doRemove = {
            if (expectedToken == null || expectedToken == activeOverlayToken) {
                activeOverlayView?.let { view ->
                    runCatching {
                        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        wm.removeViewImmediate(view)
                    }
                    activeOverlayView = null
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doRemove()
        } else {
            mainHandler.post { doRemove() }
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
