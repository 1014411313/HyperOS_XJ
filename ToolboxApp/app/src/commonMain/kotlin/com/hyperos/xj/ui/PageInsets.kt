package com.hyperos.xj.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 页面滚动内容的上下预留：滚动容器本身铺满全屏（内容可滑到状态栏与底部导航栏后面），
 * 用这两个值把首/尾内容推到悬浮的标题区与底栏之外，避免初始位置被遮挡。
 */
data class PageInsets(val top: Dp = 0.dp, val bottom: Dp = 0.dp)

val LocalPageInsets = staticCompositionLocalOf { PageInsets() }
