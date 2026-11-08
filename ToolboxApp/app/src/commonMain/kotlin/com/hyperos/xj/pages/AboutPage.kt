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
import com.hyperos.xj.runtime.openUrl
import com.hyperos.xj.ui.ActionButton
import com.hyperos.xj.ui.CollapsibleCard
import com.hyperos.xj.ui.GlassCard
import com.hyperos.xj.ui.LocalAccentColor
import com.hyperos.xj.ui.LocalPageInsets
import com.hyperos.xj.ui.PageHeader
import com.hyperos.xj.ui.SectionLabel
import com.hyperos.xj.ui.XJ
import com.hyperos.xj.ui.showToast
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

private data class ToolPage(val title: String, val hint: String, val command: String)

private val coolapkUrlTemplate = "https://www.coolapk.com/u/%s"

// 点击即拉起 QQ 加群页
private const val QQ_GROUP_DYNAMIC =
    "https://qun.qq.com/universal-share/share?ac=1&authKey=UBKBf3bnabhi%2Flls1atAoS9y3sXwzBUDQaGuPxjAYEhe4L7fKBMllTJlJAKID1Y7" +
        "&busi_data=eyJncm91cENvZGUiOiIxMDk3ODcxODgwIiwidG9rZW4iOiJTOEllamQ5dzZJMFlwamVxUGorak15UDBTL1dlaWRlODFUOEF4ckh0SlZRdCtKazVhd04welFSZmkxb0p2ZGU0IiwidWluIjoiMTAxNDQxMTMxMyJ9" +
        "&data=J1AI8mcwvBtSrfrSJXVoPqfQeujP1VsdBgBY3hF0h_neKnkoWYV5QxLvs4hPrZ8lxSFTOj5yLW2ZtKCNci7c-A&svctype=4&tempid=h5_group_info"

