package com.example.ui.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.NameDropApp
import com.example.ai.GeminiDiagnosticWatcher
import com.example.data.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Renders a genuine WindowManager TYPE_APPLICATION_OVERLAY floating box directly over
 * the Gallery or other app when Display Over Other Apps is enabled.
 *
 * Includes post-attach visibility verification (isAttachedAndVisible) and an
 * OnAttachStateChangeListener so if an OEM suppresses or detaches the overlay,
 * FloatingShareOverlayActivity keeps its floating translucent sheet visible and
 * logs diagnostics to GeminiDiagnosticWatcher.
 */
object FloatingShareOverlayWindow {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var rootContainer: LinearLayout? = null
    private var stateObserverJob: Job? = null
    private var activeWindowToken: Long = 0L

    fun isAttachedAndVisible(): Boolean {
        val container = rootContainer ?: return false
        return container.isAttachedToWindow &&
            container.windowVisibility == View.VISIBLE &&
            container.width > 0 &&
            container.height > 0
    }

    fun show(context: Context): Result<Unit> {
        val appCtx = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return Result.failure(IllegalStateException("Overlay must be mounted on main thread"))
        }

        val myToken = ++activeWindowToken
        removeContainerImmediate(appCtx, expectedToken = null)

        return runCatching {
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dp = { v: Float ->
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    v,
                    appCtx.resources.displayMetrics
                ).toInt()
            }

