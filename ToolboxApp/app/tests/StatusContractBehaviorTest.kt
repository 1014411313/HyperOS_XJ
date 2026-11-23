
private val controlValues = mapOf(
    "thermal_mode" to "level_2", "thermal_default" to "unlimited", "power_tier" to "inf",
    "unlock" to "0", "freepps" to "1", "cloud_guard" to "0", "apl_enable" to "1",
    "cycle_fake" to "", "term_v" to "", "bypass_manual" to "0",
    "rearscreen" to "0", "rearscreen_supported" to "1", "charge_gov" to "1",
)
private val liveCore = mapOf(
    "cap" to "55", "status" to "Charging", "temp" to "300", "volt" to "4000000", "curr" to "-1000000",
)
private var failed = 0
private fun verify(ok: Boolean, name: String) {
    println("${if (ok) "PASS" else "FAIL"} $name")
    if (!ok) failed++
}
private fun flag(status: DeviceStatus, name: String): Boolean? =
    status.javaClass.methods.firstOrNull { it.name == "get$name" }?.invoke(status) as? Boolean

fun main() = runBlocking {
    val empty = mapStatus(emptyMap())
    verify(empty.thermalMode.isEmpty() && empty.gear == "未知", "无控制源温控档位明确未知")
    verify(flag(empty, "ControlConfigLoaded") == false, "无控制源不算配置已加载")
    verify(flag(mapStatus(controlValues), "ControlConfigLoaded") == true, "完整合法配置已加载（允许空循环和电压）")
    for (key in controlValues.keys) {
        verify(flag(mapStatus(controlValues - key), "ControlConfigLoaded") == false, "缺控制字段 $key 不得加载")
    }
    for ((key, bad) in listOf("unlock" to "oops", "power_tier" to "garbage", "thermal_default" to "bad")) {
        verify(flag(mapStatus(controlValues + (key to bad)), "ControlConfigLoaded") == false, "非法控制字段 $key 不得加载")
    }
    for ((key, bad) in listOf("term_v" to "2700", "term_v" to "nan", "cycle_fake" to "-1")) {
        val legacy = mapStatus(controlValues + (key to bad))
        verify(flag(legacy, "ControlConfigLoaded") == true, "历史可纠错字段 $key=$bad 不隐藏控制")
        verify(flag(mapStatus(controlValues + (key to bad) - "unlock"), "ControlConfigLoaded") == false, "历史值可纠错但关键布尔缺失仍禁止操作")
    }
    verify(flag(mapStatus(controlValues + ("power_thermal_ma" to "9500") + ("power_thermal_active" to "0")), "PowerThermalActive") == false,
        "温控目标存在但未小于请求不显示降流")
    verify(flag(mapStatus(controlValues + ("power_thermal_active" to "1") + ("apl_target_ma" to "8000")), "PowerThermalActive") == true,
        "显式温控降流信号透传")
    verify(mapStatus(controlValues + ("apl_target_ma" to "8000")).aplTargetMa == 8000, "APL 目标透传")
    val zones = mapStatus(mapOf("tz|conn" to "-273000", "tz|battery" to "-5000", "tz|skin" to "-500", "tz|charger" to "35000", "tz|bad" to "NaN"))
    verify(zones.thermalZones.filter { it.first == "conn" || it.first == "bad" }.all { it.second.isNaN() } &&
        zoneItems(zones.thermalZones).count { it.first == "未知" } == 2, "温区无效哨兵及非有限数显示未知且保留身份")
    verify(zones.thermalZones.contains("battery" to -5f) && zones.thermalZones.contains("skin" to -0.5f), "原始毫℃正常负温及负小数保留")
    verify(zones.thermalZones.contains("charger" to 35f), "原始毫℃正常正温")
    verify(fmt1(-0.5f) == "-0.5" && fmt1(-1.5f) == "-1.5", "负小数符号格式正确")
    verify(pickLiveSource(false, liveCore, controlValues) == LiveSource.ROOT_WITH_LIVE, "完整 live 仍与控制 Root 基底合并")
    liveValues = liveCore
    val reader = ReadHarness()
    reader.rootValues = controlValues + ("cap" to "1")
    val status = reader.readStatus()
    verify(reader.rootCalls == 1 && status.thermalMode == "level_2" && status.soc == 55, "无快照完整 live 也读取控制基底且实时读数优先")
    reader.rootValues = null
    val unknown = reader.readStatus()
    verify(unknown.thermalMode.isEmpty() && flag(unknown, "ControlConfigLoaded") == false && unknown.soc == 55, "Root 失败仍显示 live 且控制未知")
    val nowTs = System.currentTimeMillis() / 1000
    fun snapshot(values: Map<String, String>) = values.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
    snapshotRaw = snapshot(controlValues + liveCore + ("ts" to (nowTs - 20).toString()))
    val staleReader = ReadHarness().also { it.rootValues = controlValues + ("thermal_mode" to "level_3") }
    verify(staleReader.readStatus().thermalMode == "level_3" && staleReader.rootCalls == 1, "过期快照和完整live必须回退Root控制字段")
    snapshotRaw = snapshot(controlValues + liveCore + ("ts" to nowTs.toString()))
    val lateReader = ReadHarness().also {
        it.minimumSnapshotTs = nowTs
        it.rootValues = controlValues + ("thermal_mode" to "level_4")
    }
    verify(lateReader.readStatus().thermalMode == "level_4" && lateReader.rootCalls == 1, "保存后旧快照晚到不能覆盖新控制基底")
    snapshotRaw = snapshot(controlValues + liveCore + ("ts" to (nowTs + 1).toString()))
    verify(lateReader.readStatus().thermalMode == "level_2" && lateReader.rootCalls == 1, "保存后的更新快照恢复为有效控制源")
    snapshotRaw = ""
    val battery = BatteryHarness(this)
    battery.saveBattery("unlock", "on", "解容") { battery.saved = true }
    verify(!battery.saved && battery.batteryBusy, "保存未完成前开关保持旧值")
    delay(30)
    verify(!battery.saved && !battery.batteryBusy && toast == "失败：解容", "保存失败保持旧值并反馈")
    battery.bridge.response = ExecResult("saved=on", 0)
    battery.saveBattery("unlock", "on", "解容") { battery.saved = true }
    delay(30)
    verify(battery.saved && !battery.batteryBusy, "保存成功才提交开关")
    val voltage = BatteryHarness(this)
    for (invalid in listOf("2700", "2799", "3501", "", "abc", "3000.5")) {
        voltage.shutdownVoltage = invalid
        val before = voltage.bridge.commands.size
        voltage.applyVoltage()
        delay(30)
        verify(voltage.bridge.commands.size == before && toast.contains("2800") && toast.contains("3500"), "非法电压 $invalid 保存前拒绝并提示范围")
    }
    for (valid in listOf("2800", "3000", "3500")) {
        voltage.shutdownVoltage = valid
        voltage.applyVoltage()
        delay(30)
        verify(voltage.bridge.commands.lastOrNull() == Engine.batteryRaw("volt", "term", valid), "合法电压边界 $valid 允许发送")
    }
    voltage.shutdownVoltage = "2700"
    voltage.statusReady = mapStatus(controlValues + ("term_v" to "2700")).controlConfigLoaded
    voltage.disableVoltage()
    delay(30)
    verify(voltage.bridge.commands.lastOrNull() == Engine.batteryRaw("volt", "off"), "历史2700仍可关闭关机电压")
    val blocked = BatteryHarness(this)
    blocked.statusReady = mapStatus(controlValues - "unlock").controlConfigLoaded
    blocked.shutdownVoltage = "3000"
    blocked.applyVoltage()
    blocked.disableVoltage()
    delay(30)
    verify(blocked.bridge.commands.isEmpty(), "关键布尔缺失不能发送应用或关闭命令")
    val rootCache = RootBaseHarness()
    rootCache.response = ExecResult("{\"thermal_mode\":\"level_2\"}", 1)
    verify(rootCache.rootBaseCached() == null, "Root 非零退出即使带JSON也不得冒充有效状态")
    rootCache.response = ExecResult("{\"thermal_mode\":\"level_2\"}", 0)
    verify(rootCache.rootBaseCached() == null && rootCache.calls == 1, "Root失败也限频，缓存期不重复fork")
    rootCache.rootBaseAt = 0
    verify(rootCache.rootBaseCached()?.get("thermal_mode") == "level_2" && rootCache.calls == 2, "限频到期可以恢复有效控制源")
    leaseFile = File.createTempFile("xj-visible-", ".until")
    visibilityScope = this
    try {
        AppVisibility.visible.value = true
        appVisible()
        delay(100)
        verify(leaseFile.readText().toLong() > System.currentTimeMillis() / 1000, "前台写入有效租约")
        AppVisibility.visible.value = false
        appVisible()
        visibilityJob?.join()
        verify(leaseFile.readText() == "0", "转后台取消旧任务并清零租约")
        AppVisibility.visible.value = true
        appVisible()
        delay(100)
        verify(leaseFile.readText().toLong() > System.currentTimeMillis() / 1000, "重新前台恢复租约")
    } finally {
        visibilityJob?.cancelAndJoin()
        leaseFile.delete()
    }
    check(failed == 0) { "$failed 项状态契约失败" }
    println("ALL PASS App 状态契约")
}
