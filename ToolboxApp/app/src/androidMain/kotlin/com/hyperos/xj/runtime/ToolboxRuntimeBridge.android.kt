package com.hyperos.xj.runtime

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AndroidToolboxBridge : ToolboxRuntimeBridge {
    override suspend fun readStatus(): DeviceStatus = withContext(Dispatchers.IO) {
        val raw = runAsRoot(Engine.status())
        val map = if (raw.trimStart().startsWith("{")) parseFlatJson(raw) else emptyMap()
        if (map.isEmpty()) {
            val hint = raw.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Root 不可用"
            DeviceStatus(status = "未连接", environment = hint)
        } else {
            mapStatus(map)
        }
    }

    override suspend fun readThermalSettings(): ThermalSettings = withContext(Dispatchers.IO) {
        mapThermal(parseKv(runAsRoot(Engine.settingsDump())))
    }

    override suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        runAsRoot(command)
    }

    private fun runAsRoot(command: String): String {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            output.trim()
        } catch (t: Throwable) {
            t.message ?: "error"
        }
    }
}

private fun num(map: Map<String, String>, key: String): Double =
    map[key]?.toDoubleOrNull() ?: 0.0

private fun asInt(map: Map<String, String>, key: String): Int = num(map, key).toInt()

private fun asBool(map: Map<String, String>, key: String, def: Boolean = false): Boolean =
    when (map[key]) {
        null -> def
        "true", "1", "on", "yes" -> true
        else -> false
    }

private fun zhStatus(raw: String): String = when (raw) {
    "Charging" -> "充电中"
    "Full" -> "满电"
    "Discharging" -> "放电中"
    "Not charging" -> "未充电"
    "Unknown", "" -> "未知"
    else -> raw
}

private fun modeLabel(mode: String): String = when (mode) {
    "default" -> "系统默认"
    "unlimited" -> "无限制"
    "level_1" -> "一档"
    "level_2" -> "二档"
    "level_3" -> "三档"
    "level_4" -> "四档"
    "level_5" -> "五档"
    "bypass" -> "旁路供电"
    "off" -> "已关闭"
    "disabled" -> "已禁用"
    else -> mode.ifBlank { "系统默认" }
}

private fun mapStatus(map: Map<String, String>): DeviceStatus {
    val tempC = (num(map, "temp") / 10.0).toFloat()
    val currUa = num(map, "curr")
    val packUv = num(map, "volt")
    val cellUv = num(map, "cell").takeIf { it > 0 } ?: packUv

    val currentMa = (currUa / 1000.0).toInt()
    val voltageMv = (cellUv / 1000.0).toInt()
    val powerW = (abs(currUa) / 1000.0 * packUv / 1_000_000_000.0).toFloat()

    val fullMah = (num(map, "full") / 1000.0).toInt()
    val designMah = (num(map, "design") / 1000.0).toInt()
    val health = if (designMah > 0) fullMah * 100 / designMah else 0
    val soh = map["dt|电池寿命(SOH)"]?.toDoubleOrNull()?.toInt() ?: 0

    val remainingRaw = map["dt|剩余容量(RM)"]?.toDoubleOrNull() ?: 0.0
    val remainingMah = if (remainingRaw > 20_000) (remainingRaw / 1000.0).toInt() else remainingRaw.toInt()

    val thermalZones = map.entries
        .filter { it.key.startsWith("tz|") }
        .mapNotNull { entry ->
            val value = entry.value.toFloatOrNull() ?: return@mapNotNull null
            if (abs(value) > 1000f) value / 1000f else value
        }
        .take(8)

    val protocol = map["dt|快充类型"] ?: map["dt|实际充电器类型"] ?: map["usb_type"] ?: "--"
    val powerTier = map["power_tier"] ?: "inf"

    return DeviceStatus(
        soc = asInt(map, "cap"),
        status = zhStatus(map["status"] ?: ""),
        protocol = protocol.ifBlank { "--" },
        tempC = tempC,
        currentMa = currentMa,
        voltageMv = voltageMv,
        powerW = powerW,
        fullSpeed = powerTier == "inf",
        remainingMah = remainingMah,
        fullMah = fullMah,
        designMah = designMah,
        health = health,
        soh = soh,
        cycles = asInt(map, "cycle").takeIf { it > 0 } ?: asInt(map, "cycr"),
        fakeCycles = map["cycle_fake"]?.toIntOrNull() ?: 0,
        thermalZones = thermalZones,
        gear = modeLabel(map["thermal_mode"] ?: "default"),
        model = map["device_name"] ?: "HyperOS XJ",
        moduleVersion = map["module_version"] ?: "--",
        kernelVersion = map["kernel"] ?: "",
        systemVersion = map["os_version"] ?: "--",
        environment = "Root · 模块在线",
        date = map["date"] ?: "",
        deviceCode = map["device_code"] ?: "",
        osVersion = map["os_version"] ?: "",
        baseFirmware = map["base_firmware"] ?: "",
        thermalMode = map["thermal_mode"] ?: "default",
        thermalDefault = map["thermal_default"] ?: "default",
        powerTier = powerTier,
        powerApplied = map["power_applied"] ?: "",
        unlock = asBool(map, "unlock", true),
        freepps = asBool(map, "freepps", false),
        cloudGuard = asBool(map, "cloud_guard", true),
        aplEnable = asBool(map, "apl_enable", false),
        cycleFake = map["cycle_fake"] ?: "",
        fvV = map["fv_v"] ?: "",
        termV = map["term_v"] ?: "",
        fvForce = asBool(map, "fv_force", false),
        bypassManual = asBool(map, "bypass_manual", false),
        rearScreen = asBool(map, "rearscreen", false),
        rearScreenSupported = asBool(map, "rearscreen_supported", true),
    )
}

private fun mapThermal(map: Map<String, String>): ThermalSettings = ThermalSettings(
    enabled = asBool(map, "enabled", true),
    defaultMode = map["default_mode"] ?: "default",
    chargeMode = asBool(map, "charge_mode", true),
    chargeLevel = map["charge_level"] ?: "unlimited",
    sleepLevel = map["sleep_level"] ?: "level_1",
    timeStart = map["time_start"]?.toIntOrNull() ?: 0,
    timeEnd = map["time_end"]?.toIntOrNull() ?: 0,
    timeLevel = map["time_level"] ?: "level_1",
    gameMode = asBool(map, "game_mode", true),
    gameLevel = map["game_level"] ?: "unlimited",
    gameApps = map["game_apps"] ?: "",
    gameFps = map["game_fps"]?.toIntOrNull() ?: 0,
    gameBypass = asBool(map, "game_bypass", false),
    bypassTempOn = map["bypass_temp_on"]?.toIntOrNull() ?: 0,
    bypassTempOff = map["bypass_temp_off"]?.toIntOrNull() ?: 0,
    bypassLevel = map["bypass_level"]?.toIntOrNull() ?: 0,
    bypassCurrent = map["bypass_current"]?.toIntOrNull() ?: 500_000,
    hyperos4 = asBool(map, "hyperos4", false),
    noHitempStop = asBool(map, "no_hitemp_stop", false),
    chargeGov = asBool(map, "charge_gov", true),
    powerPlanOn = asBool(map, "power_plan_on", false),
    powerPlanStart = map["power_plan_start"]?.toIntOrNull() ?: 23,
    powerPlanEnd = map["power_plan_end"]?.toIntOrNull() ?: 7,
    powerPlanMode = map["power_plan_mode"] ?: "slow",
)

actual fun createToolboxBridge(): ToolboxRuntimeBridge = AndroidToolboxBridge()