            val card = LinearLayout(appCtx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FCFFFFFF"))
                    cornerRadius = dp(28f).toFloat()
                    setStroke(dp(1.5f), Color.parseColor("#1A0A84FF"))
                }
                elevation = dp(20f).toFloat()
            }

            val headerRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val ringView = DropRingIndicatorView(appCtx)
            val ringParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                marginEnd = dp(12f)
            }

            val titleCol = LinearLayout(appCtx).apply {
                orientation = LinearLayout.VERTICAL
            }
            val titleTv = TextView(appCtx).apply {
                text = "NameDrop Share"
                textSize = 17f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#111827"))
            }
            val statusTv = TextView(appCtx).apply {
                text = "Hold this phone against another phone"
                textSize = 14f
                setTextColor(Color.parseColor("#0A84FF"))
            }
            titleCol.addView(titleTv)
            titleCol.addView(statusTv)

            val closeBtn = Button(appCtx).apply {
                text = "✕"
                isAllCaps = false
                textSize = 15f
                minimumWidth = dp(48f)
                minimumHeight = dp(48f)
                contentDescription = "Close share overlay"
                setTextColor(Color.parseColor("#6C6C70"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F2F2F7"))
                    cornerRadius = dp(24f).toFloat()
                }
                setOnClickListener {
                    FloatingShareOverlayCoordinator.dismissSession(appCtx)
                }
            }

            headerRow.addView(ringView, ringParams)
            headerRow.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            headerRow.addView(closeBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))

            val previewFrame = FrameLayout(appCtx).apply {
                visibility = View.GONE
            }
            val previewIv = ImageView(appCtx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Shared media preview"
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadius = dp(16f).toFloat()
                }
                clipToOutline = true
            }
            previewFrame.addView(
                previewIv,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(148f))
            )

            val peerTv = TextView(appCtx).apply {
                text = "Scanning via Bluetooth proximity..."
                textSize = 13f
                setTextColor(Color.parseColor("#6B7280"))
                setPadding(0, dp(10f), 0, dp(12f))
            }

            val primaryBtnRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val receiveOnlyBtn = Button(appCtx).apply {
                text = "Receive Only"
                isAllCaps = false
                minimumHeight = dp(48f)
                contentDescription = "Switch popup to Receive Only waiting mode"
                setTextColor(Color.parseColor("#0A84FF"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E8F2FF"))
                    cornerRadius = dp(24f).toFloat()
                }
                setOnClickListener {
                    FloatingShareOverlayCoordinator.triggerReceiveOnlyWaiting(appCtx)
                }
            }

            val shareNowBtn = Button(appCtx).apply {
                text = "Share"
                isAllCaps = false
                minimumHeight = dp(48f)
                contentDescription = "Share selected media via Bluetooth"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0A84FF"))
                    cornerRadius = dp(24f).toFloat()
                }
                setOnClickListener {
                    val state = FloatingShareOverlayCoordinator.uiState.value
                    if (state.needsBluetoothSetup || (state.isError && state.itemCount == 0)) {
                        openFullApp(appCtx)
                    } else {
                        FloatingShareOverlayCoordinator.triggerShareNow(appCtx)
                    }
                }
            }

            primaryBtnRow.addView(
                receiveOnlyBtn,
                LinearLayout.LayoutParams(0, dp(48f), 1f).apply { marginEnd = dp(10f) }
            )
            primaryBtnRow.addView(
                shareNowBtn,
                LinearLayout.LayoutParams(0, dp(48f), 1.25f)
            )

            val secondaryBtnRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val cancelBtn = Button(appCtx).apply {
                text = "Cancel"
                isAllCaps = false
                minimumHeight = dp(48f)
                contentDescription = "Cancel NameDrop Share"
                setTextColor(Color.parseColor("#111827"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F2F2F7"))
                    cornerRadius = dp(24f).toFloat()
                }
                setOnClickListener {
                    FloatingShareOverlayCoordinator.dismissSession(appCtx)
                }
            }

            val openAppBtn = Button(appCtx).apply {
                text = "Open App"
                isAllCaps = false
                minimumHeight = dp(48f)
                visibility = View.GONE
                contentDescription = "Open full NameDrop app"
                setTextColor(Color.parseColor("#111827"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E5E7EB"))
                    cornerRadius = dp(24f).toFloat()
                }
                setOnClickListener {
                    openFullApp(appCtx)
                }
            }

            secondaryBtnRow.addView(
                cancelBtn,
                LinearLayout.LayoutParams(0, dp(48f), 1f)
            )
            secondaryBtnRow.addView(
                openAppBtn,
                LinearLayout.LayoutParams(0, dp(48f), 1f).apply { marginStart = dp(10f) }
            )

            card.addView(headerRow)
            card.addView(
                previewFrame,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12f) }
            )
            card.addView(peerTv)
            card.addView(primaryBtnRow)
            card.addView(
                secondaryBtnRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) }
            )

            val wrapper = LinearLayout(appCtx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), 0, dp(16f), 0)
                addView(
                    card,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            wrapper.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    DebugLogger.log("Overlay", "TYPE_APPLICATION_OVERLAY view attached to window")
                }

                override fun onViewDetachedFromWindow(v: View) {
                    if (activeWindowToken == myToken && FloatingShareOverlayCoordinator.uiState.value.isActive) {
                        DebugLogger.log("Overlay", "OS detached TYPE_APPLICATION_OVERLAY while session active", "ERROR")
                        GeminiDiagnosticWatcher.onOverlayAttachVerified(
                            context = appCtx,
                            launchMode = "TYPE_APPLICATION_OVERLAY_SHARE",
                            attached = false,
                            hasVisibleBounds = false,
                            detail = "Window detached unexpectedly by OEM/WindowManager",
                            bleState = NameDropApp.bleManager.state.value
                        )
                    }
                }
            })

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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(28f)
            }

            if (activeWindowToken != myToken) {
                throw IllegalStateException("Superseded by newer overlay request")
            }

            wm.addView(wrapper, params)
            rootContainer = wrapper

            stateObserverJob?.cancel()
            stateObserverJob = scope.launch {
                FloatingShareOverlayCoordinator.uiState.collect { st ->
                    if (activeWindowToken != myToken) return@collect
                    if (!st.isActive && rootContainer === wrapper) {
                        dismiss(appCtx, expectedToken = myToken)
                        return@collect
                    }
                    titleTv.text = st.title
                    statusTv.text = st.statusMessage
                    statusTv.setTextColor(
                        when {
                            st.isSharedComplete || st.isReceiveCompleted -> Color.parseColor("#34C759")
                            st.isError -> Color.parseColor("#FF3B30")
                            else -> Color.parseColor("#0A84FF")
                        }
                    )
                    peerTv.text = st.peerLine
                    ringView.updateProgress(
                        progress = st.progress,
                        isSharing = st.isSharing || st.isArmed || st.isReceiveOnlyWaiting,
                        isDone = st.isSharedComplete || st.isReceiveCompleted
                    )
                    if (st.previewBitmap != null) {
                        previewIv.setImageBitmap(st.previewBitmap)
                        previewFrame.visibility = View.VISIBLE
                    } else {
                        previewFrame.visibility = View.GONE
                    }

                    receiveOnlyBtn.text = FloatingShareOverlayCoordinator.resolveReceiveActionLabel(st)
                    receiveOnlyBtn.isEnabled = !st.isSharing && !st.isSharedComplete
                    receiveOnlyBtn.background = GradientDrawable().apply {
                        setColor(
                            Color.parseColor(
                                when {
                                    st.isReceiveCompleted -> "#DCFCE7"
                                    st.isReceiveOnlyWaiting -> "#DBEAFE"
                                    else -> "#E8F2FF"
                                }
                            )
                        )
                        cornerRadius = dp(24f).toFloat()
                    }
                    receiveOnlyBtn.setTextColor(
                        Color.parseColor(if (st.isReceiveCompleted) "#15803D" else "#0A84FF")
                    )

                    shareNowBtn.text = FloatingShareOverlayCoordinator.resolveShareActionLabel(st)
                    shareNowBtn.background = GradientDrawable().apply {
                        setColor(
                            Color.parseColor(
                                when {
                                    st.isSharedComplete -> "#34C759"
                                    else -> "#0A84FF"
                                }
                            )
                        )
                        cornerRadius = dp(24f).toFloat()
                    }
                    shareNowBtn.isEnabled = !st.isSharing && !st.isSharedComplete && !st.isStaging

                    openAppBtn.visibility =
                        if (st.canOpenFullApp || st.isError || st.needsBluetoothSetup || st.isReceiveCompleted) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                }
            }
            DebugLogger.log("Overlay", "Mounted TYPE_APPLICATION_OVERLAY floating gallery share box")
        }
    }

    private fun openFullApp(appCtx: Context) {
        FloatingShareOverlayCoordinator.dismissSession(appCtx)
        val intent = Intent(appCtx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        appCtx.startActivity(intent)
    }

    fun dismiss(context: Context, expectedToken: Long? = null) {
        val appCtx = context.applicationContext
        removeContainerImmediate(appCtx, expectedToken = expectedToken)
    }

    private fun removeContainerImmediate(appCtx: Context, expectedToken: Long?) {
        val doRemove = {
            if (expectedToken == null || expectedToken == activeWindowToken) {
                stateObserverJob?.cancel()
                stateObserverJob = null
                rootContainer?.let { view ->
                    runCatching {
                        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        wm.removeViewImmediate(view)
                    }
                    rootContainer = null
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doRemove()
        } else {
            mainHandler.post { doRemove() }
        }
    }

    private class DropRingIndicatorView(context: Context) : View(context) {
        private var progress = 0f
        private var sharing = false
        private var done = false

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#180A84FF")
            style = Paint.Style.FILL
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#240A84FF")
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A84FF")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 8f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A84FF")
            textAlign = Paint.Align.CENTER
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val rect = RectF()

        fun updateProgress(progress: Float, isSharing: Boolean, isDone: Boolean) {
            this.progress = progress.coerceIn(0f, 1f)
            this.sharing = isSharing
            this.done = isDone
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = (minOf(width, height) / 2f) - 6f
            bgPaint.color = if (done) Color.parseColor("#2234C759") else Color.parseColor("#180A84FF")
            canvas.drawCircle(cx, cy, r, bgPaint)

            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawOval(rect, trackPaint)

            if (sharing || done) {
                arcPaint.color = if (done) Color.parseColor("#34C759") else Color.parseColor("#0A84FF")
                val sweep = if (done) 360f else (progress * 360f).coerceAtLeast(28f)
                canvas.drawArc(rect, -90f, sweep, false, arcPaint)
            }

            textPaint.color = if (done) Color.parseColor("#34C759") else Color.parseColor("#0A84FF")
            val symbol = if (done) "✓" else "✦"
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(symbol, cx, baseline, textPaint)
        }
    }
}
