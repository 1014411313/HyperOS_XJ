package com.hyperos.xj.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LiquidGauge(
    progress: Float,
    powerText: String,
    charging: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    gaugeSize: Dp = 176.dp,
    unitText: String = "W",
    animate: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "liquidGauge")
    val glowScale = if (animate) {
        transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse),
            label = "glowBreath"
        ).value
    } else {
        1f
    }
    val sparkPulse = if (animate) {
        transition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Reverse),
            label = "sparkPulse"
        ).value
    } else {
        1f
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (animate) {
            tween(800, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f))
        } else {
            tween(0)
        },
        label = "fillHeight"
    )

    val designUnit = (gaugeSize / 176f).value
    val powerSize = (when {
        powerText.length >= 6 -> 30f
        powerText.length >= 5 -> 36f
        else -> 48f
    } * designUnit).sp
    val unitSize = (14f * designUnit).sp
    val labelSize = (11f * designUnit).sp

    Box(modifier.size(gaugeSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val ringR = minOf(w, h) * 0.42f
            val stroke = minOf(w, h) * 0.045f

            if (charging) {
                val gr = ringR * 1.42f * glowScale
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.42f),
                            accent.copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = gr
                    ),
                    radius = gr,
                    center = Offset(cx, cy)
                )
            }

            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = ringR,
                center = Offset(cx, cy),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            val sweep = 360f * animatedProgress.coerceIn(0f, 1f)
            if (sweep > 1f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        // 首尾同色：sweepGradient 的 0 位在 3 点钟方向，正好落在弧的 25% 处，
                        // 首尾不同色会在这里露出一条径向接缝（看起来像横线）。
                        0f to accent,
                        0.5f to Color.White.copy(alpha = 0.85f),
                        1f to accent,
                        center = Offset(cx, cy)
                    ),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(cx - ringR, cy - ringR),
                    size = androidx.compose.ui.geometry.Size(ringR * 2f, ringR * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                val endRad = ((-90f + sweep) * PI / 180.0).toFloat()
                val tip = Offset(cx + cos(endRad) * ringR, cy + sin(endRad) * ringR)
                drawCircle(
                    color = accent.copy(alpha = 0.9f),
                    radius = stroke * 0.95f * sparkPulse,
                    center = tip
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = stroke * 0.5f * sparkPulse,
                    center = tip
                )
            }
        }

        Column(
            Modifier.width(gaugeSize * 0.64f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                text = powerText,
                maxLines = 1,
                style = TextStyle(
                    color = Color.White,
                    fontSize = powerSize,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.sp,
                    textAlign = TextAlign.Center
                )
            )
            BasicText(
                text = unitText,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = unitSize,
                    letterSpacing = (0.08f * designUnit * 14f).sp
                )
            )
            Spacer(Modifier.height((5f * designUnit).dp))
            BasicText(
                text = "电量 ${(animatedProgress * 100f).toInt()}%",
                style = TextStyle(
                    color = accent.copy(alpha = 0.85f),
                    fontSize = labelSize,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}
