package com.hyperos.xj.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 桌面预览：Compose 侧无 drawBitmapMesh，用逐行 drawImage 的行带位移近似同一折射公式
 * （参数与 PanelView / Android 侧同源）。先铺一张不位移的底图填边角，再在其上叠位移后的水下行带。
 */
actual fun DrawScope.drawRefractedBackground(
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

    val fit = maxOf(w / bw, h / bh)
    val dw = bw * fit
    val dh = bh * fit
    val left = (w - dw) / 2f
    val top = (h - dh) / 2f
    drawImage(
        image = image,
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(dw.toInt(), dh.toInt())
    )

    val scaleX = bw / dw
    val scaleY = bh / dh
    val srcW = w * scaleX
    val srcH = h * scaleY
    val srcLeft = (w / 2f - left) * scaleX - srcW / 2f
    val srcTop = (h / 2f - top) * scaleY - srcH / 2f

    val rows = LiquidStyle.MESH_ROWS
    val bandH = h / rows
    clipRect(0f, 0f, w, h) {
        for (j in 0 until rows) {
            val dy0 = j * bandH
            val dy1 = if (j == rows - 1) h else (j + 1) * bandH + 1f  // 下沿多 1px，压住行带接缝
            val cy = (dy0 + dy1) / 2f
            val dx = refractDx(cy, waveY, amp, ky, phase, h)
            if (dx == 0f) continue
            val sy0 = srcTop + dy0 * scaleY
            val sy1 = srcTop + dy1 * scaleY
            drawImage(
                image = image,
                srcOffset = IntOffset(srcLeft.toInt(), sy0.toInt()),
                srcSize = IntSize(srcW.toInt().coerceAtLeast(1), (sy1 - sy0).toInt().coerceAtLeast(1)),
                dstOffset = IntOffset(dx.toInt(), dy0.toInt()),
                dstSize = IntSize(w.toInt(), (dy1 - dy0).toInt().coerceAtLeast(1))
            )
        }
    }
}
