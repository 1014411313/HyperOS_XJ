package com.hyperos.xj.pages

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.hyperos.xj.ui.CollapsibleCard
import com.hyperos.xj.ui.GlassCard
import com.hyperos.xj.ui.InfoRow
import com.hyperos.xj.ui.LocalAccentColor
import com.hyperos.xj.ui.LocalAppearance
import com.hyperos.xj.ui.LiquidGauge
import com.hyperos.xj.ui.MetricGrid
import com.hyperos.xj.ui.Pill
import com.hyperos.xj.ui.SectionLabel
import com.hyperos.xj.ui.SliderField
import com.hyperos.xj.ui.XJ
import com.hyperos.xj.ui.showToast
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay

@Composable
fun HomePage(
    backdrop: Backdrop,
    bridge: ToolboxRuntimeBridge,
    status: DeviceStatus
) {
    var powerWatts by remember { mutableFloatStateOf(tierToWatts(status.powerTier)) }
    var applying by remember { mutableStateOf(false) }
    var userTouched by remember { mutableStateOf(false) }

    LaunchedEffect(status.powerTier, userTouched) {
        if (!userTouched) powerWatts = tierToWatts(status.powerTier)
    }

    LaunchedEffect(powerWatts, userTouched) {
        if (!userTouched) return@LaunchedEffect
        delay(400)
        applying = true
        val result = bridge.execResult(Engine.power(if (powerWatts >= 119.5f) "inf" else powerWatts.toInt().toString()))
        applying = false
        showToast(
            if (result.success) {
                if (powerWatts >= 119.5f) "充电功率：全速 已应用" else "充电功率 ${powerWatts.toInt()}W 已应用"
            } else {
                "失败：功率写入"
            }
        )
    }
    val accent = LocalAccentColor.current
    val appearance = LocalAppearance.current
    // status 首次真实读取（environment 被替换）后再渲染，避免首帧显示默认值
    val statusReady = status.environment != "检测中"

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!statusReady) {
            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                BasicText(
                    text = "正在读取设备状态…",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 13.sp)
                )
            }
        } else {
            CollapsibleCard(
                backdrop = backdrop,
                title = "实时运行面板",
                initiallyExpanded = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LiquidGauge(
                        progress = status.soc / 100f,
                        powerText = fmt1(status.powerW),
                        charging = status.charging,
                        accent = accent,
                        gaugeSize = 184.dp,
                        animate = !appearance.smoothMode
                    )
                    Spacer(Modifier.height(10.dp))
                    Pill("当前温控档位 · ${status.gear}")
                }

                Spacer(Modifier.height(14.dp))
                SectionLabel("实时状态")
                MetricGrid(
                    listOf(
                        status.status to "状态",
                        status.protocol to "充电协议",
                        "${fmt1(status.voltageMv / 1000f)}V" to "电压",
                        "${fmt1(status.currentMa / 1000f)}A" to "电流",
                        "${fmt1(status.tempC)}℃" to "温度",
                        "${status.remainingMah} mAh" to "剩余容量",
                        "${status.fullMah} mAh" to "满充容量",
                        "${status.designMah} mAh" to "设计容量",
                        "${status.health}%" to "健康度",
                        "${status.soh}%" to "电池寿命",
                        "${status.cycles}" to "循环次数",
                        "${status.fakeCycles}" to "伪装次数",
                    ),
                    columns = 3
                )

                Spacer(Modifier.height(14.dp))
                SectionLabel("温区温度")
                MetricGrid(
                    zoneItems(status.thermalZones),
                    columns = 4
                )
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SliderField(
                    backdrop = backdrop,
                    title = "充电功率",
                    valueText = if (powerWatts >= 120f) "全速" else "${powerWatts.toInt()} W",
                    valueRange = 5f..120f,
                    value = { powerWatts },
                    onValueChange = { powerWatts = it; userTouched = true }
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BasicText("5W", style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp))
                    BasicText("全速", style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp))
                }
                Spacer(Modifier.height(10.dp))
                BasicText(
                    text = when {
                        applying -> "正在写入 ${powerWatts.toInt()}W…"
                        powerWatts >= 119.5f -> "当前功率：全速（等效无限制）"
                        else -> "当前功率：${powerWatts.toInt()}W · 拖动后自动生效"
                    },
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                )
            }

            GlassCard(backdrop, Modifier.fillMaxWidth()) {
                SectionLabel("设备信息")
                InfoRow("模块版本", status.moduleVersion)
                InfoRow("当前日期", status.date)
                InfoRow("设备型号", status.model)
                InfoRow("系统版本", status.systemVersion)
                InfoRow("运行环境", status.environment)
            }
        }
    }
}

private fun fmt1(value: Float): String {
    val scaled = (value * 10f).toInt()
    return "${scaled / 10}.${kotlin.math.abs(scaled % 10)}"
}

/** 温区按部位显示中文名；同名多路时追加序号（如 接口1 / 接口2）。 */
private fun zoneItems(zones: List<Pair<String, Float>>): List<Pair<String, String>> {
    val names = zones.map { zoneLabel(it.first) }
    val total = names.groupingBy { it }.eachCount()
    val seq = mutableMapOf<String, Int>()
    return zones.mapIndexed { index, zone ->
        val name = names[index]
        val label = if ((total[name] ?: 0) > 1) {
            val n = (seq[name] ?: 0) + 1
            seq[name] = n
            "$name$n"
        } else {
            name
        }
        "${fmt1(zone.second)}℃" to label
    }
}

private fun zoneLabel(raw: String): String = when {
    raw.contains("batt", true) -> "电池"
    raw.contains("charger", true) -> "充电口"
    raw.contains("conn", true) -> "接口"
    raw.contains("usb", true) -> "USB"
    raw.contains("skin", true) -> "外壳"
    raw.contains("chg", true) -> "充电"
    else -> raw
}

private fun tierToWatts(tier: String): Float = when (tier) {
    "inf" -> 120f
    "fast" -> 40f
    "mid" -> 25f
    "slow" -> 10f
    else -> tier.toFloatOrNull()?.coerceIn(5f, 120f) ?: 120f
}
