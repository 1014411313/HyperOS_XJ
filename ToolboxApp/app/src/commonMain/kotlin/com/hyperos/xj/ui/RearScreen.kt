package com.hyperos.xj.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.hyperos.xj.runtime.DeviceStatus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun RearScreenPreview(
    accent: Color,
    status: DeviceStatus,
    fields: List<Int>,
    modifier: Modifier = Modifier,
    showNotch: Boolean = false,
    fontScale: Float = 1f,
    animate: Boolean = true
) {
    val metrics = fields.distinct().take(4).map { rearMetric(it, status) }
    BoxWithConstraints(
        modifier
            .aspectRatio(976f / 596f)
            .clip(RoundedRectangle(26.dp))
            .background(Color.Black)
    ) {
        fun px(design: Float): Dp = (design * maxWidth.value / 976f).dp
        val scale = maxWidth.value / 976f

        if (showNotch) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
                    .width(px(296f))
                    .background(Color.Black.copy(alpha = 0.86f))
            ) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(px(52f))
                        .clip(CircleShape)
                        .background(Color(0xFF151517))
                        .border(px(1.5f), Color.White.copy(alpha = 0.14f), CircleShape)
                )
            }
        }

        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(px(680f))
                .fillMaxHeight()
        ) {
            val slots = listOf(
                RearSlot(Alignment.TopStart, startAligned = true, hairlineEnd = true, hairlineBottom = true),
                RearSlot(Alignment.TopEnd, startAligned = false, hairlineEnd = false, hairlineBottom = true),
                RearSlot(Alignment.BottomStart, startAligned = true, hairlineEnd = true, hairlineBottom = false),
                RearSlot(Alignment.BottomEnd, startAligned = false, hairlineEnd = false, hairlineBottom = false)
            )
            slots.forEachIndexed { index, slot ->
                RearPanel(
                    metric = metrics.getOrNull(index),
                    accent = accent,
                    modifier = Modifier.size(px(340f), px(298f)).align(slot.alignment),
                    startAligned = slot.startAligned,
                    hairlineEnd = slot.hairlineEnd,
                    hairlineBottom = slot.hairlineBottom,
                    scale = scale,
                    fontScale = fontScale
                )
            }

            RearStatusPill(
                text = if (status.charging) "充电中" else "放电中",
                accent = accent,
                charging = status.charging,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = px(32f)),
                scale = scale,
                fontScale = fontScale
            )

            LiquidGauge(
                progress = status.soc.coerceIn(0, 100) / 100f,
                powerText = fmtRear1(if (status.charging) status.powerW else -status.powerW),
                charging = status.charging,
                accent = accent,
                modifier = Modifier.align(Alignment.Center).size(px(440f)),
                gaugeSize = px(440f),
                animate = animate
            )
        }
    }
}

private data class RearSlot(
    val alignment: Alignment,
    val startAligned: Boolean,
    val hairlineEnd: Boolean,
    val hairlineBottom: Boolean
)

private data class RearMetric(
    val label: String,
    val value: String,
    val unit: String,
    val icon: RearIconKind
)

private fun rearMetric(index: Int, status: DeviceStatus): RearMetric = when (index) {
    0 -> RearMetric("温度", fmtRear1(status.tempC), "℃", RearIconKind.Thermometer)
    1 -> RearMetric("电量", status.soc.toString(), "%", RearIconKind.Battery)
    2 -> RearMetric("电压", fmtRear3(status.voltageMv / 1000f), "V", RearIconKind.Bolt)
    3 -> RearMetric("电流", fmtRear2(status.currentMa / 1000f), "A", RearIconKind.Sine)
    4 -> RearMetric("功率", fmtRear1(status.powerW), "W", RearIconKind.Bolt)
    5 -> RearMetric("状态", status.status, "", RearIconKind.Battery)
    6 -> RearMetric("设计容量", status.designMah.toString(), "mAh", RearIconKind.Battery)
    7 -> RearMetric("满充容量", status.fullMah.toString(), "mAh", RearIconKind.Battery)
    8 -> RearMetric("健康度", status.health.toString(), "%", RearIconKind.Battery)
    9 -> RearMetric("循环", status.cycles.toString(), "次", RearIconKind.Sine)
    10 -> RearMetric("伪装", status.fakeCycles.toString(), "次", RearIconKind.Sine)
    11 -> RearMetric("协议", status.protocol, "", RearIconKind.Bolt)
    12 -> RearMetric("剩余容量", status.remainingMah.toString(), "mAh", RearIconKind.Battery)
    13 -> RearMetric("电池寿命", status.soh.toString(), "%", RearIconKind.Battery)
    else -> RearMetric("温度", fmtRear1(status.tempC), "℃", RearIconKind.Thermometer)
}

private enum class RearIconKind { Thermometer, Battery, Bolt, Sine }

