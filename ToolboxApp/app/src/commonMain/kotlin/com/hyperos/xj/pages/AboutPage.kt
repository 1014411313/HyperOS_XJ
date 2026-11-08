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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.runtime.DeviceStatus
import com.hyperos.xj.runtime.Engine
import com.hyperos.xj.runtime.ToolboxRuntimeBridge
import com.hyperos.xj.ui.ActionButton
import com.hyperos.xj.ui.CollapsibleCard
import com.hyperos.xj.ui.GlassCard
import com.hyperos.xj.ui.LocalAccentColor
import com.hyperos.xj.ui.SectionLabel
import com.hyperos.xj.ui.XJ
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

private data class ToolPage(val title: String, val hint: String, val command: String)

private val logPage = ToolPage(
    title = "运行日志",
    hint = "读取模块运行日志（/data/adb/modules/HyperOS_XJ/log/）",
    command = Engine.build("log")
)
private val diagPage = ToolPage(
    title = "诊断与日志打包",
    hint = "收集诊断信息并打包，打包路径见下方输出",
    command = Engine.build("diag", "pack")
)

@Composable
fun AboutPage(
    backdrop: Backdrop,
    bridge: ToolboxRuntimeBridge,
    status: DeviceStatus
) {
    var showSubPage by remember { mutableStateOf(false) }
    var showToolPage by remember { mutableStateOf(false) }
    var toolPage by remember { mutableStateOf(logPage) }
    var showUninstallConfirm by remember { mutableStateOf(false) }
    var showUninstallTip by remember { mutableStateOf(false) }

    when {
        showUninstallTip -> UninstallTipPage(backdrop, onBack = { showUninstallTip = false })
        showUninstallConfirm -> UninstallConfirmPage(
            backdrop,
            onBack = { showUninstallConfirm = false },
            onConfirmed = {
                showUninstallConfirm = false
                showUninstallTip = true
            }
        )
        showToolPage -> ToolOutputPage(backdrop, bridge, toolPage, onBack = { showToolPage = false })
        showSubPage -> AboutSubPage(backdrop, onBack = { showSubPage = false })
        else -> AboutMainPage(
            backdrop,
            status,
            onShowHelp = { showSubPage = true },
            onShowLog = {
                toolPage = logPage
                showToolPage = true
            },
            onShowDiag = {
                toolPage = diagPage
                showToolPage = true
            },
            onUninstall = { showUninstallConfirm = true }
        )
    }
}

@Composable
private fun AboutMainPage(
    backdrop: Backdrop,
    status: DeviceStatus,
    onShowHelp: () -> Unit,
    onShowLog: () -> Unit,
    onShowDiag: () -> Unit,
    onUninstall: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("作者与声明")
            AboutRow("小米热炸了", "整合维护者", null)
            AboutRow("AxiaojiuY", "动态温控作者", null)
            AboutRow("听夜风1337", "解容工具箱作者", null)
            BasicText(
                text = "二改声明：整合来源和维护者。作者主页暂未收录公开链接。",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
            )
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("社区")
            AboutRow("动态温控 QQ 群", "暂未收录群号与链接", null)
            AboutRow("电池解容 QQ 群", "暂未收录群号与链接", null)
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("设备信息")
            AboutRow("设备型号", null, status.model)
            AboutRow("系统版本", null, status.systemVersion)
            AboutRow("运行环境", null, status.environment)
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("版本与更新")
            AboutRow("更新日志", "随模块版本发布", null)
            AboutRow("检查更新", "当前版本 ${status.moduleVersion}，可在模块管理器检查更新", null)
            BasicText(
                text = "更新来源：GitHub / 作者酷安 / Telegram（暂未收录直达链接）",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
            )
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("帮助")
            AboutRow("使用说明", "按页面分组", "查看", onClick = onShowHelp)
            AboutRow("特别鸣谢 / 注意事项 / 档位说明", null, "展开", onClick = onShowHelp)
            AboutRow("场景功能 / 判断优先级", null, "展开", onClick = onShowHelp)
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("日志")
            AboutRow("查看日志文件", "运行日志", "打开", onClick = onShowLog)
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("工具")
            AboutRow("诊断与日志打包", "反馈问题用", "生成", onClick = onShowDiag)
            AboutRow("卸载模块", "还原系统配置，需二次确认", "危险", danger = true, onClick = onUninstall)
        }
    }
}

@Composable
private fun ToolOutputPage(
    backdrop: Backdrop,
    bridge: ToolboxRuntimeBridge,
    page: ToolPage,
    onBack: () -> Unit
) {
    val accent = LocalAccentColor.current
    val scope = rememberCoroutineScope()
    var output by remember(page) { mutableStateOf<String?>(null) }
    var running by remember(page) { mutableStateOf(false) }

    fun execute() {
        if (running) return
        scope.launch {
            running = true
            output = runCatching { bridge.exec(page.command) }
                .getOrNull()
                ?.trim()
                ?.ifEmpty { "(命令执行完成，无输出)" }
                ?: "执行失败，请检查 Root 与模块安装状态"
            running = false
        }
    }

    LaunchedEffect(page) { execute() }

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

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel(page.title)
            BasicText(page.hint, style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp))
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = "命令：" + page.command,
                style = TextStyle(
                    color = XJ.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            )
            Spacer(Modifier.height(8.dp))
            if (running) {
                BasicText("执行中…", style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp))
            } else {
                val result = output
                if (result != null) {
                    BasicText(
                        text = result,
                        style = TextStyle(
                            color = XJ.TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }

        ActionButton(backdrop, "重新执行", { execute() }, Modifier.fillMaxWidth(), secondary = true)
    }
}

@Composable
private fun UninstallConfirmPage(
    backdrop: Backdrop,
    onBack: () -> Unit,
    onConfirmed: () -> Unit
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

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("卸载模块")
            BasicText(
                "卸载前请确认：",
                style = TextStyle(color = XJ.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                "· 温控、功率、背屏等配置将还原为系统默认",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
            )
            BasicText(
                "· 背屏电池卡片会关闭，回到系统原生背屏",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
            )
            BasicText(
                "· 本 App 不直接删除模块目录，卸载动作在模块管理器完成",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
            )
            Spacer(Modifier.height(14.dp))
            ActionButton(backdrop, "我已确认，前往卸载", onConfirmed, Modifier.fillMaxWidth(), danger = true)
            Spacer(Modifier.height(8.dp))
            ActionButton(backdrop, "取消", onBack, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

@Composable
private fun UninstallTipPage(backdrop: Backdrop, onBack: () -> Unit) {
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

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("卸载模块")
            BasicText(
                text = "请在 Root 管理器（Magisk / KernelSU / APatch 等）的「模块」页面中找到 HyperOS_XJ 并卸载，卸载完成后重启设备生效。",
                style = TextStyle(color = XJ.TextPrimary, fontSize = 13.sp)
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "本 App 不直接删除 /data/adb/modules/HyperOS_XJ，避免跳过模块自身的还原流程。",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
            )
            Spacer(Modifier.height(14.dp))
            ActionButton(backdrop, "知道了", onBack, Modifier.fillMaxWidth(), secondary = true)
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
    action: String?,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val accent = LocalAccentColor.current
    val rowModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    }
    Row(
        rowModifier,
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
        if (action != null) {
            BasicText(
                text = action,
                style = TextStyle(
                    color = when {
                        onClick == null -> XJ.TextSecondary
                        danger -> XJ.Danger
                        else -> accent
                    },
                    fontSize = 12.sp
                )
            )
        }
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
