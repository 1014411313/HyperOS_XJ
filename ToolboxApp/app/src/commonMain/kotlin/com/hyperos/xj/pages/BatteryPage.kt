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
import com.hyperos.xj.ui.CollapsibleCard
import com.hyperos.xj.runtime.LocalAppVisible
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
    var pps by remember(status.freepps) { mutableStateOf(status.freepps) }
    var ppsBusy by remember { mutableStateOf(false) }
    var cloudBlock by remember { mutableStateOf(status.cloudGuard) }
    var joyoseClean by remember { mutableStateOf(status.joyoseClean) }
    var cutoffInput by remember { mutableStateOf("") }
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
    var showDtboOsConfirm by remember { mutableStateOf(false) }
    var showDtboDownloadOsConfirm by remember { mutableStateOf(false) }
    var showDtboRestoreConfirm by remember { mutableStateOf(false) }
    var dtboBackups by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var dtboSelectedBackup by remember { mutableStateOf("") }
    var dtboPendingRestore by remember { mutableStateOf("") }
    var dtboPendingSource by remember { mutableStateOf("") }
    var dtboPendingAction by remember { mutableStateOf("flash") }
    var dtboPendingToken by remember { mutableStateOf("") }
    var dtboPendingAckOs by remember { mutableStateOf(false) }
    var dtboPreflightSummary by remember { mutableStateOf("") }
    var dtboDir by remember { mutableStateOf("") }
    var dtboDirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var dtboImages by remember { mutableStateOf<List<DtboImage>>(emptyList()) }
    var dtboHasLocal by remember { mutableStateOf(false) }
    var dtboLibraryMsg by remember { mutableStateOf("") }
    var cycleTarget by remember { mutableStateOf(status.cycleFake) }
    var shutdownVoltage by remember { mutableStateOf(status.termV) }
    val appVisible = LocalAppVisible.current

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

    fun dtboTargetBytes(): Long = if (dtboImg.isNotBlank()) {
        dtboImages.firstOrNull { it.path == dtboImg }?.bytes ?: 0L
    } else {
        dtboAutoSize
    }

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

    suspend fun refreshDtboBackups() {
        dtboBackups = bridge.exec(Engine.dtboBackups()).lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size == 3 && parts[0].startsWith('/')) parts[0] to parts[1] else null
        }.toList()
        if (dtboBackups.none { it.first == dtboSelectedBackup }) dtboSelectedBackup = ""
    }

    fun dtboActionPending(): Boolean =
        dtboBusy || showDtboConfirm || showDtboOsConfirm || showDtboDownloadOsConfirm || showDtboRestoreConfirm

    fun startDtboPreflight(source: String) {
        if (dtboActionPending()) {
            dtboStatusDetail = "上一条 DTBO 指令仍在执行。"
            return
        }
        dtboBusy = true
        dtboPendingAction = "flash"
        dtboPendingSource = source
        dtboPendingAckOs = false
        dtboPreflightSummary = "预检中…"
        scope.launch {
            val result = bridge.execResult(Engine.dtboPreflight(source))
            val kv = parseKv(result.output)
            dtboPreflightSummary = kv["summary"].orEmpty().ifBlank { result.output }
            dtboPendingToken = kv["snapshot_token"].orEmpty()
            dtboStatusDetail = result.output
            dtboPendingAction = kv["preflight_action"] ?: "flash"
            when {
                kv["requires_os_ack"] == "1" -> showDtboOsConfirm = true
                result.success && dtboPendingToken.isNotBlank() && dtboPendingAction == "restore" -> showDtboRestoreConfirm = true
                result.success && dtboPendingToken.isNotBlank() -> showDtboConfirm = true
                else -> dtboStatusDetail = result.output.ifBlank { "DTBO 预检失败。" }
            }
            dtboBusy = false
        }
    }

    fun acknowledgeUnknownOs() {
        showDtboOsConfirm = false
        dtboBusy = true
        dtboPendingAckOs = true
        scope.launch {
            val result = bridge.execResult(Engine.dtboPreflight(dtboPendingSource, true))
            val kv = parseKv(result.output)
            dtboPreflightSummary = kv["summary"].orEmpty().ifBlank { result.output }
            dtboPendingToken = kv["snapshot_token"].orEmpty()
            dtboPendingAction = kv["preflight_action"] ?: "flash"
            dtboStatusDetail = result.output
            if (result.success && dtboPendingToken.isNotBlank()) {
                if (dtboPendingAction == "restore") showDtboRestoreConfirm = true else showDtboConfirm = true
            }
            dtboBusy = false
        }
    }

    fun executeDtboFlash() {
        val source = dtboPendingSource
        val token = dtboPendingToken
        showDtboConfirm = false
        dtboBusy = true
        dtboStatus = "刷入中…"
        scope.launch {
            val result = bridge.execResult(Engine.dtboFlash(source, token, dtboPendingAckOs))
            dtboStatusDetail = result.output
            dtboStatus = result.output.lineSequence().firstOrNull { it.isNotBlank() }?.take(24) ?: "刷入完成"
            showToast(if (result.success) "DTBO 已刷入，不会自动重启" else "失败：DTBO 刷入")
            dtboBusy = false
        }
    }

    fun startRestorePreflight(backup: String) {
        if (dtboActionPending() || backup !in dtboBackups.map { it.first }) return
        dtboBusy = true
        dtboPendingRestore = backup
        dtboPreflightSummary = "恢复预检中…"
        scope.launch {
            try {
                val result = bridge.execResult(Engine.dtboRestorePreflight(backup))
                val kv = parseKv(result.output)
                dtboPreflightSummary = kv["summary"].orEmpty().ifBlank { result.output }
                dtboPendingToken = kv["snapshot_token"].orEmpty()
                dtboPendingAction = kv["preflight_action"].orEmpty()
                dtboStatusDetail = result.output
                if (result.success && dtboPendingToken.isNotBlank() && dtboPendingAction == "restore") {
                    showDtboRestoreConfirm = true
                }
            } finally {
                dtboBusy = false
            }
        }
    }

    fun executeDtboRestore() {
        val backup = dtboPendingRestore
        val token = dtboPendingToken
        showDtboRestoreConfirm = false
        dtboBusy = true
        dtboStatus = "恢复中…"
        scope.launch {
            try {
                val result = bridge.execResult(Engine.dtboRestore(backup, token))
                dtboStatusDetail = result.output
                dtboStatus = result.output.lineSequence().firstOrNull { it.isNotBlank() }?.take(24) ?: "恢复完成"
                showToast(if (result.success) "DTBO备份已恢复，不会自动重启" else "失败：DTBO恢复")
                refreshDtboBackups()
            } finally {
                dtboBusy = false
            }
        }
    }

    fun downloadDtbo(acknowledgeUnknownOs: Boolean) {
        showDtboDownloadOsConfirm = false
        dtboBusy = true
        scope.launch {
            val result = bridge.execResult(Engine.dtboDownload(acknowledgeUnknownOs))
            val kv = parseKv(result.output)
            dtboStatusDetail = result.output
            if (kv["requires_os_ack"] == "1") {
                showDtboDownloadOsConfirm = true
            } else {
                showToast(if (result.success) "DTBO 下载完成" else "失败：DTBO 下载")
                if (result.success) {
                    dtboHasLocal = bridge.exec(Engine.dtboHasLocal()).trim() == "yes"
                    refreshDtboResolve()
                }
            }
            dtboBusy = false
        }
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
        if (!statusReady) {
            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                BasicText(
                    text = "正在读取设置…",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 13.sp)
                )
            }
        } else {
            CollapsibleCard(backdrop, title = "电池解容", initiallyExpanded = true, modifier = Modifier.fillMaxWidth()) {
                ToggleLine(backdrop, "电池解容", "挂载 BAA，防低电量锁容", unlock, onCheckedChange = { unlock = it; saveBattery("unlock", if (it) "on" else "off", "电池解容") })
                ToggleLine(backdrop, "公版 PPS 解锁", "FreePPS · Seyud，拔线后应用", pps, onCheckedChange = { enabled ->
                    if (!ppsBusy) {
                        ppsBusy = true
                        scope.launch {
                            try {
                                val result = bridge.execResult(Engine.battery("pps", if (enabled) "on" else "off"))
                                if (result.success) {
                                    pps = enabled
                                    showToast(if (enabled) "公版 PPS 已开启，拔线后应用" else "公版 PPS 已关闭，拔线后恢复")
                                } else {
                                    showToast(result.output.lineSequence().firstOrNull { it.contains("公版 PPS") } ?: "未能保存公版 PPS 设置")
                                }
                            } finally {
                                ppsBusy = false
                            }
                        }
                    }
                })
                ToggleLine(backdrop, "云控屏蔽", "拦截云端下发与巡检隔离", cloudBlock, onCheckedChange = { cloudBlock = it; saveBattery("cloud", if (it) "on" else "off", "云控屏蔽") })
                ToggleLine(backdrop, "Joyose 云控屏蔽", "屏蔽联网，每 30 分钟清理数据", joyoseClean, onCheckedChange = { joyoseClean = it; saveBattery("joyose", if (it) "on" else "off", "Joyose 云控屏蔽") })
                ToggleLine(backdrop, "自适应功率爬坡 APL", "全速档下动态上探", apl, onCheckedChange = { apl = it; saveBattery("apl", if (it) "on" else "off", "自适应功率爬坡 APL") })
                Spacer(Modifier.height(12.dp))
                ActionButton(
                    backdrop = backdrop,
                    text = "修复健康度显示",
                    onClick = {
                        scope.launch {
                            val r = bridge.execResult(Engine.batteryRaw("health"))
                            showToast(if (r.success) "健康度显示已修复" else "修复失败")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    secondary = true
                )
            }

            CollapsibleCard(backdrop, title = "DTBO", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
                LaunchedEffect(appVisible) {
                    if (!appVisible) return@LaunchedEffect
                    val out = bridge.exec(Engine.dtboStatus())
                    dtboStatusDetail = out
                    dtboStatus = out.lineSequence().firstOrNull { it.isNotBlank() }?.take(24) ?: "无输出"
                    dtboHasLocal = bridge.exec(Engine.dtboHasLocal()).trim() == "yes"
                    refreshDtboResolve()
                }
                SectionLabel("DTBO")
                ValueRow("分区状态", dtboStatus)
                Spacer(Modifier.height(6.dp))
                val dtboTarget = dtboTargetPath()
                BasicText(
                    text = if (dtboTarget.isBlank()) {
                        dtboHint.ifBlank { "未找到可用镜像" }
                    } else {
                        "已选镜像：${dtboTargetName()}（${dtboSizeText(dtboTargetBytes())}，${dtboTargetSource()}）"
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
                        if (dtboActionPending()) {
                            dtboStatusDetail = "上一条 DTBO 指令或确认仍在处理。"
                        } else {
                            dtboStatusDetail = "下载中…（${status.deviceCode.ifBlank { "未知机型" }}）"
                            downloadDtbo(false)
                        }
                    },
                    Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                ActionButton(
                    backdrop,
                    "刷入 DTBO",
                    {
                        when {
                            dtboActionPending() -> dtboStatusDetail = "上一条 DTBO 指令或确认仍在处理。"
                            dtboTargetPath().isBlank() -> dtboStatusDetail = dtboHint.ifBlank { "未找到可用镜像，请先在线下载或到镜像库选择。" }
                            else -> startDtboPreflight(if (dtboImg.isNotBlank()) dtboImg else if (dtboAutoSource == "local") "local" else "builtin")
                        }
                    },
                    Modifier.fillMaxWidth(),
                    danger = true
                )
                if (showDtboDownloadOsConfirm) {
                    Spacer(Modifier.height(10.dp))
                    ConfirmCard(
                        backdrop,
                        title = "下载镜像的 OS 范围未知，仍要继续？",
                        detail = "远端身份清单没有提供可验证的 OS 范围。下载仍会严格校验文件名、大小和 SHA；继续只确认允许下载，不代表刷入兼容性已验证。",
                        confirmText = "确认 OS 未知并下载",
                        onConfirm = { downloadDtbo(true) },
                        onDismiss = { showDtboDownloadOsConfirm = false }
                    )
                }
                if (showDtboOsConfirm) {
                    Spacer(Modifier.height(10.dp))
                    ConfirmCard(
                        backdrop,
                        title = "OS 范围未知，仍要继续预检？",
                        detail = "身份清单没有提供可验证的 OS 范围。当前机型、活动槽、分区大小、镜像大小和 SHA 已显示在下方摘要；继续只代表你单独确认 OS 未知，不代表兼容性已验证。",
                        confirmText = "确认 OS 未知",
                        onConfirm = ::acknowledgeUnknownOs,
                        onDismiss = { showDtboOsConfirm = false }
                    )
                }
                if (showDtboRestoreConfirm) {
                    Spacer(Modifier.height(10.dp))
                    ConfirmCard(
                        backdrop,
                        title = "确认恢复 DTBO 备份？",
                        detail = "固定恢复摘要：\n$dtboPreflightSummary\n\n只会恢复刚才明确选择且校验过的本机、槽位和分区备份；目标变化会拒绝执行，不会自动重启。",
                        confirmText = "确认恢复备份",
                        onConfirm = ::executeDtboRestore,
                        onDismiss = { showDtboRestoreConfirm = false }
                    )
                }
                if (showDtboConfirm) {
                    Spacer(Modifier.height(10.dp))
                    ConfirmCard(
                        backdrop,
                        title = "确认刷入 DTBO？",
                        detail = "固定对象摘要：\n$dtboPreflightSummary\n\n刷入前会完整备份当前分区并校验；确认后若活动槽、目标分区、分区内容、机型或镜像快照变化将拒绝执行。不会自动重启。",
                        confirmText = "确认刷入",
                        onConfirm = ::executeDtboFlash,
                        onDismiss = { showDtboConfirm = false }
                    )
                }
            }

            CollapsibleCard(backdrop, title = "恢复 DTBO 备份", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
                LaunchedEffect(appVisible) { if (appVisible) refreshDtboBackups() }
                SectionLabel("恢复 DTBO 备份")
                ActionButton(backdrop, "刷新可用备份", { if (!dtboActionPending()) { dtboBusy = true; scope.launch { try { refreshDtboBackups() } finally { dtboBusy = false } } } }, Modifier.fillMaxWidth())
                dtboBackups.forEach { (path, size) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (!dtboActionPending()) dtboSelectedBackup = if (dtboSelectedBackup == path) "" else path }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(path, Modifier.weight(1f), style = TextStyle(color = if (dtboSelectedBackup == path) accent else XJ.TextPrimary, fontSize = 11.sp))
                        BasicText("${dtboSizeText(size.toLongOrNull() ?: 0L)}${if (dtboSelectedBackup == path) " · 已选" else ""}", style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp))
                    }
                }
                if (dtboBackups.isEmpty()) BasicText("没有当前机型、槽位和分区匹配的有效备份", style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp))
                ActionButton(backdrop, "预检并恢复所选备份", { if (dtboSelectedBackup.isBlank()) dtboStatusDetail = "请先从校验列表明确选择一个备份。" else startRestorePreflight(dtboSelectedBackup) }, Modifier.fillMaxWidth(), danger = true)
            }

            CollapsibleCard(backdrop, title = "DTBO 镜像库", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
                LaunchedEffect(appVisible) { if (appVisible) refreshDtboLibrary() }
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
                                .clickable {
                                    if (!dtboActionPending()) dtboImg = if (dtboImg == img.path) "" else img.path
                                }
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
                            dtboActionPending() -> dtboLibraryMsg = "上一条 DTBO 指令或确认仍在处理。"
                            else -> startDtboPreflight("local")
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

            }

            CollapsibleCard(backdrop, title = "循环次数伪装", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
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

            CollapsibleCard(backdrop, title = "充满断充", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
                ValueRow("当前设置", if (status.chgCutoff.isBlank()) "未设置" else "${status.chgCutoff} · ${cutoffPhaseText(status.chgCutoffPhase)}")
                GlassInput(cutoffInput, { cutoffInput = it }, "输入 HH:MM（00:01~08:00）")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        backdrop,
                        "应用",
                        {
                            scope.launch {
                                val r = bridge.execResult(Engine.batteryRaw("cutoff", cutoffInput))
                                showToast(if (r.success) "已设置断充 $cutoffInput" else "失败：时间需 00:01~08:00")
                            }
                        },
                        Modifier.weight(1f)
                    )
                    ActionButton(
                        backdrop,
                        "关闭",
                        {
                            scope.launch {
                                val r = bridge.execResult(Engine.batteryRaw("cutoff", "off"))
                                showToast(if (r.success) "断充已关闭" else "失败：关闭")
                            }
                        },
                        Modifier.weight(1f),
                        secondary = true
                    )
                }
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = "充满后按设定时长断开充电，拔插恢复；电量 ≤30% 自动恢复。",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                )
            }

            CollapsibleCard(backdrop, title = "关机电压", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
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

private fun cutoffPhaseText(phase: String): String = when (phase) {
    "timing" -> "计时中"
    "cutoff" -> "已断充"
    else -> "等待充满"
}

private fun tenthOf(bytes: Long, unit: Long): String {
    val tenth = bytes * 10 / unit
    return "${tenth / 10}.${tenth % 10}"
}
