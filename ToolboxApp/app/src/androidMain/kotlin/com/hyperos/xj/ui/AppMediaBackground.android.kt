package com.hyperos.xj.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
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
    val textureView = remember(path) {
        TextureView(context).apply {
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
                            applyVideoCenterCrop(this@apply, videoWidth, videoHeight)
                        }
                        mediaPlayer.setOnPreparedListener { it.start() }
                        mediaPlayer.prepareAsync()
                    }.onFailure {
                        mediaPlayer.release()
                        player = null
                    }
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    val mediaPlayer = player ?: return
                    applyVideoCenterCrop(this@apply, mediaPlayer.videoWidth, mediaPlayer.videoHeight)
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

/** TextureView center-crop：按视频/视口较大缩放比铺满后居中裁切，避免拉伸变形。 */
private fun applyVideoCenterCrop(view: TextureView, videoWidth: Int, videoHeight: Int) {
    if (videoWidth <= 0 || videoHeight <= 0) return
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return
    val scale = maxOf(viewWidth / videoWidth, viewHeight / videoHeight)
    val matrix = Matrix()
    matrix.setScale(scale, scale)
    matrix.postTranslate((viewWidth - videoWidth * scale) / 2f, (viewHeight - videoHeight * scale) / 2f)
    view.setTransform(matrix)
}
