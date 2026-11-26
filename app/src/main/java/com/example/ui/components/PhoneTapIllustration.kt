package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.ui.theme.IosBlue

@Composable
fun PhoneTapIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 112.dp, height = 78.dp)) {
        val phoneW = 28.dp.toPx()
        val phoneH = 48.dp.toPx()
        val r = CornerRadius(6.dp.toPx(), 6.dp.toPx())

        // Left tilted phone
        rotate(degrees = -14f, pivot = Offset(size.width * 0.38f, size.height * 0.62f)) {
            drawRoundRect(
                color = IosBlue.copy(alpha = 0.16f),
                topLeft = Offset(size.width * 0.25f, size.height * 0.28f),
                size = Size(phoneW, phoneH),
                cornerRadius = r
            )
            drawRoundRect(
                color = IosBlue,
                topLeft = Offset(size.width * 0.25f, size.height * 0.28f),
                size = Size(phoneW, phoneH),
                cornerRadius = r,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Right tilted phone
        rotate(degrees = 14f, pivot = Offset(size.width * 0.62f, size.height * 0.62f)) {
            drawRoundRect(
                color = Color(0xFFE5E5EA),
                topLeft = Offset(size.width * 0.50f, size.height * 0.28f),
                size = Size(phoneW, phoneH),
                cornerRadius = r
            )
            drawRoundRect(
                color = Color(0xFF3A3A3C),
                topLeft = Offset(size.width * 0.50f, size.height * 0.28f),
                size = Size(phoneW, phoneH),
                cornerRadius = r,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // BLE proximity wave arcs above top edges
        val arcCenter = Offset(size.width / 2f, size.height * 0.22f)
        for (i in 1..3) {
            val radius = (7 * i).dp.toPx()
            drawArc(
                color = IosBlue.copy(alpha = 1f - i * 0.22f),
                startAngle = 215f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(arcCenter.x - radius, arcCenter.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}
