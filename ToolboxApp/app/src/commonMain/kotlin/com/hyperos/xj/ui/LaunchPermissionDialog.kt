package com.hyperos.xj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.runtime.batteryOptimizationIgnored
import com.hyperos.xj.runtime.createToolboxBridge
import com.hyperos.xj.runtime.isLsposedInstalled
import com.hyperos.xj.runtime.notificationsGranted
import com.hyperos.xj.runtime.openLsposedManager
import com.hyperos.xj.runtime.requestIgnoreBatteryOptimizations
import com.hyperos.xj.runtime.requestNotifications
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay

/**
 * 开屏权限与配置检查。用应用内覆盖层而不是 Dialog：Dialog 是独立窗口，采不到主窗口的
 * LayerBackdrop，玻璃卡片会失效；覆盖层还能顺带接系统返回键。
 * 通知/电池优化状态在打开期间轮询刷新，Root 与 LSPosed 由按钮触发。
 */
@Composable
fun LaunchPermissionDialog(backdrop: Backdrop, onDismiss: (noMore: Boolean) -> Unit) {
    val bridge = remember { createToolboxBridge() }
    var notifyGranted by remember { mutableStateOf(notificationsGranted()) }
    var batteryOk by remember { mutableStateOf(batteryOptimizationIgnored()) }
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var rootCheckKey by remember { mutableStateOf(0) }
    var noMore by remember { mutableStateOf(false) }
    val lsposedInstalled = remember { isLsposedInstalled() }

    PlatformBackHandler { onDismiss(noMore) }

    LaunchedEffect(Unit) {
        while (true) {
            notifyGranted = notificationsGranted()
            batteryOk = batteryOptimizationIgnored()
            delay(700)
        }
    }
    LaunchedEffect(rootCheckKey) {
        rootOk = null
        val result = runCatching { bridge.execResult("id -u") }.getOrNull()
        rootOk = result != null && result.success && result.output.trim() == "0"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
        ) {
            BasicText(
                text = "权限与配置检查",
                style = TextStyle(color = XJ.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = "下列项目未完成时功能可能不生效",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
            )
            Spacer(Modifier.height(14.dp))

            PermissionRow(
                title = "通知权限",
                desc = "Android 13+ 后台服务通知，缺它会看不到运行状态",
                done = notifyGranted,
                actionText = "去授权",
                onAction = { requestNotifications() }
            )
            PermissionRow(
                title = "Root 权限",
                desc = "引擎所有命令通过 su 执行，需在 Root 管理器放行",
                done = rootOk,
                actionText = "重新检测",
                onAction = { rootCheckKey += 1 }
            )
            PermissionRow(
                title = "电池优化白名单",
                desc = "防止后台被系统杀掉",
                done = batteryOk,
                actionText = "去设置",
                onAction = { requestIgnoreBatteryOptimizations() }
            )
            PermissionRow(
                title = "背屏模块（LSPosed）",
                desc = if (lsposedInstalled) {
                    "已确认在 LSPosed 中只勾选「背屏」（com.xiaomi.subscreencenter）后重启"
                } else {
                    "未检测到 LSPosed 管理器，背屏卡片需要 LSPosed 注入才生效"
                },
                done = if (lsposedInstalled) null else false,
                actionText = if (lsposedInstalled) "打开 LSPosed" else null,
                onAction = if (lsposedInstalled) ({ openLsposedManager() }) else null
            )

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.clickable { noMore = !noMore },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .width(16.dp)
                            .height(16.dp)
                            .background(
                                if (noMore) XJ.Accent else Color(0x33FFFFFF),
                                RoundedCornerShape(5.dp)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        text = "以后不再提示",
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
                    )
                }
                Box(
                    Modifier
                        .background(XJ.Accent, RoundedCornerShape(12.dp))
                        .clickable { onDismiss(noMore) }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    BasicText(
                        text = "关闭",
                        style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    done: Boolean?,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(color = XJ.TextPrimary, fontSize = 14.sp)
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = desc,
                style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            done == true -> BasicText(
                text = "已完成",
                style = TextStyle(color = XJ.AccentSoft, fontSize = 12.sp)
            )
            done == false -> BasicText(
                text = "未完成",
                style = TextStyle(color = XJ.Danger, fontSize = 12.sp)
            )
            else -> BasicText(
                text = "检测中",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .background(Color(0x1FFFFFFF), RoundedCornerShape(10.dp))
                    .clickable { onAction() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                BasicText(
                    text = actionText,
                    style = TextStyle(color = XJ.TextPrimary, fontSize = 12.sp)
                )
            }
        }
    }
}
