package com.hyperos.xj.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hyperos.xj.runtime.gifFrameAt
import java.io.File

@Composable
actual fun AppMediaBackground(kind: String, path: String, blurDp: Float, modifier: Modifier) {
    if (kind == "gif") {
        // gifFrameAt 返回共享帧缓冲，引用不变而内容逐帧更新，须 neverEqualPolicy 才能每帧重组重绘
        var frame by remember(path) { mutableStateOf<ImageBitmap?>(null, neverEqualPolicy()) }
        LaunchedEffect(path) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    frame = gifFrameAt(path, (now - start) / 1_000_000L)
                }
            }
        }
        val bitmap = frame
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.blur(blurDp.dp)
            )
        }
        return
    }
    if (kind != "video") return
    if (!File(path).isFile) return
    val context = LocalContext.current
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    // 手机竖拍的视频常是「横屏帧 + rotation 元数据」：MediaPlayer 上报的宽高不含旋转，
    // 直接拿去算 center-crop 会把画面放大到只剩局部，所以先读真实尺寸与旋转角。
    val videoMeta = remember(path) { readVideoMeta(path) }
    val textureView = remember(path) {
        TextureView(context).apply {
            // 视频尺寸回调可能早于布局完成，view 宽高为 0 时算出的矩阵会把画面放大到看不见，
            // 布局变化后再按当前尺寸重算一次。
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val texture = v as TextureView
                val mediaPlayer = player
                val meta = videoMeta
                if (meta != null) {
                    applyVideoTransform(texture, meta)
                } else if (mediaPlayer != null && mediaPlayer.videoWidth > 0) {
                    applyVideoTransform(texture, VideoMeta(mediaPlayer.videoWidth, mediaPlayer.videoHeight, 0))
                }
            }
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    val mediaPlayer = MediaPlayer()
                    player = mediaPlayer
                    runCatching {
                        mediaPlayer.setSurface(Surface(surface))
                        mediaPlayer.isLooping = true
                        mediaPlayer.setVolume(0f, 0f)
                        mediaPlayer.setDataSource(path)
                        mediaPlayer.setOnVideoSizeChangedListener { _, videoWidth, videoHeight ->
                            if (videoMeta == null && videoWidth > 0 && videoHeight > 0) {
                                applyVideoTransform(this@apply, VideoMeta(videoWidth, videoHeight, 0))
                            }
                        }
                        mediaPlayer.setOnPreparedListener { it.start() }
                        mediaPlayer.prepareAsync()
                    }.onFailure {
                        mediaPlayer.release()
                        player = null
                    }
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    val mediaPlayer = player
                    val meta = videoMeta
                    if (meta != null) {
                        applyVideoTransform(this@apply, meta)
                    } else if (mediaPlayer != null && mediaPlayer.videoWidth > 0) {
                        applyVideoTransform(this@apply, VideoMeta(mediaPlayer.videoWidth, mediaPlayer.videoHeight, 0))
                    }
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    player?.release()
                    player = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }
    DisposableEffect(path) {
        onDispose {
            player?.release()
            player = null
            textureView.surfaceTextureListener = null
        }
    }
    // AndroidView 的 factory 只在首次组合执行，必须 key(path) 让重选视频后视图随新 TextureView 重建
    key(path) {
        AndroidView(
            factory = { textureView },
            modifier = modifier.blur(blurDp.dp)
        )
    }
}

/** 视频元数据：宽高为编码方向尺寸，rotation 为显示时需旋转的角度（0/90/180/270）。 */
private data class VideoMeta(val width: Int, val height: Int, val rotation: Int)

private fun readVideoMeta(path: String): VideoMeta? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (w > 0 && h > 0) VideoMeta(w, h, rot) else null
    } catch (t: Throwable) {
        null
    } finally {
        retriever.release()
    }
}

/**
 * 按视频原始比例缩放铺满视图（center-crop）：先围绕视图中心按 rotation 转正，
 * 再按「转正后的显示尺寸」等比缩放，两者都以视图中心为轴，画面不会偏。
 */
private fun applyVideoTransform(view: TextureView, meta: VideoMeta) {
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return
    val rot = ((meta.rotation % 360) + 360) % 360
    val dispW = if (rot % 180 == 0) meta.width.toFloat() else meta.height.toFloat()
    val dispH = if (rot % 180 == 0) meta.height.toFloat() else meta.width.toFloat()
    if (dispW <= 0f || dispH <= 0f) return
    val scale = maxOf(viewWidth / dispW, viewHeight / dispH)
    val cx = viewWidth / 2f
    val cy = viewHeight / 2f
    val matrix = Matrix()
    matrix.postRotate(rot.toFloat(), cx, cy)
    matrix.postScale(scale, scale, cx, cy)
    view.setTransform(matrix)
}
