package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.DropBadgePhase
import com.example.model.DropBadgeState
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosGreen

/**
 * Animated Drop Pop-Down badge at top-center of screen:
 * - Drops down smoothly when a transfer starts with "Sharing" and a live circular ring forming around the drop.
 * - Animates circular ring to 100% completion and flips to "Shared" with a crisp checkmark beat.
 */
@Composable
fun DropTransferBadgeOverlay(
    state: DropBadgeState,
    modifier: Modifier = Modifier
) {
    val isVisible = state.phase != DropBadgePhase.HIDDEN
    val isShared = state.phase == DropBadgePhase.SHARED
    val animatedProgress by animateFloatAsState(
        targetValue = if (isShared) 1f else state.progress.coerceIn(0.06f, 0.96f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dropRingProgress"
    )
    val ringColor = if (isShared) IosGreen else IosBlue

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(
                initialOffsetY = { -it * 2 },
                animationSpec = spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow)
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { -it * 2 }
            ) + fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = Color(0xF51C1C1E),
                modifier = Modifier
                    .shadow(18.dp, RoundedCornerShape(32.dp))
                    .testTag("drop_transfer_badge")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Drop icon inside circular ring progress indicator
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(44.dp)) {
                            val stroke = 3.5.dp.toPx()
                            drawCircle(
                                color = Color.White.copy(alpha = 0.16f),
                                style = Stroke(width = stroke)
                            )
                            drawArc(
                                color = ringColor,
                                startAngle = -90f,
                                sweepAngle = 360f * animatedProgress,
                                useCenter = false,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(ringColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isShared) Icons.Rounded.Check else Icons.Rounded.ArrowUpward,
                                contentDescription = state.title,
                                tint = ringColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = if (isShared) "Shared" else state.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (state.subtitle.isNotBlank()) {
                            Text(
                                text = state.subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.72f)
                            )
                        }
                    }
                }
            }
        }
    }
}
