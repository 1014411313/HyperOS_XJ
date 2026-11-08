package com.hyperos.xj.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.ceil

/** smoothstep：左右各 fadeHalf 宽度内位移从 0 平滑升到 1。 */
private fun edgeFade(x: Float, width: Float, fadeHalf: Float): Float {
    fun s(t: Float): Float {
        val v = t.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }
    return s(x / fadeHalf) * s((width - x) / fadeHalf)
}

actual fun DrawScope.drawRefractedBackground(
    image: ImageBitmap,
    waveY: Float,
    amp: Float,
    ky: Float,
    phase: Float
) {
    val w = size.width.toInt()
    val h = size.height.toInt()
    if (w <= 0 || h <= 0) return
    val src = image.asAndroidBitmap()
    val bw = src.width.toFloat()
    val bh = src.height.toFloat()
    if (bw <= 0f || bh <= 0f) return

    // 网格位移最大 ±amp：源图必须比画布大一圈，否则边缘采样越界露黑底（bug-075）
    val margin = ceil(amp).toInt() + 4
    val cw = w + 2 * margin
    val ch = h + 2 * margin
    val cover = RefractionMeshCache.ensure(cw, ch)
    val canvas = RefractionMeshCache.canvas ?: return

    val fit = maxOf(cw / bw, ch / bh)
    val dw = bw * fit
    val dh = bh * fit
    val left = (cw - dw) / 2f
    val top = (ch - dh) / 2f
    val rect = RefractionMeshCache.srcRect
    rect.set(left, top, left + dw, top + dh)
    cover.eraseColor(0)
    canvas.drawBitmap(src, null, rect, RefractionMeshCache.filterPaint)

    // 顶点按外扩矩形映射（与 PanelView.updateMesh 同式），左右各 8% 宽度内位移渐入，避免边缘硬切
    val verts = RefractionMeshCache.verts!!
    val fadeHalf = w * 0.08f
    var idx = 0
    for (j in 0..LiquidStyle.MESH_ROWS) {
        val y = -margin + ch * j / LiquidStyle.MESH_ROWS.toFloat()
        val dx = refractDx(y, waveY, amp, ky, phase, h.toFloat())
        for (i in 0..LiquidStyle.MESH_COLS) {
            val x = -margin + cw * i / LiquidStyle.MESH_COLS.toFloat()
            verts[idx++] = x + dx * edgeFade(x, w.toFloat(), fadeHalf)
            verts[idx++] = y
        }
    }

    drawIntoCanvas { c ->
        c.nativeCanvas.drawBitmapMesh(
            cover, LiquidStyle.MESH_COLS, LiquidStyle.MESH_ROWS, verts, 0, null, 0, null
        )
    }
}

/** 复用画布尺寸的裁剪位图、其 Canvas、顶点数组与临时 RectF，避免每帧分配。 */
private object RefractionMeshCache {
    private var width = 0
    private var height = 0
    var cover: Bitmap? = null
        private set
    var canvas: Canvas? = null
        private set
    var verts: FloatArray? = null
        private set
    val srcRect = RectF()
    val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun ensure(w: Int, h: Int): Bitmap {
        var current = cover
        if (current == null || width != w || height != h) {
            current?.recycle()
            current = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cover = current
            canvas = Canvas(current)
            verts = FloatArray((LiquidStyle.MESH_COLS + 1) * (LiquidStyle.MESH_ROWS + 1) * 2)
            width = w
            height = h
        }
        return current
    }
}
