package com.hyperos.xj.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.runtime.DeviceStatus
import com.hyperos.xj.runtime.Engine
import com.hyperos.xj.runtime.ToolboxRuntimeBridge
import com.hyperos.xj.runtime.pickDirectoryPath
import com.hyperos.xj.runtime.parseKv
import com.hyperos.xj.ui.ActionButton
import com.hyperos.xj.ui.GlassCard
import com.hyperos.xj.ui.GlassInput
import com.hyperos.xj.ui.LocalAccentColor
import com.hyperos.xj.ui.SectionLabel
import com.hyperos.xj.ui.ToggleLine
import com.hyperos.xj.ui.LocalPageInsets
import com.hyperos.xj.ui.PageHeader
import com.hyperos.xj.ui.ValueRow
import com.hyperos.xj.ui.XJ
import com.hyperos.xj.ui.showToast
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

@Composable
fun BatteryPage(
    backdrop: Backdrop,
    bridge: ToolboxRuntimeBridge,
    status: DeviceStatus
) {
    val accent = LocalAccentColor.current
    val scope = rememberCoroutineScope()
    var unlock by remember { mutableStateOf(status.unlock) }
    var pps by remember { mutableStateOf(status.freepps) }
    var cloudBlock by remember { mutableStateOf(status.cloudGuard) }
    var apl by remember { mutableStateOf(status.aplEnable) }
    var dtboStatus by remember { mutableStateOf("读取中") }
    var dtboStatusDetail by remember { mutableStateOf("") }
    // 空 = 用自动识别结果（local.img 优先，其次内置机型镜像）；非空 = 镜像库里手动选的那个
    var dtboImg by remember { mutableStateOf("") }
    var dtboAutoImg by remember { mutableStateOf("") }
    var dtboAutoSource by remember { mutableStateOf("") }
    var dtboAutoSize by remember { mutableStateOf(0L) }
    var dtboHint by remember { mutableStateOf("") }
    var dtboBusy by remember { mutableStateOf(false) }
    var showDtboConfirm by remember { mutableStateOf(false) }
    var dtboDir by remember { mutableStateOf("") }
    var dtboDirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var dtboImages by remember { mutableStateOf<List<DtboImage>>(emptyList()) }
    var dtboHasLocal by remember { mutableStateOf(false) }
    var dtboLibraryMsg by remember { mutableStateOf("") }
    var showLocalConfirm by remember { mutableStateOf(false) }
    var cycleTarget by remember { mutableStateOf(status.cycleFake) }
    var shutdownVoltage by remember { mutableStateOf(status.termV) }
    var loaded by remember { mutableStateOf(false) }

    // 解容等开关初值取自 status 快照，等 status 首次真实读取后再渲染，避免从默认值跳变
    val statusReady = status.environment != "检测中"

    fun saveBattery(key: String, value: String, title: String? = null) {
        scope.launch {
            val result = bridge.execResult(Engine.battery(key, value))
            if (title != null) {
                showToast(
                    when {
                        !result.success -> "失败：$title"
                        value == "on" -> "$title 已开启"
                        value == "off" -> "$title 已关闭"
                        else -> "$title 已保存"
                    }
                )
            }
        }
    }

    fun refreshDtboLibrary() {
        scope.launch {
            dtboDirs = bridge.exec(Engine.dtboDirs())
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            dtboImages = bridge.exec(Engine.dtboList())
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split('|')
                    val bytes = parts.getOrNull(2)?.toLongOrNull()
                    if (bytes == null) null else DtboImage(parts[1], bytes)
                }
                .toList()
        }
    }

    fun dtboTargetPath(): String = dtboImg.trim().ifEmpty { dtboAutoImg }

    fun dtboTargetName(): String =
        dtboTargetPath().substringAfterLast('/').ifBlank { "未识别到镜像" }

    fun dtboSizeText(bytes: Long): String = when {
        bytes >= 1_048_576 -> "${tenthOf(bytes, 1_048_576)} MB"
        bytes >= 1_024 -> "${bytes / 1_024} KB"
        else -> "$bytes B"
    }

    fun dtboTargetSource(): String = when {
        dtboImg.isNotBlank() -> "镜像库所选"
        dtboAutoSource == "local" -> "已下载"
        dtboAutoSource == "builtin" -> "模块内置"
        else -> ""
    }

    fun refreshDtboResolve() {
        scope.launch {
            val kv = parseKv(bridge.exec(Engine.dtboResolve()))
            dtboAutoImg = kv["img"].orEmpty()
            dtboAutoSource = kv["source"].orEmpty()
            dtboAutoSize = kv["size"]?.toLongOrNull() ?: 0L
            dtboHint = kv["hint"].orEmpty()
        }
    }

    fun flashLocal() {
        scope.launch {
            dtboHasLocal = bridge.exec(Engine.dtboHasLocal()).trim() == "yes"
            if (dtboHasLocal) showLocalConfirm = true else dtboLibraryMsg = "未找到 local.img"
        }
    }

    LaunchedEffect(Unit) {
        val out = bridge.exec(Engine.dtboStatus())
        dtboStatusDetail = out
        dtboStatus = out.lineSequence().firstOrNull { it.isNotBlank() }?.take(24) ?: "无输出"
        dtboHasLocal = bridge.exec(Engine.dtboHasLocal()).trim() == "yes"
        refreshDtboResolve()
        refreshDtboLibrary()
        loaded = true
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 18.dp,
                end = 18.dp,
                top = LocalPageInsets.current.top,
                bottom = LocalPageInsets.current.bottom
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageHeader(status)
        if (!loaded || !statusReady) {
            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                BasicText(
                    text = "正在读取设置…",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 13.sp)
                )
            }
        } else {
            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("电池解容")
                ToggleLine(backdrop, "电池解容", "挂载 BAA，防低电量锁容", unlock, onCheckedChange = { unlock = it; saveBattery("unlock", if (it) "on" else "off", "电池解容") })
                ToggleLine(backdrop, "公版 PPS 解锁", "第三方充电头功率兼容", pps, onCheckedChange = { pps = it; saveBattery("pps", if (it) "on" else "off", "公版 PPS 解锁") })
                ToggleLine(backdrop, "云控屏蔽", "拦截云端下发与巡检隔离", cloudBlock, onCheckedChange = { cloudBlock = it; saveBattery("cloud", if (it) "on" else "off", "云控屏蔽") })
                ToggleLine(backdrop, "自适应功率爬坡 APL", "全速档下动态上探", apl, onCheckedChange = { apl = it; saveBattery("apl", if (it) "on" else "off", "自适应功率爬坡 APL") })
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("DTBO")
                ValueRow("分区状态", dtboStatus)
                Spacer(Modifier.height(6.dp))
                val dtboTarget = dtboTargetPath()
                BasicText(
                    text = if (dtboTarget.isBlank()) {
                        dtboHint.ifBlank { "未找到可用镜像" }
                    } else {
                        "自动识别：${dtboTargetName()}（${dtboSizeText(dtboAutoSize)}，${dtboTargetSource()}）"
                    },
                    style = TextStyle(
                        color = if (dtboTarget.isBlank()) XJ.TextSecondary else XJ.TextPrimary,
                        fontSize = 12.sp
                    )
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = "刷入 DTBO 会改写系统分区，属高风险操作，确认无误再继续。",
                    style = TextStyle(color = XJ.Danger, fontSize = 11.sp)
                )
                if (dtboStatusDetail.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        text = dtboStatusDetail,
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                ActionButton(
                    backdrop,
                    "在线下载当前手机 DTBO",
                    {
                        if (dtboBusy) {
                            dtboStatusDetail = "上一条指令仍在执行。"
                        } else {
                            dtboBusy = true
                            scope.launch {
                                val code = status.deviceCode.ifBlank { "未知机型" }
                                dtboStatusDetail = "下载中…（$code）"
                                val result = bridge.execResult(Engine.dtboDownload())
                                dtboStatusDetail = result.output.ifBlank { if (result.success) "下载指令已结束。" else "下载失败。" }
                                showToast(if (result.success) "DTBO 下载完成" else "失败：DTBO 下载")
                                dtboHasLocal = bridge.exec(Engine.dtboHasLocal()).trim() == "yes"
                                refreshDtboResolve()
                                dtboBusy = false
                            }
                        }
                    },
                    Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                ActionButton(
                    backdrop,
                    "刷入并重启生效",
                    {
                        when {
                            dtboBusy -> dtboStatusDetail = "上一条刷入指令仍在执行。"
                            dtboTargetPath().isBlank() -> dtboStatusDetail = dtboHint.ifBlank { "未找到可用镜像，请先在线下载或到镜像库选择。" }
                            else -> showDtboConfirm = true
                        }
                    },
                    Modifier.fillMaxWidth(),
                    danger = true
                )
                if (showDtboConfirm) {
                    Spacer(Modifier.height(10.dp))
                    ConfirmCard(
                        backdrop,
                        title = "刷入 DTBO 镜像？",
                        detail = "将向当前 DTBO 分区写入 ${dtboTargetName()}（${dtboSizeText(dtboAutoSize)}）。\n\n风险：DTBO 是系统分区，写入错误镜像或错误槽位可能让设备无法开机（变砖），操作不可撤销。刷入前会自动备份当前分区到 /sdcard/Download/dtbo_backup，刷入后需重启生效。请确认电量充足、重要数据已备份。",
                        confirmText = "确认刷入",
                        onConfirm = {
                            val dtboFlashImg = dtboTargetPath()
                            val dtboViaLocal = dtboImg.isBlank() && dtboAutoSource == "local"
                            showDtboConfirm = false
                            dtboBusy = true
                            dtboStatus = "刷入中…"
                            scope.launch {
                                val result = bridge.execResult(if (dtboViaLocal) Engine.dtboFlashLocal() else Engine.dtboFlash(dtboFlashImg))
                                dtboStatusDetail = result.output
                                dtboStatus = result.output.lineSequence().firstOrNull { it.isNotBlank() }?.take(24) ?: "已下发，重启生效"
                                showToast(if (result.success) "DTBO 已刷入，重启生效" else "失败：DTBO 刷入")
                                dtboBusy = false
                            }
                        },
                        onDismiss = { showDtboConfirm = false }
                    )
                }
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("DTBO 镜像库")

                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassInput(dtboDir, { dtboDir = it }, "留空点「添加目录」选目录，或输入绝对路径", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ActionButton(
                        backdrop,
                        "添加目录",
                        {
                            val dir = dtboDir.trim()
                            when {
                                dir.isNotEmpty() && !dir.startsWith("/") -> dtboLibraryMsg = "扫描目录必须是绝对路径。"
                                dir.isNotEmpty() -> scope.launch {
                                    val result = bridge.execResult(Engine.dtboAddDir(dir))
                                    dtboLibraryMsg = result.output
                                    showToast(if (result.success) "扫描目录已添加" else "失败：添加扫描目录")
                                    dtboDir = ""
                                    refreshDtboLibrary()
                                }
                                else -> scope.launch {
                                    val picked = pickDirectoryPath()
                                    if (picked == null) {
                                        dtboLibraryMsg = "未选择目录"
                                    } else {
                                        val result = bridge.execResult(Engine.dtboAddDir(picked))
                                        dtboLibraryMsg = result.output
                                        showToast(if (result.success) "扫描目录已添加" else "失败：添加扫描目录")
                                        refreshDtboLibrary()
                                    }
                                }
                            }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))

                BasicText(
                    text = "扫描目录",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
                )
                if (dtboDirs.isEmpty()) {
                    BasicText(
                        text = "暂无扫描目录",
                        modifier = Modifier.padding(top = 4.dp),
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                } else {
                    dtboDirs.forEach { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                text = dir,
                                modifier = Modifier.weight(1f),
                                style = TextStyle(color = XJ.TextPrimary, fontSize = 12.sp)
                            )
                            BasicText(
                                text = "删除",
                                modifier = Modifier
                                    .clickable {
                                        scope.launch {
                                            val result = bridge.execResult(Engine.dtboDelDir(dir))
                                            dtboLibraryMsg = result.output
                                            showToast(if (result.success) "扫描目录已删除" else "失败：删除扫描目录")
                                            refreshDtboLibrary()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                style = TextStyle(color = XJ.Danger, fontSize = 12.sp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                BasicText(
                    text = "选择镜像",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
                )
                if (dtboImages.isEmpty()) {
                    BasicText(
                        text = "未找到 .img",
                        modifier = Modifier.padding(top = 4.dp),
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                } else {
                    dtboImages.forEach { img ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { dtboImg = if (dtboImg == img.path) "" else img.path }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    text = img.name,
                                    style = TextStyle(
                                        color = if (dtboImg == img.path) accent else XJ.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (dtboImg == img.path) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                )
                                BasicText(
                                    text = img.sizeText,
                                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                                )
                            }
                            if (dtboImg == img.path) {
                                BasicText(
                                    text = "已选（再点取消）",
                                    style = TextStyle(color = accent, fontSize = 12.sp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                ActionButton(
                    backdrop,
                    "刷本地镜像",
                    {
                        when {
                            dtboBusy -> dtboLibraryMsg = "上一条刷入指令仍在执行。"
                            else -> flashLocal()
                        }
                    },
                    Modifier.fillMaxWidth(),
                    secondary = true
                )
                if (dtboLibraryMsg.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        text = dtboLibraryMsg,
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                }
                if (showLocalConfirm) {
                    Spacer(Modifier.height(10.dp))
                    ConfirmCard(
                        backdrop,
                        title = "刷入本地镜像",
                        detail = "将向当前 DTBO 分区写入模块内置镜像 local.img。\n\n风险：DTBO 是系统分区，写入错误镜像或错误槽位可能让设备无法开机（变砖）。刷入前会自动备份当前分区，刷入后需重启生效。",
                        confirmText = "确认刷入",
                        onConfirm = {
                            showLocalConfirm = false
                            dtboBusy = true
                            dtboStatus = "刷入中…"
                            scope.launch {
                                val result = bridge.execResult(Engine.dtboFlashLocal())
                                dtboStatusDetail = result.output
                                dtboStatus = result.output.lineSequence().firstOrNull { it.isNotBlank() }?.take(24) ?: "已下发，重启生效"
                                showToast(if (result.success) "DTBO 已刷入，重启生效" else "失败：DTBO 刷入")
                                dtboBusy = false
                            }
                        },
                        onDismiss = { showLocalConfirm = false }
                    )
                }
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("循环次数伪装")
                ValueRow("伪装循环次数", status.cycleFake.ifBlank { "未设置" })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassInput(cycleTarget, { cycleTarget = it }, "输入目标次数", Modifier.weight(1f), numeric = true)
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        text = "应用",
                        modifier = Modifier
                            .clickable { saveBattery("cycle", cycleTarget, "循环伪装") }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    )
                }
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = "清除伪装",
                    modifier = Modifier
                        .clickable { saveBattery("cycle", "clear", "清除伪装") }
                        .padding(vertical = 4.dp),
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
                )
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("关机电压")
                ValueRow("关机电压", "2800~3500 mV · ${status.termV.ifBlank { "未设置" }}")
                GlassInput(shutdownVoltage, { shutdownVoltage = it }, "输入关机电压 mV", numeric = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        backdrop,
                        "应用关机电压",
                        {
                            scope.launch {
                                val term = bridge.execResult(Engine.batteryRaw("volt", "term", shutdownVoltage))
                                showToast(if (term.success) "关机电压已应用" else "失败：关机电压写入")
                            }
                        },
                        Modifier.weight(1f)
                    )
                    ActionButton(
                        backdrop,
                        "关闭",
                        {
                            scope.launch {
                                val result = bridge.execResult(Engine.batteryRaw("volt", "off"))
                                showToast(if (result.success) "关机电压已关闭" else "失败：关闭")
                            }
                        },
                        Modifier.weight(1f),
                        secondary = true
                    )
                }
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = "放电中电压 ≤ 该值时执行软关机（OS4 新平台无可写内核关机节点）。",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                )
            }
        }
    }
}

@Composable
private fun ConfirmCard(
    backdrop: Backdrop,
    title: String,
    detail: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(XJ.Danger.copy(alpha = 0.10f))
            .padding(12.dp)
    ) {
        BasicText(
            text = title,
            style = TextStyle(color = XJ.Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = detail,
            style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(backdrop, confirmText, onConfirm, Modifier.weight(1f), danger = true)
            ActionButton(backdrop, "取消", onDismiss, Modifier.weight(1f), secondary = true)
        }
    }
}

private data class DtboImage(val path: String, val bytes: Long) {
    val name: String get() = path.substringAfterLast('/')
    val sizeText: String
        get() = when {
            bytes >= 1_048_576 -> "${tenthOf(bytes, 1_048_576)} MB"
            bytes >= 1_024 -> "${tenthOf(bytes, 1_024)} KB"
            else -> "$bytes B"
        }
}

private fun tenthOf(bytes: Long, unit: Long): String {
    val tenth = bytes * 10 / unit
    return "${tenth / 10}.${tenth % 10}"
}
