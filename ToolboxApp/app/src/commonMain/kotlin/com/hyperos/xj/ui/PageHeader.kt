package com.hyperos.xj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.runtime.DeviceStatus

val LocalSelectedTab = compositionLocalOf { 0 }

/**
 * 页面标题区。放在各页滚动内容顶部，随内容一起滚出屏幕：滑动时标题跟卡片同时上移，
 * 不做固定悬浮，避免与内容之间出现分界带。
 */
@Composable
fun PageHeader(status: DeviceStatus) {
    val selectedTab = LocalSelectedTab.current
    val accent = LocalAccentColor.current
    val pageTitle = when (selectedTab) {
        0 -> "HyperOS - 动态温控·解容"
        1 -> "温控"
        2 -> "电池"
        3 -> "显示外观"
        else -> "关于"
    }
    val pageSubtitle = when (selectedTab) {
        0 -> "监控入口 + 充电功率 + 设备信息"
        1 -> "档位 / 充电 / 游戏 / 旁路"
        2 -> "解容 / DTBO / 循环 / 电压"
        3 -> "主题 / 背屏卡片 / 玻璃 / 缩放 / 底栏 / 背景"
        else -> "作者 / 版本 / 帮助 / 维护"
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                text = pageTitle,
                style = TextStyle(color = XJ.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            )
            BasicText(
                text = pageSubtitle,
                style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (status.charging) accent else XJ.Warm)
            )
            Spacer(Modifier.size(6.dp))
            BasicText(
                text = status.status,
                style = TextStyle(color = XJ.TextPrimary, fontSize = 11.sp)
            )
        }
    }
}
