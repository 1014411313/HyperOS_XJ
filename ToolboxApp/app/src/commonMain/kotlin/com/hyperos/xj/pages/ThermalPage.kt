package com.hyperos.xj.pages

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.runtime.DeviceStatus
import com.hyperos.xj.runtime.Engine
import com.hyperos.xj.runtime.ThermalSettings
import com.hyperos.xj.runtime.ToolboxRuntimeBridge
import com.hyperos.xj.runtime.installedGameCandidates
import com.hyperos.xj.ui.ActionButton
import com.hyperos.xj.ui.ChipSelector
import com.hyperos.xj.ui.CollapsibleCard
import com.hyperos.xj.ui.GlassCard
import com.hyperos.xj.ui.GlassInput
import com.hyperos.xj.ui.LocalAccentColor
import com.hyperos.xj.ui.SectionLabel
import com.hyperos.xj.ui.SliderField
import com.hyperos.xj.ui.LocalPageInsets
import com.hyperos.xj.ui.ToggleLine
import com.hyperos.xj.ui.XJ
import com.hyperos.xj.ui.showToast
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val gearOptions = listOf(
    "系统默认", "无限制",
    "一档 ≤30℃", "二档 ≤35℃", "三档 ≤40℃", "四档 ≤45℃", "五档 ≤50℃"
)

