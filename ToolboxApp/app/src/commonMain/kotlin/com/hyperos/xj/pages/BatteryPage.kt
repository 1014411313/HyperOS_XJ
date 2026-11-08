package com.hyperos.xj.pages

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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var dtboStatus by remember { mutableStateOf("由模块安装器处理") }
    var cycleTarget by remember { mutableStateOf(status.cycleFake) }
    var fullVoltage by remember { mutableStateOf(status.fvV) }
    var forceLimit by remember { mutableStateOf(status.fvForce) }
    var shutdownVoltage by remember { mutableStateOf(status.termV) }

    fun saveBattery(key: String, value: String) {
        scope.launch { bridge.exec(Engine.battery(key, value)) }
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
            Spacer(Modifier.height(6.dp))
            ActionButton(backdrop, "在线下载当前手机 DTBO", { dtboStatus = "DTBO 引擎尚未接入" }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "本机 DTBO 信息 / 刷入流程占位",
                style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
            )
            Spacer(Modifier.height(8.dp))
            ActionButton(backdrop, "确认刷入，重启生效", { dtboStatus = "DTBO 引擎尚未接入" }, Modifier.fillMaxWidth(), danger = true)
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
                    forceLimit = it
                    scope.launch { bridge.exec(Engine.batteryRaw("volt", "force", if (it) "on" else "off")) }
                }
            )
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
