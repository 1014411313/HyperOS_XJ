package com.hyperos.xj.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 背屏充电动画的共用视觉参数：与 PanelView.java 内的常量一一对应，两侧必须同步修改。
 * 设计基准宽度 976，实际像素按 scale = width / 976 缩放。
 */
object LiquidStyle {
    const val MESH_COLS = 12
    const val MESH_ROWS = 40
    const val REFRACT_AMP = 24f       // 水平位移幅度（设计基准 px；真机反馈 36 仍过强，再降 1/3）
    const val REFRACT_KY = 0.026f     // 相位随 y 的变化率（rad / 设计 px）

    const val ABSORB_TOP_ALPHA = 0.04f
    const val ABSORB_BOTTOM_ALPHA = 0.30f
    const val ABSORB_COLOR = 0xFF14202AL  // 中性海蓝灰：几乎不改变背景色相（原深绿 0x0A4D22）

    // 微弱焦散：水下浅层的不规则极短亮弧（与 PanelView 同源）
    const val CAUSTIC_COUNT = 40
    const val CAUSTIC_BAND = 0.12f
    const val CAUSTIC_MIN_LEN = 5f
    const val CAUSTIC_MAX_LEN = 18f
    const val CAUSTIC_MIN_ALPHA = 0.05f
    const val CAUSTIC_MAX_ALPHA = 0.18f
    const val CAUSTIC_MIN_WIDTH = 1.0f
    const val CAUSTIC_MAX_WIDTH = 2.0f
    const val CAUSTIC_DRIFT_MAX = 10f
    const val CAUSTIC_DRIFT_LOOP_MS = 9333f

    // 水面高对比边缘段：极短、几乎不透明的强亮线
    const val EDGE_COUNT = 8
    const val EDGE_MIN_LEN = 4f
    const val EDGE_MAX_LEN = 10f
    const val EDGE_MIN_ALPHA = 0.95f
    const val EDGE_MAX_ALPHA = 1.0f

    const val HIGHLIGHT_COUNT = 56
    const val HIGHLIGHT_MIN_LEN = 8f
    const val HIGHLIGHT_MAX_LEN = 34f
    const val HIGHLIGHT_MIN_ALPHA = 0.45f
    const val HIGHLIGHT_MAX_ALPHA = 1.0f
    const val HIGHLIGHT_SKIP = 0.25f
    const val HIGHLIGHT_JITTER = 1.5f

    const val BUBBLE_RING_ALPHA = 0.55f
    const val BUBBLE_RING_WIDTH = 2.2f
    const val BUBBLE_SHINE_ALPHA = 0.7f
    const val BUBBLE_SHINE_R = 0.28f
}

/** 逐行折射位移：水面以上为 0，以下 sin(y*ky+phase)*amp*ramp。与 PanelView.updateMesh 同式。 */
fun refractDx(y: Float, waveY: Float, amp: Float, ky: Float, phase: Float, height: Float): Float {
    if (y <= waveY) return 0f
    val span = maxOf(1f, height - waveY)
    // 幅度随深度“缓慢”增大：t^1.5，浅处更弱、深处渐强
    val t = minOf(1f, (y - waveY) / span)
    val ramp = t * kotlin.math.sqrt(t)
    return sin(y * ky + phase) * amp * ramp
}

/**
 * 带位移余量的背景折射（预览条带实现）：源图按四边外扩 margin 的虚拟矩形 cover 采样，
 * 行带源窗口随位移平移，位移最大 ±amp 时采样仍在源图内，边缘不露底（与 PanelView.prepareCover 同式）；
 * 左右各 8% 宽度内位移按 smoothstep 渐入，避免边缘硬切。[amp] 为设备像素、[ky] 为 rad / 设备像素。
 */
fun DrawScope.drawRefractedBackgroundCovered(
    image: ImageBitmap,
    waveY: Float,
    amp: Float,
    ky: Float,
    phase: Float
) {
    val w = size.width
    val h = size.height
    val bw = image.width.toFloat()
    val bh = image.height.toFloat()
    if (w <= 0f || h <= 0f || bw <= 0f || bh <= 0f) return

    val margin = ceil(amp) + 4f
    val ew = w + 2f * margin
    val eh = h + 2f * margin
    val fit = maxOf(ew / bw, eh / bh)
    val dw = bw * fit
    val dh = bh * fit
    val left = (ew - dw) / 2f
    val top = (eh - dh) / 2f
    val scaleX = bw / dw
    val scaleY = bh / dh

    val rows = LiquidStyle.MESH_ROWS
    val bandH = h / rows
    val fadeHalf = w * 0.08f
    clipRect(0f, 0f, w, h) {
        for (j in 0 until rows) {
            val dy0 = j * bandH
            val dy1 = if (j == rows - 1) h else (j + 1) * bandH + 1f  // 下沿多 1px，压住行带接缝
            val cy = (dy0 + dy1) / 2f
            val dx = refractDx(cy, waveY, amp, ky, phase, h) *
                smooth01(cy / fadeHalf) * smooth01((w - cy) / fadeHalf)
            // 源窗口取外扩采样平面上随 -dx 平移的 view 窗口：位移 ±amp 时窗口仍落在源图内。
            val sx = (margin - dx - left) * scaleX
            val sy = (margin + dy0 - top) * scaleY
            drawImage(
                image = image,
                srcOffset = IntOffset(sx.roundToInt(), sy.roundToInt()),
                srcSize = IntSize(
                    (w * scaleX).roundToInt().coerceAtLeast(1),
                    ((dy1 - dy0) * scaleY).roundToInt().coerceAtLeast(1)
                ),
                dstOffset = IntOffset(0, dy0.roundToInt()),
                dstSize = IntSize(
                    w.roundToInt().coerceAtLeast(1),
                    (dy1 - dy0).roundToInt().coerceAtLeast(1)
                )
            )
        }
    }
}

/** smoothstep：t 截到 [0,1] 后两端平滑，用于边缘位移渐入。 */
private fun smooth01(t: Float): Float {
    val v = t.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

/**
 * 以逐行水平位移绘制背景（折射）。Android 走 drawBitmapMesh，桌面走行带近似。
 * [amp] 为设备像素、[ky] 为 rad / 设备像素、[phase] 与液面波浪同源。
 */
expect fun DrawScope.drawRefractedBackground(
    image: ImageBitmap,
    waveY: Float,
    amp: Float,
    ky: Float,
    phase: Float
)