@Composable
fun ThermalPage(
    backdrop: Backdrop,
    bridge: ToolboxRuntimeBridge,
    status: DeviceStatus
) {
    val accent = LocalAccentColor.current
    val scope = rememberCoroutineScope()
    var normalGear by remember { mutableIntStateOf(0) }
    var chargeEnabled by remember { mutableStateOf(true) }
    var chargeScreenOn by remember { mutableIntStateOf(0) }
    var chargeScreenOff by remember { mutableIntStateOf(0) }
    var chargeStartHour by remember { mutableStateOf("0") }
    var chargeEndHour by remember { mutableStateOf("0") }
    var chargeTimedGear by remember { mutableIntStateOf(2) }
    var gameEnabled by remember { mutableStateOf(true) }
    var gameGear by remember { mutableIntStateOf(1) }
    var refreshRate by remember { mutableStateOf("0") }
    var gameApps by remember { mutableStateOf(emptyList<String>()) }
    var newPackage by remember { mutableStateOf("") }
    var tripleSpeed by remember { mutableStateOf(false) }
    var planEnabled by remember { mutableStateOf(false) }
    var planStart by remember { mutableStateOf("23") }
    var planEnd by remember { mutableStateOf("07") }
    var planWatts by remember { mutableFloatStateOf(27f) }
    var manualBypass by remember { mutableStateOf(status.bypassManual) }
    var gameBypass by remember { mutableStateOf(false) }
    var bypassTempOn by remember { mutableStateOf("0") }
    var bypassTempOff by remember { mutableStateOf("0") }
    var bypassSocOn by remember { mutableStateOf("0") }
    var engineEnabled by remember { mutableStateOf(true) }
    var noHitempStop by remember { mutableStateOf(false) }
    var chargeGov by remember { mutableStateOf(true) }
    var showAppPicker by remember { mutableStateOf(false) }
    var appsLoaded by remember { mutableStateOf(false) }
    var appsLoading by remember { mutableStateOf(false) }
    var appCandidates by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var loaded by remember { mutableStateOf(false) }

    fun save(key: String, value: String, title: String? = null) {
        scope.launch {
            val result = bridge.execResult(Engine.setting(key, value))
            if (title != null) {
                showToast(
                    when {
                        !result.success -> "失败：$title"
                        value == "true" -> "$title 已开启"
                        value == "false" -> "$title 已关闭"
                        else -> "$title 已保存"
                    }
                )
            }
        }
    }

    fun applySettings(s: ThermalSettings) {
        normalGear = gearIndex(s.defaultMode)
        chargeEnabled = s.chargeMode
        chargeScreenOn = gearIndex(s.chargeLevel)
        chargeScreenOff = gearIndex(s.sleepLevel)
        chargeStartHour = s.timeStart.toString().padStart(2, '0')
        chargeEndHour = s.timeEnd.toString().padStart(2, '0')
        chargeTimedGear = gearIndex(s.timeLevel)
        gameEnabled = s.gameMode
        gameGear = gearIndex(s.gameLevel)
        refreshRate = s.gameFps.toString()
        gameApps = s.gameApps.split('|').filter { it.isNotBlank() }
        tripleSpeed = s.hyperos4
        planEnabled = s.powerPlanOn
        planStart = s.powerPlanStart.toString().padStart(2, '0')
        planEnd = s.powerPlanEnd.toString().padStart(2, '0')
        planWatts = when (s.powerPlanMode) {
            "inf" -> 120f
            "fast" -> 40f
            "mid" -> 25f
            "slow" -> 10f
            else -> s.powerPlanMode.toFloatOrNull()?.coerceIn(5f, 120f) ?: 27f
        }
        gameBypass = s.gameBypass
        bypassTempOn = s.bypassTempOn.toString()
        bypassTempOff = s.bypassTempOff.toString()
        bypassSocOn = s.bypassLevel.toString()
        engineEnabled = s.enabled
        noHitempStop = s.noHitempStop
        chargeGov = s.chargeGov
    }

    LaunchedEffect(Unit) {
        bridge.cachedThermalSettings()?.let {
            applySettings(it)
            loaded = true
        }
        val s = bridge.readThermalSettings()
        applySettings(s)
        loaded = true
    }

    LaunchedEffect(planWatts, loaded) {
        if (!loaded) return@LaunchedEffect
        delay(400)
        save("power_plan_mode", if (planWatts >= 119.5f) "inf" else planWatts.toInt().toString())
    }

    Box(Modifier.fillMaxSize()) {
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
            if (!loaded) {
                GlassCard(backdrop, Modifier.fillMaxWidth()) {
                    BasicText(
                        text = "正在读取设置…",
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 13.sp)
                    )
                }
            } else {
                BasicText(
                    text = "高温保护备注：高温自动降充电功率与温度回落恢复默认开启，这里只做说明，不提供独立功能卡片。",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                )

                GlassCard(backdrop, Modifier.fillMaxWidth()) {
                    SectionLabel("引擎控制")
                    ToggleLine(
                        backdrop = backdrop,
                        title = "引擎总开关",
                        checked = engineEnabled,
                        onCheckedChange = { engineEnabled = it; save("enabled", it.toString(), "引擎总开关") }
                    )
                    ToggleLine(
                        backdrop = backdrop,
                        title = "解除系统高温停充",
                        checked = noHitempStop,
                        onCheckedChange = { noHitempStop = it; save("no_hitemp_stop", it.toString(), "解除高温停充") }
                    )
                    ToggleLine(
                        backdrop = backdrop,
                        title = "充电温控联动",
                        checked = chargeGov,
                        onCheckedChange = {
                            chargeGov = it
                            scope.launch {
                                val result = bridge.execResult(Engine.chargeGov(it))
                                showToast(
                                    when {
                                        !result.success -> "失败：充电温控联动"
                                        it -> "充电温控联动 已开启"
                                        else -> "充电温控联动 已关闭"
                                    }
                                )
                            }
                        }
                    )
                }

                GlassCard(backdrop, Modifier.fillMaxWidth()) {
                    SectionLabel("普通场景温控")
                    BasicText(
                        text = "非充电、非游戏时生效；充电中会被充电档位覆盖。档位温度上限同时作为充电功率降档线",
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipSelector(
                        backdrop = backdrop,
                        title = "默认温控档位",
                        options = gearOptions,
                        selectedIndex = normalGear,
                        onSelect = { normalGear = it; save("default_mode", gearMode(it), "默认温控档位") }
                    )
                }

                CollapsibleCard(
                    backdrop = backdrop,
                    title = "充电场景",
                    subtitle = "亮屏充电档位",
                    initiallyExpanded = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ToggleLine(
                        backdrop = backdrop,
                        title = "启用充电场景",
                        checked = chargeEnabled,
                        onCheckedChange = { chargeEnabled = it; save("charge_mode", it.toString(), "充电场景") }
                    )
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        text = "充电且亮屏时生效，覆盖默认档位",
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipSelector(
                        backdrop = backdrop,
                        title = "亮屏充电档位",
                        options = gearOptions,
                        selectedIndex = chargeScreenOn,
                        onSelect = { chargeScreenOn = it; save("charge_level", gearMode(it), "亮屏充电档位") }
                    )
                    Spacer(Modifier.height(12.dp))
                    BasicText(
                        text = "充电且息屏时生效",
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipSelector(
                        backdrop = backdrop,
                        title = "息屏充电档位",
                        options = gearOptions,
                        selectedIndex = chargeScreenOff,
                        onSelect = { chargeScreenOff = it; save("sleep_level", gearMode(it), "息屏充电档位") }
                    )
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("定时切换")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassInput(chargeStartHour, { chargeStartHour = it; save("time_start", it.ifBlank { "0" }) }, "开始时", Modifier.weight(1f), numeric = true)
                        Spacer(Modifier.width(8.dp))
                        GlassInput(chargeEndHour, { chargeEndHour = it; save("time_end", it.ifBlank { "0" }) }, "结束时", Modifier.weight(1f), numeric = true)
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            text = gearOptions[chargeTimedGear],
                            modifier = Modifier
                                .width(116.dp)
                                .padding(vertical = 12.dp),
                            style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        text = "在设定时段内生效，覆盖亮屏档位",
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipSelector(
                        backdrop = backdrop,
                        title = "时段档位",
                        options = gearOptions,
                        selectedIndex = chargeTimedGear,
                        onSelect = { chargeTimedGear = it; save("time_level", gearMode(it), "时段档位") }
                    )
                }

                CollapsibleCard(
                    backdrop = backdrop,
                    title = "游戏场景",
                    subtitle = "游戏温控档位",
                    initiallyExpanded = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ToggleLine(
                        backdrop = backdrop,
                        title = "启用游戏场景",
                        checked = gameEnabled,
                        onCheckedChange = { gameEnabled = it; save("game_mode", it.toString(), "游戏场景") }
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipSelector(
                        backdrop = backdrop,
                        title = "游戏温控档位",
                        options = gearOptions,
                        selectedIndex = gameGear,
                        onSelect = { gameGear = it; save("game_level", gearMode(it), "游戏温控档位") }
                    )
                    Spacer(Modifier.height(12.dp))
                    BasicText(
                        text = "锁定刷新率，0 表示关闭",
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
                    )
                    Spacer(Modifier.height(6.dp))
                    GlassInput(refreshRate, { refreshRate = it; save("game_fps", it.ifBlank { "0" }) }, "输入刷新率", numeric = true)
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("游戏应用列表")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        gameApps.forEachIndexed { index, app ->
                            BasicText(
                                text = "$app ×",
                                modifier = Modifier
                                    .clickableChip(backdrop, false) {
                                        gameApps = gameApps.filterIndexed { i, _ -> i != index }
                                        save("game_apps", gameApps.joinToString("|"), "游戏应用列表")
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                style = TextStyle(color = XJ.TextPrimary, fontSize = 12.sp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassInput(newPackage, { newPackage = it }, "输入包名", Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            text = "添加",
                            modifier = Modifier
                                .clickableChip(backdrop, false) {
                                    if (newPackage.isNotBlank()) {
                                        gameApps = gameApps + newPackage
                                        newPackage = ""
                                        save("game_apps", gameApps.joinToString("|"), "游戏应用列表")
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        text = if (appsLoading && !showAppPicker) "正在读取应用列表…" else "从已安装应用选择",
                        modifier = Modifier
                            .clickableChip(backdrop, false) {
                                if (!appsLoaded && !appsLoading) {
                                    appsLoading = true
                                    scope.launch {
                                        appCandidates = installedGameCandidates()
                                        appsLoaded = true
                                        appsLoading = false
                                    }
                                }
                                showAppPicker = true
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        style = TextStyle(color = XJ.TextPrimary, fontSize = 13.sp)
                    )
                }

                GlassCard(backdrop, Modifier.fillMaxWidth()) {
                    SectionLabel("游戏加速")
                    ToggleLine(
                        backdrop = backdrop,
                        title = "HyperOS 游戏三倍速",
                        subtitle = "开启后立即生效，开机后自动执行",
                        checked = tripleSpeed,
                        onCheckedChange = { tripleSpeed = it; save("hyperos4", it.toString(), "游戏三倍速") }
                    )
                }

                GlassCard(backdrop, Modifier.fillMaxWidth()) {
                    SectionLabel("自动任务")
                    ToggleLine(
                        backdrop = backdrop,
                        title = "定时充电计划",
                        subtitle = "指定时间段自动切换功率",
                        checked = planEnabled,
                        onCheckedChange = { planEnabled = it; save("power_plan_on", it.toString(), "定时充电计划") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassInput(planStart, { planStart = it; save("power_plan_start", it.ifBlank { "0" }) }, "开始", Modifier.weight(1f), numeric = true)
                        Spacer(Modifier.width(8.dp))
                        BasicText("到", style = TextStyle(color = XJ.TextSecondary, fontSize = 13.sp))
                        Spacer(Modifier.width(8.dp))
                        GlassInput(planEnd, { planEnd = it; save("power_plan_end", it.ifBlank { "0" }) }, "结束", Modifier.weight(1f), numeric = true)
                    }
                    Spacer(Modifier.height(14.dp))
                    SliderField(
                        backdrop = backdrop,
                        title = "计划功率",
                        valueText = if (planWatts >= 120f) "全速" else "${planWatts.toInt()} W",
                        valueRange = 5f..120f,
                        value = { planWatts },
                        onValueChange = { planWatts = it }
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        BasicText("5W", style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp))
                        BasicText("全速", style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp))
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        text = "时段内手动改过功率档后不再接管。",
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                }

                GlassCard(backdrop, Modifier.fillMaxWidth()) {
                    SectionLabel("旁路供电")
                    ToggleLine(
                        backdrop = backdrop,
                        title = "手动旁路",
                        subtitle = "立即生效",
                        checked = manualBypass,
                        onCheckedChange = {
                            manualBypass = it
                            scope.launch {
                                val result = bridge.execResult(Engine.bypass(it))
                                showToast(
                                    when {
                                        !result.success -> "失败：手动旁路"
                                        it -> "手动旁路 已开启"
                                        else -> "手动旁路 已关闭"
                                    }
                                )
                            }
                        }
                    )
                    ToggleLine(
                        backdrop = backdrop,
                        title = "游戏时旁路供电",
                        subtitle = "检测到游戏应用时启用",
                        checked = gameBypass,
                        onCheckedChange = { gameBypass = it; save("game_bypass", it.toString(), "游戏时旁路供电") }
                    )
                    Spacer(Modifier.height(4.dp))
                    BasicText("温度开启阈值（0 = 关闭）", style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp))
                    GlassInput(bypassTempOn, { bypassTempOn = it; save("bypass_temp_on", it.ifBlank { "0" }) }, "温度开启阈值", numeric = true)
                    Spacer(Modifier.height(8.dp))
                    BasicText("温度关闭阈值（回落温度）", style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp))
                    GlassInput(bypassTempOff, { bypassTempOff = it; save("bypass_temp_off", it.ifBlank { "0" }) }, "温度关闭阈值", numeric = true)
                    Spacer(Modifier.height(8.dp))
                    BasicText("电量开启阈值（电量大于等于）", style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp))
                    GlassInput(bypassSocOn, { bypassSocOn = it; save("bypass_level", it.ifBlank { "0" }) }, "电量开启阈值", numeric = true)
                }
            }
        }
        if (showAppPicker) {
            GameAppPickerOverlay(
                backdrop = backdrop,
                candidates = appCandidates,
                selected = gameApps,
                loading = appsLoading,
                onToggle = { pkg ->
                    gameApps = if (pkg in gameApps) gameApps - pkg else gameApps + pkg
                    save("game_apps", gameApps.joinToString("|"), "游戏应用列表")
                },
                onDismiss = { showAppPicker = false }
            )
        }
    }
}

@Composable
private fun GameAppPickerOverlay(
    backdrop: Backdrop,
    candidates: List<Pair<String, String>>,
    selected: List<String>,
    loading: Boolean,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = LocalAccentColor.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(candidates, query) {
        val q = query.trim()
        if (q.isEmpty()) candidates
        else candidates.filter { (pkg, label) ->
            label.contains(q, ignoreCase = true) || pkg.contains(q, ignoreCase = true)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss)
    ) {
        GlassCard(
            backdrop,
            Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .align(Alignment.Center)
                .clickable(onClick = {})
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = "选择游戏应用",
                    modifier = Modifier.weight(1f),
                    style = TextStyle(color = XJ.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                )
                ActionButton(backdrop, "关闭", onDismiss, secondary = true)
            }
            Spacer(Modifier.height(10.dp))
            GlassInput(query, { query = it }, "搜索应用名或包名", Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            when {
                loading -> BasicText(
                    text = "正在读取应用列表…",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
                )
                candidates.isEmpty() -> BasicText(
                    text = "未读取到应用列表",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
                )
                filtered.isEmpty() -> BasicText(
                    text = "没有匹配的应用",
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
                )
                else -> LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.first }) { (pkg, label) ->
                        val isSelected = pkg in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f))
                                .clickable { onToggle(pkg) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    text = label,
                                    style = TextStyle(
                                        color = if (isSelected) accent else XJ.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                )
                                BasicText(
                                    text = pkg,
                                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                                )
                            }
                            if (isSelected) {
                                BasicText(
                                    text = "已选",
                                    style = TextStyle(color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun gearMode(index: Int): String = when (index) {
    0 -> "default"
    1 -> "unlimited"
    2 -> "level_1"
    3 -> "level_2"
    4 -> "level_3"
    5 -> "level_4"
    6 -> "level_5"
    else -> "default"
}

private fun gearIndex(mode: String): Int = when (mode) {
    "default" -> 0
    "unlimited" -> 1
    "level_1" -> 2
    "level_2" -> 3
    "level_3" -> 4
    "level_4" -> 5
    "level_5" -> 6
    else -> 0
}

@Composable
private fun Modifier.clickableChip(
    backdrop: Backdrop,
    selected: Boolean,
    onClick: () -> Unit
): Modifier {
    val accent = LocalAccentColor.current
    return this.then(
        Modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { com.kyant.shapes.Capsule() },
                effects = {
                    vibrancy()
                    blur(6f.dp.toPx())
                },
                shadow = null,
                onDrawSurface = {
                    drawRect(
                        if (selected) accent.copy(alpha = 0.82f)
                        else Color.White.copy(alpha = 0.08f)
                    )
                }
            )
            .clickable(onClick = onClick)
    )
}
