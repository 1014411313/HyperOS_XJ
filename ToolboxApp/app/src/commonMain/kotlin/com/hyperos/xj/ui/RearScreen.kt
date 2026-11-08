package com.hyperos.xj.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import com.hyperos.xj.runtime.DeviceStatus
import com.hyperos.xj.runtime.gifFrameAt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

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

    // 焦散漂移相位：独立慢速循环（周期与 PanelView 同值），animate=false 时冻结。
    val caustTick = rememberInfiniteTransition(label = "rearCaustic")
    val rawCaustPhase by caustTick.animateFloat(
        initialValue = 0f,
        targetValue = LiquidStyle.CAUSTIC_DRIFT_LOOP_MS,
        animationSpec = infiniteRepeatable(
            animation = tween(LiquidStyle.CAUSTIC_DRIFT_LOOP_MS.toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rearCausticPhaseMs"
    )
    val caustPhaseMs = rawCaustPhase * if (animate) 1f else 0f

    // 水面高光段：固定 seed 生成一次，位置随波面流动，避免每帧全变导致闪烁。
    val highlightSegments = remember {
        val rnd = Random(20261109)
        List(LiquidStyle.HIGHLIGHT_COUNT) {
            HighlightSeg(
                len = LiquidStyle.HIGHLIGHT_MIN_LEN +
                    rnd.nextFloat() * (LiquidStyle.HIGHLIGHT_MAX_LEN - LiquidStyle.HIGHLIGHT_MIN_LEN),
                alpha = LiquidStyle.HIGHLIGHT_MIN_ALPHA +
                    rnd.nextFloat() * (LiquidStyle.HIGHLIGHT_MAX_ALPHA - LiquidStyle.HIGHLIGHT_MIN_ALPHA),
                jitter = (rnd.nextFloat() * 2f - 1f) * LiquidStyle.HIGHLIGHT_JITTER,
                skip = rnd.nextFloat() < LiquidStyle.HIGHLIGHT_SKIP
            )
        }
    }

    // 水下焦散弧：固定 seed 一次性生成，位置/深度/弧度不逐帧变化，仅随漂移相位缓慢移动。
    val caustics = remember {
        val rnd = Random(20261110)
        List(LiquidStyle.CAUSTIC_COUNT) {
            val len = LiquidStyle.CAUSTIC_MIN_LEN +
                rnd.nextFloat() * (LiquidStyle.CAUSTIC_MAX_LEN - LiquidStyle.CAUSTIC_MIN_LEN)
            CausticArc(
                bx = rnd.nextFloat(),
                bt = rnd.nextFloat().pow(1.5f),   // u^1.5：偏浅层密集
                len = len,
                alpha = LiquidStyle.CAUSTIC_MIN_ALPHA +
                    rnd.nextFloat() * (LiquidStyle.CAUSTIC_MAX_ALPHA - LiquidStyle.CAUSTIC_MIN_ALPHA),
                width = LiquidStyle.CAUSTIC_MIN_WIDTH +
                    rnd.nextFloat() * (LiquidStyle.CAUSTIC_MAX_WIDTH - LiquidStyle.CAUSTIC_MIN_WIDTH),
                bow = (rnd.nextFloat() * 2f - 1f) * 0.30f,   // 相对长度的弯曲比例
                speedK = if (rnd.nextFloat() < 0.4f) 2f else 1f,   // 整数倍频率保证漂移循环无缝
                phase = rnd.nextFloat() * 2f * PI.toFloat()
            )
        }
    }

    // 高对比边缘段：极短、几乎不透明的强亮线（独立 seed，与主高光分离）。
    val edgeSegs = remember {
        val rnd = Random(20261111)
        List(LiquidStyle.EDGE_COUNT) {
            EdgeSeg(
                bx = 0.03f + rnd.nextFloat() * 0.94f,
                len = LiquidStyle.EDGE_MIN_LEN +
                    rnd.nextFloat() * (LiquidStyle.EDGE_MAX_LEN - LiquidStyle.EDGE_MIN_LEN),
                alpha = LiquidStyle.EDGE_MIN_ALPHA +
                    rnd.nextFloat() * (LiquidStyle.EDGE_MAX_ALPHA - LiquidStyle.EDGE_MIN_ALPHA),
                jitter = (rnd.nextFloat() * 2f - 1f) * LiquidStyle.HIGHLIGHT_JITTER
            )
        }
    }

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
                Canvas(Modifier.matchParentSize()) {
                    drawRearRefractedBackground(gifFrame, level, phase)
                }
            }
        } else if (bgImage != null) {
            Canvas(Modifier.matchParentSize()) {
                drawRearRefractedBackground(bgImage, level, phase)
            }
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
            // 水体吸收色：极淡的垂直渐变（最深处 alpha ≤ 0.30），保持背景透出；下沿保留原有压暗。
            val absorb = Color(LiquidStyle.ABSORB_COLOR)
            clipPath(liquid) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            absorb.copy(alpha = LiquidStyle.ABSORB_TOP_ALPHA),
                            absorb.copy(alpha = LiquidStyle.ABSORB_BOTTOM_ALPHA)
                        ),
                        startY = waveY,
                        endY = h
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(w, h)
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.6f to Color.Black.copy(alpha = 0.20f),
                        1f to Color.Black.copy(alpha = 0.35f),
                        startY = h - 48f * s,
                        endY = h
                    ),
                    topLeft = Offset(0f, h - 48f * s),
                    size = Size(w, 48f * s)
                )

                // 水下焦散：浅层不规则极短亮弧，缓慢漂移（频率取整数倍保证漂移循环无缝）。
                val band = minOf(fillHeight, h * LiquidStyle.CAUSTIC_BAND)
                val driftAng = twoPi * caustPhaseMs / LiquidStyle.CAUSTIC_DRIFT_LOOP_MS
                caustics.forEach { ca ->
                    val drift = sin(driftAng * ca.speedK + ca.phase) * LiquidStyle.CAUSTIC_DRIFT_MAX * s
                    val x = ca.bx * w + drift
                    val half = ca.len * s * 0.5f
                    val y = waveAt(x) + band * ca.bt
                    val arc = Path().apply {
                        moveTo(x - half, y)
                        quadraticBezierTo(x, y + ca.bow * ca.len * s, x + half, y)
                    }
                    drawPath(
                        arc,
                        Color.White.copy(alpha = ca.alpha),
                        style = Stroke(width = maxOf(1f, ca.width * s), cap = StrokeCap.Round)
                    )
                }
            }

            // 水面高光：断续、明暗不均的极细线段，不要连续亮边。
            val hlWidth = maxOf(2.4f, w * 0.0032f)
            highlightSegments.forEachIndexed { i, seg ->
                if (seg.skip) return@forEachIndexed
                val cx = (i + 0.5f) / LiquidStyle.HIGHLIGHT_COUNT * w
                val half = seg.len * s * 0.5f
                val jitter = seg.jitter * s
                val x0 = cx - half
                val x1 = cx + half
                drawLine(
                    color = Color.White.copy(alpha = seg.alpha),
                    start = Offset(x0, waveAt(x0) + jitter),
                    end = Offset(x1, waveAt(x1) + jitter),
                    strokeWidth = hlWidth,
                    cap = StrokeCap.Round
                )
            }

            // 高对比边缘段：极短、接近不透明的强亮线，增强水面边缘的高光对比。
            edgeSegs.forEach { eg ->
                val cx = eg.bx * w
                val half = eg.len * s * 0.5f
                val jitter = eg.jitter * s
                drawLine(
                    color = Color.White.copy(alpha = eg.alpha),
                    start = Offset(cx - half, waveAt(cx - half) + jitter),
                    end = Offset(cx + half, waveAt(cx + half) + jitter),
                    strokeWidth = hlWidth,
                    cap = StrokeCap.Round
                )
            }

            // 气泡不填充：只画边缘折射弧 + 左上高光点，上升逻辑不变。
            if (fillHeight > 12f * s) {
                val bubbles = listOf(0.20f to 13f, 0.40f to 9f, 0.60f to 7f, 0.80f to 9f, 0.30f to 11f, 0.70f to 7f)
                val ringColor = Color(0xFFEFFFFF).copy(alpha = LiquidStyle.BUBBLE_RING_ALPHA)
                val ringWidth = maxOf(1f, LiquidStyle.BUBBLE_RING_WIDTH * s)
                bubbles.forEachIndexed { idx, (bx, br) ->
                    val t = (phase / twoPi + idx * 0.16f) % 1f
                    val cy = waveY + fillHeight * (1f - t)
                    val cx = bx * w
                    val r = br * s
                    drawCircle(ringColor, radius = r, center = Offset(cx, cy), style = Stroke(width = ringWidth))
                    drawCircle(
                        Color.White.copy(alpha = LiquidStyle.BUBBLE_SHINE_ALPHA),
                        radius = r * LiquidStyle.BUBBLE_SHINE_R,
                        center = Offset(cx - r * 0.30f, cy - r * 0.30f)
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
                    // 与 PanelView.drawCenter 同式的极弱水纹漂移（参考点同为设计中心 x=636），
                    // 闪电+百分比整组平移，拆开会与排版错位。
                    .graphicsLayer {
                        translationY = sin(636f * scale * 0.018f + phase) * 1.2f * scale * 1.2f * density
                        translationX = cos(636f * scale * 0.014f + phase) * 0.5f * scale * 1.2f * density
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status.charging) {
                    RearBolt(width = px(104f * 0.60f * fontScale), height = px(104f * 1.02f * fontScale))
                    Spacer(Modifier.width(px(104f * 0.16f * fontScale)))
                }
                BasicText(
                    text = "${status.soc}%",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = (104f * scale * fontScale).sp,
                        lineHeight = (104f * scale * fontScale).sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            val rowValues = fields.take(4).mapNotNull { rearField(it, status)?.value }
            if (rowValues.isNotEmpty()) {
                val rowStyle = TextStyle(
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = (27f * scale * fontScale).sp,
                    lineHeight = (27f * scale * fontScale).sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 与 PanelView.drawDataRow 同式，幅度取中心组的 0.8 倍。
                        .graphicsLayer {
                            translationY = sin(636f * scale * 0.018f + phase) * 1.2f * scale * 0.8f * density
                            translationX = cos(636f * scale * 0.014f + phase) * 0.5f * scale * 0.8f * density
                        }
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

/** 背景折射：与 PanelView 同参数（amp 按 scale 缩放、ky 换算到设备 px、phase 与波面同源）。 */
private fun DrawScope.drawRearRefractedBackground(image: ImageBitmap, level: Float, phase: Float) {
    val s = size.width / 976f
    drawRefractedBackgroundCovered(
        image = image,
        waveY = size.height - size.height * level,
        amp = LiquidStyle.REFRACT_AMP * s,
        ky = LiquidStyle.REFRACT_KY / s,
        phase = phase
    )
}

private data class HighlightSeg(val len: Float, val alpha: Float, val jitter: Float, val skip: Boolean)

private data class CausticArc(
    val bx: Float,
    val bt: Float,
    val len: Float,
    val alpha: Float,
    val width: Float,
    val bow: Float,
    val speedK: Float,
    val phase: Float
)

private data class EdgeSeg(val bx: Float, val len: Float, val alpha: Float, val jitter: Float)

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