private const val QQ_GROUP_UNLOCK =
    "https://qun.qq.com/universal-share/share?ac=1&authKey=YhuSvQKUmZst0N%2BcIBC0hFVMtK%2FCNB5EUlB8H0lzsIKWcBMJL1VwhbqfkSZiiRRb" +
        "&busi_data=eyJncm91cENvZGUiOiIxMDc1OTc1ODM2IiwidG9rZW4iOiJaNUtpamlCa2xjdS96cldudGV4NXFycmR0UE9EWldlNkl4enY3ZDFTYnEzZkJPa2FnVUNtSHY3NlRadHk1SlJHIiwidWluIjoiMTAxNDQxMTMxMyJ9" +
        "&data=zd7iIa33lYCenKfv37BpLylPQoaDyvuk_p7NoYparDlGzka01HrKp9oGYxK4Hd85vAtpNiRJCW-xhD6IZPilPg&svctype=4&tempid=h5_group_info"

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
            bridge,
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
    bridge: ToolboxRuntimeBridge,
    status: DeviceStatus,
    onShowHelp: () -> Unit,
    onShowLog: () -> Unit,
    onShowDiag: () -> Unit,
    onUninstall: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var updateMsg by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                top = LocalPageInsets.current.top,
                bottom = LocalPageInsets.current.bottom
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageHeader(status)
        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("作者与声明")
            AboutRow("小米热炸了", "整合维护者", "酷安主页", onClick = { openUrl(coolapkUrlTemplate.format("517965")) })
            AboutRow("AxiaojiuY", "动态温控作者", "酷安主页", onClick = { openUrl(coolapkUrlTemplate.format("45432024")) })
            AboutRow("听夜风1337", "解容工具箱作者", "酷安主页", onClick = { openUrl(coolapkUrlTemplate.format("37273905")) })
            BasicText(
                text = "二改声明：整合来源和维护者。点击作者名可打开其酷安主页。",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
            )
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("社区")
            AboutRow(
                "动态温控 QQ 群",
                "群号 1097871880",
                "点击加群",
                onClick = { openUrl(QQ_GROUP_DYNAMIC) }
            )
            AboutRow(
                "电池解容 QQ 群",
                "群号 1075975836",
                "点击加群",
                onClick = { openUrl(QQ_GROUP_UNLOCK) }
            )
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
            AboutRow(
                "检查更新",
                updateMsg.ifBlank { "当前版本 ${status.moduleVersion}" },
                "立即检查",
                onClick = {
                    updateMsg = "检查中…"
                    scope.launch {
                        val result = bridge.execResult(Engine.checkUpdate())
                        showToast(if (result.success) "检查更新完成" else "失败：检查更新")
                        updateMsg = result.output.trim().ifBlank { "无输出" }
                    }
                }
            )
            BasicText(
                text = "更新源依次尝试 jsDelivr / Gitee / GitHub，国内网络下自动选可用源；也可在 settings.conf 配 update_base 指向自建镜像",
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
            val result = runCatching { bridge.execResult(page.command) }.getOrNull()
            output = result
                ?.output
                ?.trim()
                ?.ifEmpty { "(命令执行完成，无输出)" }
                ?: "执行失败，请检查 Root 与模块安装状态"
            showToast(if (result?.success == true) "命令执行完成" else "失败：命令执行")
            running = false
        }
    }

    LaunchedEffect(page) { execute() }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                top = LocalPageInsets.current.top,
                bottom = LocalPageInsets.current.bottom
            ),
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
            .padding(
                top = LocalPageInsets.current.top,
                bottom = LocalPageInsets.current.bottom
            ),
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
            .padding(
                top = LocalPageInsets.current.top,
                bottom = LocalPageInsets.current.bottom
            ),
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
            .padding(
                top = LocalPageInsets.current.top,
                bottom = LocalPageInsets.current.bottom
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BasicText(
            text = "‹ 返回",
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp),
            style = TextStyle(color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        )

        CollapsibleCard(backdrop, "特别鸣谢", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
            HelpBullet("感谢整合维护者「小米热炸了」。")
            HelpBullet("感谢动态温控作者「AxiaojiuY」与电池解容工具箱作者「听夜风1337」，本模块的温控场景与电池解容能力整理自上述上游方案。")
            HelpBullet("引擎脚本在本仓库内重写维护，模块包只包含本仓库的引擎、温控场景与电池载荷，不打包其他第三方模块。")
            HelpBullet("动态温控 QQ 群 1097871880，电池解容 QQ 群 1075975836，作者酷安主页可在「关于」首页点击打开。")
        }
        CollapsibleCard(backdrop, "注意事项", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
            HelpBullet("高温：引擎内置高温守卫，电池温度达到 48℃ 时会把「无限制 / 一档 / 二档 / 系统默认」强制压到三档；模块默认保留系统的高温停充保护，手动关闭后高温也会继续充电，长期高温充电会加速电池老化。")
            HelpBullet("包名：游戏识别按完整包名精确匹配，多个包名用「|」分隔；包名填错时游戏档位、锁定刷新率与游戏联动旁路都不会生效。")
            HelpBullet("刷新率：锁定刷新率通过系统设置写入，退出游戏后自动还原；长时间锁定高刷新率会增加耗电与发热。")
            HelpBullet("旁路：旁路供电是把充电电流压低（默认 500mA，可调且不超过记录的原始电流），并非真正断开充电；低电量时长时间旁路会让电池持续放电。")
            HelpBullet("DTBO：刷写 DTBO 镜像属高危操作，刷错会导致设备无法开机（变砖）；本版本 App 未开放刷入入口，如需操作必须先备份并自行确认。")
        }
        CollapsibleCard(backdrop, "使用帮助与指南", initiallyExpanded = true, modifier = Modifier.fillMaxWidth()) {
            CollapsibleCard(backdrop, "温控档位说明", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
                HelpBullet("系统默认（default）：还原原厂温控配置并重启系统温控守护，交还系统自行调度。")
                HelpBullet("无限制（unlimited）：写入空白温控配置，解除系统温控限制，性能优先，发热与功耗自负。")
                HelpBullet("一档～五档（level_1..level_5）：模块内置的五套温控场景配置，数字越大越保守。")
                HelpBullet("开启充电温控联动时，档位决定充电降功率的电池温度线：一档 45℃、二档 40℃、三档 35℃、四档 31℃、五档 26℃；超过温度线先降为中功率，再升高 4℃ 降为低功率。")
                HelpBullet("「默认档位」在不充电或关闭充电温控时生效；页面切换档位只写入配置，由引擎每 3 秒轮询应用。")
            }
            CollapsibleCard(backdrop, "场景功能说明", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
                HelpBullet("亮屏充电：充电且亮屏时应用的档位，默认「无限制」。")
                HelpBullet("息屏充电：充电且息屏时应用的档位，未设置时沿用亮屏充电档位。")
                HelpBullet("时段：每天「开始～结束」小时内充电时应用的档位，支持跨零点（如 23 → 7）；时段外沿用亮屏充电档位。")
                HelpBullet("游戏：亮屏且前台应用命中游戏包名时应用游戏档位，可同时锁定刷新率、联动旁路。")
                HelpBullet("旁路：充电时把充电电流压到很低（默认 500mA），接近「边玩边不充电」；支持手动开关、电量触发、温度触发（带迟滞回落）与游戏联动，仅充电且非联发科机型时生效。")
                HelpBullet("默认档位：不充电时应用的温控档位。")
            }
            CollapsibleCard(backdrop, "场景判断优先级", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
                HelpBullet("引擎每 3 秒判定一轮，先命中先生效：")
                HelpBullet("① 模块禁用或总开关关闭：还原充电与温控，所有场景不生效。")
                HelpBullet("② 旁路（仅充电时）：手动旁路 > 温度触发（带迟滞）> 电量触发 > 游戏联动；命中后直接接管充电，当轮不再应用温控场景。")
                HelpBullet("③ 温控场景（充电时）：游戏 > 息屏 > 时段 > 亮屏充电；不充电时使用「默认档位」。")
                HelpBullet("④ 高温守卫兜底：电池 ≥48℃ 时，「无限制 / 一档 / 二档 / 系统默认」强制压到三档。")
                HelpBullet("⑤ 充电功率联动：充电计划时段内取用户档与计划档中更严的一档；充电温控联动按当前档位的温度线自动降功率。")
            }
            CollapsibleCard(backdrop, "常见问题", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
                HelpBullet("改了档位不生效？切换只写入配置，引擎最多 3 秒后应用，且应用档位需重启系统温控守护（约 1～2 秒）；请确认模块未被禁用、总开关已打开；充电时实际档位由场景判定决定，可能被时段 / 息屏 / 游戏覆盖。")
                HelpBullet("为什么需要 Root？引擎直接改写系统温控配置目录、充电电流节点与系统属性，全部通过 su 调用完成，没有 Root 无法实现。")
                HelpBullet("DTBO 有什么风险？DTBO 是设备树分区镜像，刷错会导致设备无法开机；刷写前必须先备份原厂镜像。")
                HelpBullet("日志在哪里？模块日志在 /data/adb/modules/HyperOS_XJ/log/，可在主页「日志」入口查看；反馈问题前先用「诊断与日志打包」生成日志包。")
                HelpBullet("温控配置会被系统改回去吗？引擎应用档位后会把配置文件加锁防止系统覆盖，恢复「系统默认」时解锁并还原首次运行时备份的原厂配置。")
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
            AboutRow("GitHub", "暂未提供", null)
            AboutRow("作者酷安", "暂未提供", null)
            AboutRow("Telegram", "暂未提供", null)
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = "以上渠道暂未收录公开链接。",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
            )
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
private fun HelpBullet(text: String) {
    BasicText(
        text = "· $text",
        modifier = Modifier.padding(vertical = 3.dp),
        style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
    )
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
