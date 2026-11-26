package com.example.ui.components

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import com.example.audio.ShareSoundPlayer
import com.example.model.GlowThemeStyle
import com.example.ui.theme.IosBlue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class CutoutAnchor(
    val centerX: Float,
    val centerY: Float,
    val radius: Float
)

private data class SparkleParticle(
    val angleRad: Float,
    val distanceFactor: Float,
    val radiusDp: Float,
    val isMiniStar: Boolean
)

/**
 * Audio-clock-locked slow-motion Name Drop entry animation (~4.25s total),
 * anchored dynamically to the device's detected front camera cutout / bar:
 *
 * 1. Star Drops Down (0.00 - 0.14): Blue 4-point Gemini star drops from the camera cutout.
 * 2. Star Bursts (0.14 - 0.28): Star sparkles, shakes, and bursts with the Apple Pay chime.
 * 3. Star Goes Up (0.28 - 0.42): Star reforms back to life and glides up into the camera cutout.
 * 4. Original Full-Screen Blue Flash (0.40 - 0.56): Soft system-blue wash illuminates with the Gemini sound.
 * 5. Original Thick Perimeter Sweep (0.54 - 0.92): Thick 7.5dp trail splits from camera bar down both sides.
 * 6. Bottom Convergence & Card Pop-Up (0.92 - 1.00): Trails meet at bottom center and invoke [onSweepComplete].
 */
