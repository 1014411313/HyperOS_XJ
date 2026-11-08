package com.hyperos.xj.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.runtime.DeviceStatus
import com.hyperos.xj.runtime.ToolboxRuntimeBridge
import com.hyperos.xj.ui.CollapsibleCard
import com.hyperos.xj.ui.GlassCard
import com.hyperos.xj.ui.LocalAccentColor
import com.hyperos.xj.ui.SectionLabel
import com.hyperos.xj.ui.XJ
import com.kyant.backdrop.Backdrop

@Composable
fun AboutPage(
    backdrop: Backdrop,
    bridge: ToolboxRuntimeBridge,
    status: DeviceStatus
) {
    var showSubPage by remember { mutableStateOf(false) }

    if (showSubPage) {
        AboutSubPage(backdrop, onBack = { showSubPage = false })
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("作者与声明")
                AboutRow("小米热炸了", "整合维护者", "入口") { }
                AboutRow("AxiaojiuY", "动态温控作者", "入口") { }
                AboutRow("听夜风1337", "解容工具箱作者", "入口") { }
                BasicText(
                    text = "二改声明：整合来源和维护者。",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                )
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("社区")
                AboutRow("动态温控 QQ 群", null, "打开") { }
                AboutRow("电池解容 QQ 群", null, "打开") { }
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("设备信息")
                AboutRow("设备型号", status.model, status.model) { }
                AboutRow("系统版本", null, status.systemVersion) { }
                AboutRow("运行环境", null, status.environment) { }
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("版本与更新")
                AboutRow("更新日志", "查看历次更新内容", "查看") { }
                AboutRow("检查更新", "检查最新版本", status.moduleVersion) { }
                BasicText(
                    text = "更新来源：GitHub / 作者酷安 / Telegram",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                )
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("帮助")
                AboutRow("使用说明", "按页面分组", "查看") { showSubPage = true }
                AboutRow("特别鸣谢 / 注意事项 / 档位说明", null, "展开") { showSubPage = true }
                AboutRow("场景功能 / 判断优先级", null, "展开") { showSubPage = true }
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("日志")
                AboutRow("查看日志文件", "运行日志", "打开") { }
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("工具")
                AboutRow("诊断与日志打包", "反馈问题用", "生成") { }
                AboutRow("卸载模块", "还原系统配置，需二次确认", "危险", danger = true) { }
            }
        }
    }
}

@Composable
private fun AboutSubPage(
    backdrop: Backdrop,
    onBack: () -> Unit
) {
    val accent = LocalAccentColor.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BasicText(
            text = "‹ 返回",
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp),
            style = TextStyle(color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        )

        CollapsibleCard(backdrop, "特别鸣谢", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
            BasicText("贡献者列表占位", style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp))
        }
        CollapsibleCard(backdrop, "注意事项", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
            BasicText("高温 / 包名 / 刷新率 / 旁路风险提示", style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp))
        }
        CollapsibleCard(backdrop, "使用帮助与指南", initiallyExpanded = true, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubBox("温控档位说明", Modifier.weight(1f))
                SubBox("场景功能说明", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubBox("场景判断优先级", Modifier.weight(1f))
                SubBox("常见问题", Modifier.weight(1f))
            }
        }
        CollapsibleCard(backdrop, "备份与恢复", initiallyExpanded = true, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubBox("已保存设置", Modifier.weight(1f))
                SubBox("数据大小", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            AboutRow("立即备份当前设置", null, "备份") { }
            AboutRow("保存路径", null, "路径") { }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubBox("复制到剪贴板", Modifier.weight(1f))
                SubBox("保存到路径", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubBox("浏览器下载", Modifier.weight(1f))
                SubBox("完整备份", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubBox("粘贴 JSON 恢复", Modifier.weight(1f))
                SubBox("从文件恢复", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            AboutRow("完整恢复全部数据", null, "恢复") { }
            AboutRow("恢复默认配置", null, "恢复") { }
        }
        CollapsibleCard(backdrop, "获取更新", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubBox("GitHub", Modifier.weight(1f))
                SubBox("作者酷安", Modifier.weight(1f))
                SubBox("Telegram", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AboutRow(
    title: String,
    subtitle: String?,
    action: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val accent = LocalAccentColor.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = if (danger) XJ.Danger else XJ.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                )
            }
        }
        BasicText(
            text = action,
            style = TextStyle(color = if (danger) XJ.Danger else accent, fontSize = 12.sp)
        )
    }
}

@Composable
private fun SubBox(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier
            .clip(com.kyant.shapes.RoundedRectangle(12.dp))
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f))
            .padding(vertical = 14.dp, horizontal = 8.dp),
        style = TextStyle(
            color = XJ.TextSecondary,
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    )
}
