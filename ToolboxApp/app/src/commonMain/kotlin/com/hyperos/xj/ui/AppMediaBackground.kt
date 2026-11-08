package com.hyperos.xj.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * App 自身媒体背景：kind 为 bgKind（wallpaper/image/gif/video），path 为 gif/video 落盘的绝对路径。
 * 文件缺失或解码失败时渲染为透明，由调用方的底层壁纸透出。
 */
@Composable
expect fun AppMediaBackground(kind: String, path: String, blurDp: Float, modifier: Modifier = Modifier)