@Composable
fun PerimeterSweepOverlay(
    isVisible: Boolean,
    audioPositionProviderMs: () -> Long,
    glowTheme: GlowThemeStyle = GlowThemeStyle.NONE,
    onSweepComplete: () -> Unit
) {
    if (!isVisible) return

    val totalDurationMs = ShareSoundPlayer.CHIME_DURATION_MS.toFloat()
    var normalizedTime by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val density = LocalDensity.current

    var overlayOffsetInWindow by remember { mutableStateOf(Offset.Zero) }
    var detectedCutoutRect by remember { mutableStateOf<Rect?>(null) }

    val particles = remember {
        List(40) { idx ->
            val goldenAngle = idx * 2.399963f
            SparkleParticle(
                angleRad = goldenAngle,
                distanceFactor = 0.28f + ((idx % 7) / 6f) * 0.72f,
                radiusDp = 1.6f + (idx % 4) * 1.0f,
                isMiniStar = idx % 5 == 0
            )
        }
    }

    LaunchedEffect(view, isVisible) {
        val insetsCompat = ViewCompat.getRootWindowInsets(view)
        val cutout = insetsCompat?.displayCutout
        detectedCutoutRect = cutout?.boundingRects?.minByOrNull { it.top }
    }

    LaunchedEffect(isVisible) {
        normalizedTime = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        var maxSeenMs = 0L
        var burstHapticFired = false
        var flashHapticFired = false

        while (true) {
            withFrameNanos {
                val audioMs = audioPositionProviderMs().coerceAtLeast(maxSeenMs)
                maxSeenMs = audioMs
                normalizedTime = (audioMs / totalDurationMs).coerceIn(0f, 1f)

                if (!burstHapticFired && audioMs >= 820L) {
                    burstHapticFired = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                if (!flashHapticFired && audioMs >= 1850L) {
                    flashHapticFired = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            if (maxSeenMs >= totalDurationMs.toLong()) break
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onSweepComplete()
    }

    val t = normalizedTime
    val (primaryTone, deepTone, brightTone) = when (glowTheme) {
        GlowThemeStyle.RED_EDGE -> Triple(Color(0xFFFF3B30), Color(0xFFD92D20), Color(0xFFFF8A80))
        GlowThemeStyle.GREEN_GLOW -> Triple(Color(0xFF34C759), Color(0xFF1B8E3E), Color(0xFF86EFAC))
        GlowThemeStyle.PURPLE_SURROUNDED -> Triple(Color(0xFFAF52DE), Color(0xFF7B2CBF), Color(0xFFD8B4FE))
        GlowThemeStyle.NONE -> Triple(IosBlue, Color(0xFF0055D4), Color(0xFF64B5FF))
    }

    val starPrimary = IosBlue
    val starDeep = Color(0xFF0055D4)
    val starBright = Color(0xFF64B5FF)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                overlayOffsetInWindow = coords.positionInWindow()
            }
            .background(Color.Black.copy(alpha = 0.84f))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val strokeCore = 7.5.dp.toPx()
            val strokeDiffusion = 18.dp.toPx()
            val edgeInset = strokeCore / 2f + 2.dp.toPx()
            val cornerRadius = 34.dp.toPx().coerceAtMost(w / 4f)

            val cutoutAnchor = resolveCameraCutoutAnchor(
                rect = detectedCutoutRect,
                overlayWindowOffset = overlayOffsetInWindow,
                canvasWidth = w,
                fallbackTopY = with(density) { 22.dp.toPx() },
                defaultRadius = with(density) { 14.dp.toPx() }
            )
            val cameraPos = Offset(cutoutAnchor.centerX, cutoutAnchor.centerY)
            val centerStagePos = Offset(w * 0.5f, h * 0.42f)

            // ── PART 1: Star Comes Down (0.00 -> 0.14) ────────────────────────────────
            if (t < 0.14f) {
                val p = easeInOutCubic(t / 0.14f)
                val starPos = Offset(
                    x = cameraPos.x + (centerStagePos.x - cameraPos.x) * p,
                    y = cameraPos.y + (centerStagePos.y - cameraPos.y) * p
                )
                drawLine(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            starBright.copy(alpha = 0.48f * (1f - p * 0.35f)),
                            starPrimary.copy(alpha = 0.28f),
                            Color.Transparent
                        ),
                        startY = cameraPos.y,
                        endY = starPos.y
                    ),
                    start = cameraPos,
                    end = starPos,
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawBlueGeminiSparkle(
                    center = starPos,
                    outerRadius = 10.dp.toPx() + 24.dp.toPx() * p,
                    deepBlue = starDeep,
                    primaryBlue = starPrimary,
                    brightBlue = starBright
                )
            }

            // ── PART 2: Star Sparkles, Shakes & Bursts (0.14 -> 0.28) ─────────────────
            if (t in 0.14f..0.28f) {
                val s2 = ((t - 0.14f) / 0.14f).coerceIn(0f, 1f)
                if (s2 < 0.42f) {
                    val shakeProg = s2 / 0.42f
                    val amp = 7.dp.toPx() * shakeProg
                    val shakeX = sin(shakeProg * 36f) * amp
                    val shakeY = cos(shakeProg * 28f) * (amp * 0.6f)
                    val shakenCenter = centerStagePos + Offset(shakeX, shakeY)

                    particles.take(16).forEach { pt ->
                        val orbitR = (36.dp.toPx() + 16.dp.toPx() * shakeProg) * pt.distanceFactor
                        val px = shakenCenter.x + cos(pt.angleRad + shakeProg * 4f) * orbitR
                        val py = shakenCenter.y + sin(pt.angleRad + shakeProg * 4f) * orbitR
                        drawCircle(
                            color = starBright.copy(alpha = (0.85f * shakeProg).coerceIn(0f, 1f)),
                            radius = pt.radiusDp.dp.toPx() * 0.7f,
                            center = Offset(px, py)
                        )
                    }

                    drawBlueGeminiSparkle(
                        center = shakenCenter,
                        outerRadius = 34.dp.toPx() * (1f + 0.15f * sin(shakeProg * 18f)),
                        deepBlue = starDeep,
                        primaryBlue = starPrimary,
                        brightBlue = starBright
                    )
                } else {
                    val burstProg = easeOutCubic((s2 - 0.42f) / 0.58f)
                    val coreFade = (1f - burstProg * 1.45f).coerceIn(0f, 1f)
                    if (coreFade > 0.02f) {
                        drawBlueGeminiSparkle(
                            center = centerStagePos,
                            outerRadius = 36.dp.toPx() * (1f + 0.35f * burstProg),
                            deepBlue = starDeep.copy(alpha = coreFade),
                            primaryBlue = starPrimary.copy(alpha = coreFade),
                            brightBlue = starBright.copy(alpha = coreFade)
                        )
                    }

                    val maxBlast = 142.dp.toPx()
                    particles.forEach { pt ->
                        val dist = maxBlast * pt.distanceFactor * burstProg
                        val pos = Offset(
                            x = centerStagePos.x + cos(pt.angleRad) * dist,
                            y = centerStagePos.y + sin(pt.angleRad) * dist
                        )
                        val pAlpha = (1f - burstProg * 0.25f).coerceIn(0f, 1f)
                        if (pt.isMiniStar) {
                            drawBlueGeminiSparkle(
                                center = pos,
                                outerRadius = 7.dp.toPx() * (1f - 0.3f * burstProg),
                                deepBlue = starDeep.copy(alpha = pAlpha),
                                primaryBlue = starPrimary.copy(alpha = pAlpha),
                                brightBlue = starBright.copy(alpha = pAlpha)
                            )
                        } else {
                            drawCircle(
                                color = starBright.copy(alpha = pAlpha * 0.9f),
                                radius = pt.radiusDp.dp.toPx(),
                                center = pos
                            )
                        }
                    }
                }
            }

            // ── PART 3: Star Reforms & Goes Up Into Camera Bar (0.28 -> 0.42) ─────────
            if (t in 0.28f..0.42f) {
                val s3 = ((t - 0.28f) / 0.14f).coerceIn(0f, 1f)
                if (s3 < 0.34f) {
                    val coalesce = 1f - easeInOutCubic(s3 / 0.34f)
                    val maxBlast = 142.dp.toPx()
                    particles.forEach { pt ->
                        val dist = maxBlast * pt.distanceFactor * coalesce
                        val pos = Offset(
                            x = centerStagePos.x + cos(pt.angleRad) * dist,
                            y = centerStagePos.y + sin(pt.angleRad) * dist
                        )
                        drawCircle(
                            color = starBright.copy(alpha = 0.85f * coalesce + 0.15f),
                            radius = pt.radiusDp.dp.toPx(),
                            center = pos
                        )
                    }
                    val reformAlpha = easeInOutCubic(s3 / 0.34f)
                    drawBlueGeminiSparkle(
                        center = centerStagePos,
                        outerRadius = 32.dp.toPx() * (0.4f + 0.6f * reformAlpha),
                        deepBlue = starDeep.copy(alpha = reformAlpha),
                        primaryBlue = starPrimary.copy(alpha = reformAlpha),
                        brightBlue = starBright.copy(alpha = reformAlpha)
                    )
                } else {
                    val rise = easeInOutCubic((s3 - 0.34f) / 0.66f)
                    val starPos = Offset(
                        x = centerStagePos.x + (cameraPos.x - centerStagePos.x) * rise,
                        y = centerStagePos.y + (cameraPos.y - centerStagePos.y) * rise
                    )
                    drawLine(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                starBright.copy(alpha = 0.72f),
                                starPrimary.copy(alpha = 0.35f),
                                Color.Transparent
                            ),
                            startY = cameraPos.y,
                            endY = (starPos.y + 84.dp.toPx()).coerceAtMost(h * 0.6f)
                        ),
                        start = cameraPos,
                        end = starPos,
                        strokeWidth = 8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    val shrinkFade = if (rise > 0.85f) 1f - ((rise - 0.85f) / 0.15f) else 1f
                    drawBlueGeminiSparkle(
                        center = starPos,
                        outerRadius = (32.dp.toPx() * (1f - 0.7f * rise)).coerceAtLeast(8.dp.toPx()),
                        deepBlue = starDeep.copy(alpha = shrinkFade),
                        primaryBlue = starPrimary.copy(alpha = shrinkFade),
                        brightBlue = starBright.copy(alpha = shrinkFade)
                    )
                    if (rise > 0.72f) {
                        drawCircle(
                            color = starBright.copy(alpha = 0.75f * ((rise - 0.72f) / 0.28f)),
                            radius = cutoutAnchor.radius + 6.dp.toPx(),
                            center = cameraPos,
                            style = Stroke(width = 4.dp.toPx())
                        )
                    }
                }
            }

            // ── PART 4 (OLD ANIMATION): Soft Full-Screen Blue Flash (0.40 -> 0.56) ────
            if (t in 0.40f..0.56f) {
                val f = ((t - 0.40f) / 0.16f).coerceIn(0f, 1f)
                val flashAlpha = sin(f * PI.toFloat()).coerceIn(0f, 1f) * 0.55f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            brightTone.copy(alpha = flashAlpha * 0.9f),
                            primaryTone.copy(alpha = flashAlpha * 0.75f),
                            deepTone.copy(alpha = flashAlpha * 0.4f)
                        ),
                        center = Offset(w * 0.5f, h * 0.38f),
                        radius = maxOf(w, h) * 0.85f
                    )
                )
            }

            // ── PART 5 (OLD ANIMATION): Thick Perimeter Sweep From Camera Bar (0.54 -> 1.00) ─
            if (t >= 0.54f) {
                val sweepRaw = ((t - 0.54f) / 0.38f).coerceIn(0f, 1f)
                val sweepProgress = easeInOutCubic(sweepRaw)

                val topEdgeY = edgeInset
                val startX = cutoutAnchor.centerX.coerceIn(edgeInset + cornerRadius, w - edgeInset - cornerRadius)
                val bottomCenter = Offset(w * 0.5f, h - edgeInset)

                val leftPath = Path().apply {
                    moveTo(startX, topEdgeY)
                    lineTo(edgeInset + cornerRadius, topEdgeY)
                    quadraticTo(edgeInset, topEdgeY, edgeInset, topEdgeY + cornerRadius)
                    lineTo(edgeInset, h - edgeInset - cornerRadius)
                    quadraticTo(edgeInset, h - edgeInset, edgeInset + cornerRadius, h - edgeInset)
                    lineTo(bottomCenter.x, bottomCenter.y)
                }

                val rightPath = Path().apply {
                    moveTo(startX, topEdgeY)
                    lineTo(w - edgeInset - cornerRadius, topEdgeY)
                    quadraticTo(w - edgeInset, topEdgeY, w - edgeInset, topEdgeY + cornerRadius)
                    lineTo(w - edgeInset, h - edgeInset - cornerRadius)
                    quadraticTo(w - edgeInset, h - edgeInset, w - edgeInset - cornerRadius, h - edgeInset)
                    lineTo(bottomCenter.x, bottomCenter.y)
                }

                val leftMeasure = PathMeasure().apply { setPath(leftPath, false) }
                val rightMeasure = PathMeasure().apply { setPath(rightPath, false) }

                val leftSeg = Path()
                leftMeasure.getSegment(0f, leftMeasure.length * sweepProgress, leftSeg, true)

                val rightSeg = Path()
                rightMeasure.getSegment(0f, rightMeasure.length * sweepProgress, rightSeg, true)

                // Soft clean outer edge diffusion (zero neon)
                drawPath(
                    path = leftSeg,
                    color = primaryTone.copy(alpha = 0.28f),
                    style = Stroke(width = strokeDiffusion, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = rightSeg,
                    color = primaryTone.copy(alpha = 0.28f),
                    style = Stroke(width = strokeDiffusion, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                val trailBrush = Brush.verticalGradient(colors = listOf(brightTone, primaryTone, deepTone))
                drawPath(
                    path = leftSeg,
                    brush = trailBrush,
                    style = Stroke(width = strokeCore, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = rightSeg,
                    brush = trailBrush,
                    style = Stroke(width = strokeCore, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Bottom-center convergence bloom when both trails meet (0.92 -> 1.00)
                if (t >= 0.92f) {
                    val s6 = ((t - 0.92f) / 0.08f).coerceIn(0f, 1f)
                    val meetAlpha = easeInOutCubic(s6)
                    drawOval(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                brightTone.copy(alpha = 0.82f * meetAlpha),
                                primaryTone.copy(alpha = 0.36f * meetAlpha),
                                Color.Transparent
                            ),
                            center = bottomCenter,
                            radius = 78.dp.toPx()
                        ),
                        topLeft = Offset(bottomCenter.x - 78.dp.toPx(), bottomCenter.y - 28.dp.toPx()),
                        size = Size(156.dp.toPx(), 56.dp.toPx())
                    )
                }
            }
        }
    }
}

private fun resolveCameraCutoutAnchor(
    rect: Rect?,
    overlayWindowOffset: Offset,
    canvasWidth: Float,
    fallbackTopY: Float,
    defaultRadius: Float
): CutoutAnchor {
    if (rect == null || rect.isEmpty) {
        return CutoutAnchor(
            centerX = canvasWidth * 0.5f,
            centerY = fallbackTopY,
            radius = defaultRadius
        )
    }
    val localX = (rect.exactCenterX() - overlayWindowOffset.x).coerceIn(24f, canvasWidth - 24f)
    val localY = (rect.exactCenterY() - overlayWindowOffset.y).coerceAtLeast(fallbackTopY * 0.65f)
    val halfSpan = (minOf(rect.width(), rect.height()) / 2f).coerceIn(defaultRadius * 0.6f, defaultRadius * 1.8f)
    return CutoutAnchor(
        centerX = localX,
        centerY = localY,
        radius = halfSpan
    )
}

private fun DrawScope.drawBlueGeminiSparkle(
    center: Offset,
    outerRadius: Float,
    deepBlue: Color,
    primaryBlue: Color,
    brightBlue: Color
) {
    val innerOffset = outerRadius * 0.24f
    val sparklePath = Path().apply {
        moveTo(center.x, center.y - outerRadius)
        quadraticTo(center.x + innerOffset, center.y - innerOffset, center.x + outerRadius, center.y)
        quadraticTo(center.x + innerOffset, center.y + innerOffset, center.x, center.y + outerRadius)
        quadraticTo(center.x - innerOffset, center.y + innerOffset, center.x - outerRadius, center.y)
        quadraticTo(center.x - innerOffset, center.y - innerOffset, center.x, center.y - outerRadius)
        close()
    }

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(primaryBlue.copy(alpha = 0.35f), Color.Transparent),
            center = center,
            radius = outerRadius * 1.45f
        ),
        radius = outerRadius * 1.45f,
        center = center
    )

    drawPath(
        path = sparklePath,
        brush = Brush.linearGradient(
            colors = listOf(brightBlue, primaryBlue, deepBlue),
            start = Offset(center.x - outerRadius, center.y - outerRadius),
            end = Offset(center.x + outerRadius, center.y + outerRadius)
        )
    )
}

private fun easeInOutCubic(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return if (t < 0.5f) {
        4f * t * t * t
    } else {
        1f - ((-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f)) / 2f
    }
}

private fun easeOutCubic(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    val inv = 1f - t
    return 1f - inv * inv * inv
}
