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
import com.hyperos.xj.ui.ActionButton
import com.hyperos.xj.ui.GlassCard
import com.hyperos.xj.ui.GlassInput
import com.hyperos.xj.ui.LocalAccentColor
import com.hyperos.xj.ui.SectionLabel
import com.hyperos.xj.ui.ToggleLine
import com.hyperos.xj.ui.ValueRow
import com.hyperos.xj.ui.XJ
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
    var dtboImg by remember { mutableStateOf("") }
    var dtboBusy by remember { mutableStateOf(false) }
    var showDtboConfirm by remember { mutableStateOf(false) }
    var showVoltForceConfirm by remember { mutableStateOf(false) }
    var cycleTarget by remember { mutableStateOf(status.cycleFake) }
    var fullVoltage by remember { mutableStateOf(status.fvV) }
    var forceLimit by remember { mutableStateOf(status.fvForce) }
    var shutdownVoltage by remember { mutableStateOf(status.termV) }

    fun saveBattery(key: String, value: String) {
        scope.launch { bridge.exec(Engine.battery(key, value)) }
    }

    LaunchedEffect(Unit) {
        val out = bridge.exec(Engine.dtboStatus())
        dtboStatusDetail = out
        dtboStatus = out.lineSequence().firstOrNull { it.isNotBlank() }?.take(24) ?: "无输出"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("电池解容")
            ToggleLine(backdrop, "电池解容", "挂载 BAA，防低电量锁容", unlock, onCheckedChange = { unlock = it; saveBattery("unlock", if (it) "on" else "off") })
            ToggleLine(backdrop, "公版 PPS 解锁", "第三方充电头功率兼容", pps, onCheckedChange = { pps = it; saveBattery("pps", if (it) "on" else "off") })
            ToggleLine(backdrop, "云控屏蔽", "拦截云端下发与巡检隔离", cloudBlock, onCheckedChange = { cloudBlock = it; saveBattery("cloud", if (it) "on" else "off") })
            ToggleLine(backdrop, "自适应功率爬坡 APL", "全速档下动态上探", apl, onCheckedChange = { apl = it; saveBattery("apl", if (it) "on" else "off") })
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("DTBO")
            ValueRow("DTBO 刷入", dtboStatus)
            if (dtboStatusDetail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = dtboStatusDetail,
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                )
            }
            Spacer(Modifier.height(8.dp))
            GlassInput(dtboImg, { dtboImg = it }, "镜像文件名，如 haotian.img")
            Spacer(Modifier.height(8.dp))
            ActionButton(
                backdrop,
                "在线下载当前手机 DTBO",
                { dtboStatusDetail = "在线下载 DTBO 尚未实现，可使用模块内置镜像（battery/dtbo/）。" },
                Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            ActionButton(
                backdrop,
                "确认刷入，重启生效",
                {
                    val img = dtboImg.trim()
                    when {
                        dtboBusy -> dtboStatusDetail = "上一条刷入指令仍在执行。"
                        img.isEmpty() -> dtboStatusDetail = "请先填入要刷入的镜像文件名（模块内置 battery/dtbo/ 下）。"
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
                    title = "刷入 DTBO 镜像",
                    detail = "将向当前 DTBO 分区写入 ${Engine.MODULE_DIR}/battery/dtbo/${dtboImg.trim()}，错误镜像或错误槽位可能导致设备无法启动，刷入后需重启生效。",
                    confirmText = "确认刷入",
                    onConfirm = {
                        showDtboConfirm = false
                        dtboBusy = true
                        dtboStatus = "刷入中…"
                        scope.launch {
                            val out = bridge.exec(Engine.dtboFlash(dtboImg.trim()))
                            dtboStatusDetail = out
                            dtboStatus = out.lineSequence().firstOrNull { it.isNotBlank() }?.take(24) ?: "已下发，重启生效"
                            dtboBusy = false
                        }
                    },
                    onDismiss = { showDtboConfirm = false }
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
                        .clickable { saveBattery("cycle", cycleTarget) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                )
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = "清除伪装",
                modifier = Modifier
                    .clickable { saveBattery("cycle", "clear") }
                    .padding(vertical = 4.dp),
                style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
            )
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("电池电压")
            ValueRow("满电电压", "4300~4700 mV · ${status.fvV.ifBlank { "未设置" }}")
            GlassInput(fullVoltage, { fullVoltage = it }, "输入满电电压 mV", numeric = true)
            Spacer(Modifier.height(8.dp))
            ToggleLine(
                backdrop,
                "强制覆盖原厂上限",
                "目标值超过原厂上限时开启",
                forceLimit,
                onCheckedChange = {
                    if (it) {
                        showVoltForceConfirm = true
                    } else {
                        forceLimit = false
                        scope.launch { bridge.exec(Engine.batteryRaw("volt", "force", "off")) }
                    }
                }
            )
            if (showVoltForceConfirm) {
                Spacer(Modifier.height(6.dp))
                ConfirmCard(
                    backdrop,
                    title = "改写电池满电电压上限",
                    detail = "将强制覆盖原厂满电电压上限，可能加速电池老化或绕过硬件保护，存在硬件风险。确认后下发 volt force on，再通过「应用电压」写入目标值。",
                    confirmText = "确认开启",
                    onConfirm = {
                        showVoltForceConfirm = false
                        forceLimit = true
                        scope.launch { bridge.exec(Engine.batteryRaw("volt", "force", "on")) }
                    },
                    onDismiss = { showVoltForceConfirm = false }
                )
            }
            ValueRow("关机电压", "2800~3500 mV · ${status.termV.ifBlank { "未设置" }}")
            GlassInput(shutdownVoltage, { shutdownVoltage = it }, "输入关机电压 mV", numeric = true)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    backdrop,
                    "应用电压",
                    {
                        scope.launch {
                            bridge.exec(Engine.batteryRaw("volt", "force", if (forceLimit) "on" else "off"))
                            bridge.exec(Engine.batteryRaw("volt", "fv", fullVoltage))
                            bridge.exec(Engine.batteryRaw("volt", "term", shutdownVoltage))
                        }
                    },
                    Modifier.weight(1f)
                )
                ActionButton(
                    backdrop,
                    "关闭电压",
                    { scope.launch { bridge.exec(Engine.batteryRaw("volt", "off")) } },
                    Modifier.weight(1f),
                    secondary = true
                )
            }
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "风险提示：电池老化 / 硬件保护 / 生效条件 / 还原说明。",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
            )
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