@Composable
private fun RearPanel(
    metric: RearMetric?,
    accent: Color,
    modifier: Modifier,
    startAligned: Boolean,
    hairlineEnd: Boolean,
    hairlineBottom: Boolean,
    scale: Float,
    fontScale: Float
) {
    val hairline = Color.White.copy(alpha = 0.06f)
    Box(modifier.background(Color.Black)) {
        if (hairlineEnd) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(hairline)
            )
        }
        if (hairlineBottom) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(hairline)
            )
        }

        if (metric == null) return@Box

        val innerAlign = when {
            startAligned && hairlineBottom -> Alignment.TopStart
            startAligned -> Alignment.BottomStart
            hairlineBottom -> Alignment.TopEnd
            else -> Alignment.BottomEnd
        }
        Column(
            Modifier
                .align(innerAlign)
                .padding(horizontal = (44f * scale).dp, vertical = (36f * scale).dp),
            horizontalAlignment = if (startAligned) Alignment.Start else Alignment.End
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RearIcon(metric.icon, accent, (22f * scale).dp)
                Spacer(Modifier.width((10f * scale).dp))
                BasicText(
                    text = metric.label,
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = (17f * scale * fontScale).sp,
                        letterSpacing = (0.15f * 17f * scale * fontScale).sp
                    )
                )
            }
            Spacer(Modifier.height((6f * scale).dp))
            Row(verticalAlignment = Alignment.Bottom) {
                val valueFont = if (metric.unit.isEmpty()) 30f else 46f
                BasicText(
                    text = metric.value,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = (valueFont * scale * fontScale).sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.03f * valueFont * scale * fontScale).sp
                    )
                )
                if (metric.unit.isNotEmpty()) {
                    Spacer(Modifier.width((5f * scale).dp))
                    BasicText(
                        text = metric.unit,
                        modifier = Modifier.padding(bottom = (3f * scale).dp),
                        style = TextStyle(color = Color.White.copy(alpha = 0.35f), fontSize = (20f * scale * fontScale).sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RearIcon(kind: RearIconKind, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val stroke = 1.8f * s
        when (kind) {
            RearIconKind.Thermometer -> {
                drawLine(
                    color = color,
                    start = Offset(12f * s, 3.2f * s),
                    end = Offset(12f * s, 11f * s),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawOval(
                    color = color,
                    topLeft = Offset(8.5f * s, 10.5f * s),
                    size = Size(7f * s, 10f * s),
                    style = Stroke(width = stroke)
                )
            }
            RearIconKind.Battery -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(2.4f * s, 6.6f * s),
                    size = Size(16.2f * s, 10.8f * s),
                    cornerRadius = CornerRadius(3f * s, 3f * s),
                    style = Stroke(width = stroke)
                )
                drawLine(
                    color = color,
                    start = Offset(21.4f * s, 10.2f * s),
                    end = Offset(21.4f * s, 13.8f * s),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(6.2f * s, 10.4f * s),
                    end = Offset(6.2f * s, 13.6f * s),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(9.6f * s, 10.4f * s),
                    end = Offset(9.6f * s, 13.6f * s),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            RearIconKind.Bolt -> {
                val p = Path().apply {
                    moveTo(13f * s, 2f * s)
                    lineTo(4.5f * s, 13.5f * s)
                    lineTo(11f * s, 13.5f * s)
                    lineTo(10f * s, 22f * s)
                    lineTo(18.5f * s, 10.5f * s)
                    lineTo(12f * s, 10.5f * s)
                    close()
                }
                drawPath(p, color)
            }
            RearIconKind.Sine -> {
                val p = Path()
                val w = this.size.width
                val h = this.size.height
                p.moveTo(2.5f * s, h / 2f)
                var x = 2.5f * s
                val step = w / 40f
                while (x <= w - 2.5f * s) {
                    val t = (x / w) * (2f * PI).toFloat()
                    p.lineTo(x, h / 2f - sin(t) * 6f * s)
                    x += step
                }
                drawPath(p, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun RearStatusPill(
    text: String,
    accent: Color,
    charging: Boolean,
    modifier: Modifier,
    scale: Float,
    fontScale: Float
) {
    val transition = rememberInfiniteTransition(label = "pill")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pillPulse"
    )
    val borderColor = if (charging) accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.16f)
    val textColor = if (charging) accent else Color.White.copy(alpha = 0.5f)
    val dotColor = if (charging) accent else Color.White.copy(alpha = 0.5f)

    Row(
        modifier
            .height((38f * scale).dp)
            .clip(Capsule())
            .background(Color.Black)
            .border((1f * scale).dp, borderColor, Capsule())
            .padding(horizontal = (20f * scale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size((8f * scale).dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = if (charging) pulse else 0.7f))
        )
        Spacer(Modifier.width((8f * scale).dp))
        BasicText(
            text = text,
            style = TextStyle(
                color = textColor,
                fontSize = (16f * scale * fontScale).sp,
                letterSpacing = (0.12f * 16f * scale * fontScale).sp
            )
        )
    }
}

private fun fmtRear1(value: Float): String {
    val scaled = (value * 10f).toInt()
    return "${scaled / 10}.${abs(scaled % 10)}"
}

private fun fmtRear2(value: Float): String {
    val scaled = (value * 100f).toInt()
    return "${scaled / 100}.${pad2(abs(scaled % 100))}"
}

private fun fmtRear3(value: Float): String {
    val scaled = (value * 1000f).toInt()
    return "${scaled / 1000}.${pad3(abs(scaled % 1000))}"
}

private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()

private fun pad3(value: Int): String = when {
    value < 10 -> "00$value"
    value < 100 -> "0$value"
    else -> value.toString()
}
