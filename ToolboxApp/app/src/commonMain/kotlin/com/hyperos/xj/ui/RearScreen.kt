package com.hyperos.xj.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import com.hyperos.xj.runtime.DeviceStatus
import com.hyperos.xj.runtime.gifFrameAt
import kotlin.math.PI
import kotlin.math.sin

/** 与 LookPage.rearFieldIds 同一套字段，顺序必须一致；fields 参数传的是这里的索引。 */
private val rearFieldIds = listOf("temp", "soc", "volt", "cur", "power", "status", "design", "full", "health", "cycle", "fake", "proto", "rm", "soh")

/** 闪电多边形归一化坐标，与 PanelView.BOLT_NX/NY 保持一致。 */
private val boltNx = floatArrayOf(0.55f, 0.12f, 0.42f, 0.30f, 0.88f, 0.55f)
private val boltNy = floatArrayOf(0.00f, 0.55f, 0.55f, 1.00f, 0.42f, 0.42f)

/**
 * 背屏面板预览，与 PanelView 同构：液体按电量高度铺满面板、液面为流动 sin 波浪、液内有气泡、
 * 中央闪电 + 大号电量百分比、底部一行实时数据。
 */
@Composable
fun RearScreenPreview(
    accent: Color,
    status: DeviceStatus,
    fields: List<Int>,
    modifier: Modifier = Modifier,
    showNotch: Boolean = false,
    fontScale: Float = 1f,
    animate: Boolean = true,
    bgColor: String = "#000000",
    bgImage: ImageBitmap? = null,
    bgGifPath: String? = null
) {
    val bg = parseRearColor(bgColor)
    val level by animateFloatAsState(
        targetValue = status.soc.coerceIn(0, 100) / 100f,
        animationSpec = if (animate) tween(800) else tween(0),
        label = "rearLiquidLevel"
    )

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entrance by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(if (animate) 480 else 0),
        label = "rearEntrance"
    )

    val wave = rememberInfiniteTransition(label = "rearWave")
    val rawPhase by wave.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1333, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rearWavePhase"
    )
    // animate=false 时冻结波形（乘 0），避免预览里仍有流动。
    val phase = rawPhase * if (animate) 1f else 0f

    BoxWithConstraints(
        modifier
            .aspectRatio(976f / 596f)
            .clip(RoundedRectangle(26.dp))
            .background(bg)
    ) {
        val scale = maxWidth.value / 976f
        fun px(design: Float): Dp = (design * scale).dp

        // 背景图在液体、挖孔遮罩之下，必须是第一个子组件。
        if (bgGifPath != null) {
            val gifClock = rememberInfiniteTransition(label = "rearGif")
            val gifMs by gifClock.animateFloat(
                initialValue = 0f,
                targetValue = 60_000f,
                animationSpec = infiniteRepeatable(
                    animation = tween(60_000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rearGifMs"
            )
            val gifFrame = gifFrameAt(bgGifPath, (gifMs * if (animate) 1f else 0f).toLong())
            if (gifFrame != null) {
                Image(
                    bitmap = gifFrame,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
        } else if (bgImage != null) {
            Image(
                bitmap = bgImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }

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

        // 全幅液体：铺满整个面板宽度（含左侧挖孔区），与 PanelView 一致。
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val s = w / 976f
            val fillHeight = h * level
            if (fillHeight <= 0.5f) return@Canvas

            val amp = 8f * s
            val waveY = h - fillHeight
            val segs = 64
            val twoPi = (2 * PI).toFloat()

            fun waveAt(x: Float): Float = waveY + sin(x / w * 4f * PI.toFloat() + phase) * amp

            val liquid = Path().apply {
                moveTo(0f, waveAt(0f))
                for (i in 1..segs) {
                    val x = w * i / segs
                    lineTo(x, waveAt(x))
                }
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(liquid, Color(0xFF00E030).copy(alpha = 0.75f))

            clipPath(liquid) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                        startY = h - 48f * s,
                        endY = h
                    ),
                    topLeft = Offset(0f, h - 48f * s),
                    size = Size(w, 48f * s)
                )
            }

            val crest = Path().apply {
                moveTo(0f, waveAt(0f))
                for (i in 1..segs) {
                    val x = w * i / segs
                    lineTo(x, waveAt(x))
                }
            }
            val crestWidth = maxOf(3f * s, w * 0.005f)
            drawPath(
                crest,
                Color.White.copy(alpha = 0.35f),
                style = Stroke(width = crestWidth * 2.6f, cap = StrokeCap.Round)
            )
            drawPath(
                crest,
                Color.White,
                style = Stroke(width = crestWidth, cap = StrokeCap.Round)
            )

            if (fillHeight > 12f * s) {
                val bubbles = listOf(0.20f to 6f, 0.40f to 4f, 0.60f to 3f, 0.80f to 4f, 0.30f to 5f, 0.70f to 3f)
                bubbles.forEachIndexed { idx, (bx, br) ->
                    val t = (phase / twoPi + idx * 0.16f) % 1f
                    val cy = waveY + fillHeight * (1f - t)
                    val cx = bx * w
                    drawCircle(Color(0xFFEFFFFF).copy(alpha = 0.40f), radius = br * s, center = Offset(cx, cy))
                    drawCircle(
                        Color.White.copy(alpha = 0.62f),
                        radius = br * 0.36f * s,
                        center = Offset(cx - br * 0.3f * s, cy - br * 0.3f * s)
                    )
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(px(680f))
                .fillMaxHeight()
        ) {
            Row(
                Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        alpha = entrance
                        scaleX = 0.85f + 0.15f * entrance
                        scaleY = 0.85f + 0.15f * entrance
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status.charging) {
                    RearBolt(width = px(96f * 0.60f * fontScale), height = px(96f * 1.02f * fontScale))
                    Spacer(Modifier.width(px(96f * 0.16f * fontScale)))
                }
                BasicText(
                    text = "${status.soc}%",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = (96f * scale * fontScale).sp,
                        lineHeight = (96f * scale * fontScale).sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            val rowValues = fields.take(4).mapNotNull { rearField(it, status)?.value }
            if (rowValues.isNotEmpty()) {
                val rowStyle = TextStyle(
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = (24f * scale * fontScale).sp,
                    lineHeight = (24f * scale * fontScale).sp,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = px(44f)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowValues.forEachIndexed { index, value ->
                        if (index > 0) {
                            BasicText(
                                text = "|",
                                modifier = Modifier.padding(horizontal = px(24f * fontScale * 0.55f)),
                                style = rowStyle.copy(color = Color.White.copy(alpha = 0.65f))
                            )
                        }
                        BasicText(text = value, style = rowStyle)
                    }
                }
            }
        }
    }
}

@Composable
private fun RearBolt(width: Dp, height: Dp) {
    Canvas(Modifier.size(width = width, height = height)) {
        val w = size.width
        val h = size.height
        val path = Path()
        for (i in boltNx.indices) {
            val x = boltNx[i] * w
            val y = boltNy[i] * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, Color(0xFFFFC933))
    }
}

private data class RearField(val label: String, val value: String)

private fun parseRearColor(hex: String): Color {
    val v = hex.trim().removePrefix("color:").removePrefix("#")
    return when (v.length) {
        6 -> runCatching { Color(("ff$v").toLong(16).toInt()) }.getOrDefault(Color.Black)
        8 -> runCatching { Color(v.toLong(16).toInt()) }.getOrDefault(Color.Black)
        else -> Color.Black
    }
}

private fun rearField(index: Int?, status: DeviceStatus): RearField? {
    val id = rearFieldIds.getOrNull(index ?: return null) ?: return null
    return when (id) {
        "temp" -> RearField("温度", fmtRear1(status.tempC) + "℃")
        "soc" -> RearField("电量", status.soc.toString() + "%")
        "volt" -> RearField("电压", fmtRear2(status.voltageMv / 1000f) + "V")
        "cur" -> RearField("电流", fmtRear2(status.currentMa / 1000f) + "A")
        "power" -> RearField("功率", fmtRear1(status.powerW) + "W")
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

private fun fmtRear1(value: Float): String {
    val scaled = (value * 10f).toInt()
    return "${scaled / 10}.${kotlin.math.abs(scaled % 10)}"
}

private fun fmtRear2(value: Float): String {
    val scaled = (value * 100f).toInt()
    return "${scaled / 100}.${pad2(kotlin.math.abs(scaled % 100))}"
}

private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()
