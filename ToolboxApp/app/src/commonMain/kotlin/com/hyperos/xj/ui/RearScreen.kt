package com.hyperos.xj.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.hyperos.xj.runtime.DeviceStatus
import kotlin.math.abs

/** 与 LookPage.rearFieldIds 同一套字段，顺序必须一致；fields 参数传的是这里的索引。 */
private val rearFieldIds = listOf("temp", "soc", "volt", "cur", "power", "status", "design", "full", "health", "cycle", "fake", "proto", "rm", "soh")

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
    val level by animateFloatAsState(
        targetValue = status.soc.coerceIn(0, 100) / 100f,
        animationSpec = if (animate) tween(800) else tween(0),
        label = "rearLiquidLevel"
    )

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
            Canvas(Modifier.matchParentSize()) {
                val u = size.width / 680f
                drawLine(
                    color = Color.White.copy(alpha = 0.078f),
                    start = Offset(0f, 24f * u),
                    end = Offset(0f, size.height - 24f * u),
                    strokeWidth = maxOf(1f, u)
                )

                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = minOf(size.width, size.height) * 0.33f
                val stroke = maxOf(6f, 10f * u) * u

                drawCircle(
                    color = Color.White.copy(alpha = 0.2f),
                    radius = radius,
                    center = Offset(cx, cy),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                val liquidTop = cy + radius - 2f * radius * level
                val circle = Path().apply {
                    addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
                }
                clipPath(circle) {
                    drawRect(
                        color = accent.copy(alpha = 0.18f),
                        topLeft = Offset(cx - radius, liquidTop),
                        size = Size(radius * 2f, cy + radius - liquidTop)
                    )
                }

                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * level,
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            BasicText(
                text = "实时功率",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = px(62f)),
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = (18f * scale).sp,
                    lineHeight = (18f * scale).sp,
                    fontWeight = FontWeight.Medium
                )
            )

            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = px(250f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText(
                    text = fmtRear1(if (status.charging) status.powerW else -status.powerW),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = (88f * scale * fontScale).sp,
                        lineHeight = (88f * scale * fontScale).sp,
                        fontWeight = FontWeight.Light
                    )
                )
                BasicText(
                    text = "W",
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = (26f * scale).sp,
                        lineHeight = (26f * scale).sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            RearStatusPill(
                text = status.status,
                accent = accent,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = px(23f)),
                scale = scale
            )

            rearField(fields.getOrNull(0), status)?.let {
                RearCornerField(
                    it,
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = px(28f), top = px(104f)),
                    startAligned = true,
                    scale = scale,
                    fontScale = fontScale
                )
            }
            rearField(fields.getOrNull(1), status)?.let {
                RearCornerField(
                    it,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = px(28f), top = px(104f)),
                    startAligned = false,
                    scale = scale,
                    fontScale = fontScale
                )
            }
            rearField(fields.getOrNull(2), status)?.let {
                RearCornerField(
                    it,
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = px(28f), bottom = px(62f)),
                    startAligned = true,
                    scale = scale,
                    fontScale = fontScale
                )
            }
            rearField(fields.getOrNull(3), status)?.let {
                RearCornerField(
                    it,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = px(28f), bottom = px(62f)),
                    startAligned = false,
                    scale = scale,
                    fontScale = fontScale
                )
            }
        }
    }
}

private data class RearField(val label: String, val value: String)

private fun rearField(index: Int?, status: DeviceStatus): RearField? {
    val id = rearFieldIds.getOrNull(index ?: return null) ?: return null
    return when (id) {
        "temp" -> RearField("温度", fmtRear1(status.tempC) + "℃")
        "soc" -> RearField("电量", status.soc.toString() + "%")
        "volt" -> RearField("电压", fmtRear2(status.voltageMv / 1000f) + "V")
        "cur" -> RearField("电流", fmtRear2(abs(status.currentMa) / 1000f) + "A")
        "power" -> RearField("功率", fmtRear1(abs(status.powerW)) + "W")
        "status" -> RearField("状态", status.status)
        "design" -> RearField("设计容量", if (status.designMah > 0) "${status.designMah}mAh" else "--")
        "full" -> RearField("满充容量", if (status.fullMah > 0) "${status.fullMah}mAh" else "--")
        "health" -> RearField("健康度", if (status.health > 0) "${status.health}%" else "--")
        "cycle" -> RearField("循环", if (status.cycles > 0) "${status.cycles}次" else "--")
        "fake" -> RearField("伪装", "${status.fakeCycles}次")
        "proto" -> RearField("协议", status.protocol.ifEmpty { "--" })
        "rm" -> RearField("剩余容量", if (status.remainingMah > 0) "${status.remainingMah}mAh" else "--")
        "soh" -> RearField("电池寿命", if (status.soh > 0) "${status.soh}%" else "--")
        else -> null
    }
}

@Composable
private fun RearCornerField(
    field: RearField,
    modifier: Modifier,
    startAligned: Boolean,
    scale: Float,
    fontScale: Float
) {
    Column(
        modifier,
        horizontalAlignment = if (startAligned) Alignment.Start else Alignment.End
    ) {
        BasicText(
            text = field.label,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.55f),
                fontSize = (17f * scale).sp,
                lineHeight = (17f * scale).sp,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(Modifier.height((8f * scale).dp))
        BasicText(
            text = field.value,
            style = TextStyle(
                color = Color.White,
                fontSize = (42f * scale * fontScale).sp,
                lineHeight = (42f * scale * fontScale).sp,
                fontWeight = FontWeight.Light
            )
        )
    }
}

@Composable
private fun RearStatusPill(
    text: String,
    accent: Color,
    modifier: Modifier,
    scale: Float
) {
    Row(
        modifier
            .height((38f * scale).dp)
            .clip(Capsule())
            .background(Color.Black.copy(alpha = 0.94f))
            .border((1.5f * scale).dp, accent.copy(alpha = 0.7f), Capsule())
            .padding(horizontal = (22f * scale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = accent,
                fontSize = (18f * scale).sp,
                fontWeight = FontWeight.Medium
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

private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()
