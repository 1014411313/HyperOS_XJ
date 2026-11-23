package com.hyperos.xj.runtime

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicReference

/** 守护每拍写出的状态快照；App 直读免 fork su，过期/缺失/解析失败时回退 root 采样。 */
private const val SHARED_STATUS_STALE_MS = 15_000L
private val engineCommandMutex = Mutex()

/**
 * 读不到的数据源冷却这么久再试：面板 700ms 刷一拍，SELinux 拒绝的 sysfs 节点不能跟着刷 denial，
 * 权限或节点状态变化后最迟一个冷却周期自动恢复。
 */
private const val SOURCE_RETRY_MS = 60_000L

/** 快照文件还没生成时用短冷却，守护起来后很快能读到。 */
private const val MISSING_RETRY_MS = 5_000L

/** root 兜底最多每秒一次，保持首页实时读数。 */
private const val ROOT_STATUS_CACHE_MS = 1_000L

private val sharedStatusFiles = listOf(
    File("/storage/emulated/0/Android/data/com.hyperos.xj/files/status.json"),
    File("/data/local/tmp/xj_status.json"),
)

private class AndroidToolboxBridge : ToolboxRuntimeBridge {
    @Volatile
    private var settingsCache: ThermalSettings? = null

    private val sourceCache = StatusSourceCache()

    @Volatile
    private var rootBaseAt = 0L
    private var rootBase: Map<String, String>? = null
    private var rootHintText: String = "Root 不可用"
    private var deviceInfo: Map<String, String> = emptyMap()
    @Volatile private var statusDirty = false

    private fun withDeviceInfo(map: Map<String, String>): Map<String, String> {
        val keys = setOf("device_name", "device_code", "os_version", "base_firmware", "kernel", "module_version", "date")
        deviceInfo = deviceInfo + map.filter { it.key in keys && it.value.isNotBlank() }
        return map + deviceInfo
    }

    override suspend fun readStatus(): DeviceStatus = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (!AppVisibility.visible.value) return@withContext DeviceStatus()
        val snap = sourceCache.readSnapshot(
            sharedStatusFiles, SHARED_STATUS_STALE_MS, SOURCE_RETRY_MS, MISSING_RETRY_MS,
        ) { raw -> if (raw.trimStart().startsWith("{")) parseFlatJson(raw) else emptyMap() }
        if (statusDirty) {
            val updated = rootBaseCached()
            if (updated != null) {
                statusDirty = false
                return@withContext mapStatus(withDeviceInfo(updated))
            }
        }
        var live = readSysfsLive(sourceCache)
        // 快照发布受整轮守护耗时限制；节点拒绝时用轻量 KV 读取，避免数秒沿用旧电流。
        if (snap != null && !hasLiveCore(live.orEmpty()) && !sourceCache.cooledDown("root-live")) {
            val result = withTimeoutOrNull(2_000L) { readRootStatus(Engine.readLive()) }
            val rootLive = if (result?.success == true) parseKv(result.output) else emptyMap()
            if (rootLive.isNotEmpty()) live = rootLive + live.orEmpty()
            if (!hasLiveCore(rootLive)) sourceCache.markUnreadable("root-live", SOURCE_RETRY_MS)
        }
        // 只有快照和完整实时都拿不到时才读取完整 root 基底；部分实时值不能当 0。
        val needRootBase = snap == null && (live == null || !hasLiveCore(live))
        val rootBase = if (needRootBase) rootBaseCached() else null
        when (pickLiveSource(snap != null, live, rootBase)) {
            LiveSource.SNAPSHOT_WITH_LIVE -> mapStatus(withDeviceInfo(snap.orEmpty() + live.orEmpty()))
            LiveSource.SNAPSHOT -> mapStatus(withDeviceInfo(snap.orEmpty()))
            LiveSource.LIVE -> mapStatus(withDeviceInfo(live.orEmpty()), "面板直读 · 引擎快照不可用")
            LiveSource.ROOT_WITH_LIVE -> mapStatus(withDeviceInfo(rootBase.orEmpty() + live.orEmpty()))
            LiveSource.ROOT -> mapStatus(withDeviceInfo(rootBase.orEmpty()))
            LiveSource.PARTIAL_LIVE -> mapStatus(withDeviceInfo(live.orEmpty()), "面板直读（部分字段） · 引擎快照不可用")
            LiveSource.NONE -> DeviceStatus(status = "未连接", environment = rootHintText)
        }
    }

    private suspend fun rootBaseCached(): Map<String, String>? {
        coroutineContext.ensureActive()
        if (!AppVisibility.visible.value) return null
        val now = System.currentTimeMillis()
        if (now - rootBaseAt < ROOT_STATUS_CACHE_MS) return rootBase
        val raw = readRootStatus().output
        rootBase = (if (raw.trimStart().startsWith("{")) parseFlatJson(raw) else emptyMap()).ifEmpty { null }
        rootHintText = raw.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Root 不可用"
        rootBaseAt = now
        return rootBase
    }

    private suspend fun readRootStatus(command: String = Engine.status()): ExecResult = suspendCancellableCoroutine { continuation ->
        val running = AtomicReference<Process?>()
        continuation.invokeOnCancellation { running.get()?.destroy() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) return@Runnable
            if (!AppVisibility.visible.value) {
                continuation.resume(ExecResult("读取已暂停", -1))
                return@Runnable
            }
            val result = try {
                val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                running.set(process)
                if (!continuation.isActive || !AppVisibility.visible.value) {
                    process.destroy()
                    ExecResult("读取已暂停", -1)
                } else {
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    ExecResult(output.trim(), process.waitFor())
                }
            } catch (t: Exception) {
                ExecResult(t.message ?: "error", -1)
            }
            if (continuation.isActive) continuation.resume(result)
        })
    }

    private fun invalidateRootBase() {
        rootBase = null
        rootBaseAt = 0L
    }

    override fun cachedThermalSettings(): ThermalSettings? = settingsCache

    override suspend fun readThermalSettings(): ThermalSettings = withContext(Dispatchers.IO) {
        settingsCache = null
        val result = runAsRoot(Engine.settingsDump())
        if (!result.success) {
            throw IllegalStateException("读取引擎设置失败（退出码 ${result.exitCode}），请检查 Root 权限和模块状态")
        }
        val settings = parseKv(result.output).filterValues { it.isNotBlank() }
        val required = listOf(
            "enabled", "default_mode", "charge_mode", "charge_level", "sleep_level",
            "time_start", "time_end", "time_level", "game_mode", "game_level",
        )
        val missing = required.filter { settings[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("引擎设置缺少必要字段：${missing.joinToString()}")
        }
        val boolValues = setOf("true", "false", "1", "0", "on", "off", "yes", "no")
        val modes = setOf("default", "unlimited", "level_1", "level_2", "level_3", "level_4", "level_5")
        val valid = listOf("enabled", "charge_mode", "game_mode").all { settings[it] in boolValues } &&
            listOf("default_mode", "charge_level", "sleep_level", "time_level", "game_level").all { settings[it] in modes } &&
            listOf("time_start", "time_end").all { thermalHour(settings[it].orEmpty()) != null } &&
            listOf("power_plan_start", "power_plan_end").all { key ->
                settings[key]?.let { thermalHour(it) != null } ?: true
            }
        if (!valid) throw IllegalStateException("引擎设置的必要字段格式无效")
        mapThermal(settings).also { settingsCache = it }
    }

    override suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        invalidateRootBase()
        try {
            engineCommandMutex.withLock {
                withContext(NonCancellable) { runAsRoot(command) }
            }.also { settingsCache = null }.output
        } finally {
            withContext(Dispatchers.Main) {
                if (!command.startsWith(Engine.build("dtbo")) && command != Engine.status() && command != Engine.settingsDump()) {
                    statusDirty = true
                }
                AppVisibility.statusRevision.intValue++
            }
        }
    }

    override suspend fun execResult(command: String): ExecResult = withContext(Dispatchers.IO) {
        invalidateRootBase()
        try {
            engineCommandMutex.withLock {
                withContext(NonCancellable) { runAsRoot(command) }
            }.also { settingsCache = null }
        } finally {
            withContext(Dispatchers.Main) {
                if (!command.startsWith(Engine.build("dtbo")) && command != Engine.status() && command != Engine.settingsDump()) {
                    statusDirty = true
                }
                AppVisibility.statusRevision.intValue++
            }
        }
    }

    private suspend fun runAsRoot(command: String): ExecResult {
        val backgroundRead = command == Engine.settingsDump() || command in listOf(
            Engine.dtboStatus(), Engine.dtboHasLocal(), Engine.dtboResolve(),
            Engine.dtboDirs(), Engine.dtboList(), Engine.dtboBackups(),
        )
        if (!AppVisibility.visible.value && backgroundRead) return ExecResult("应用不可见，读取已暂停", -1)
        val timeoutMs = if (command == Engine.build("diag", "pack")) 120_000L else 30_000L
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val processRef = AtomicReference<Process?>()
                    continuation.invokeOnCancellation { processRef.get()?.destroy() }
                    Dispatchers.IO.dispatch(continuation.context, Runnable {
                        if (!continuation.isActive) return@Runnable
                        try {
                            val process = ProcessBuilder("su", "-c", command)
                                .redirectErrorStream(true)
                                .start()
                            processRef.set(process)
                            if (!continuation.isActive) {
                                process.destroy()
                                return@Runnable
                            }
                            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                            val code = process.waitFor()
                            if (continuation.isActive) continuation.resume(ExecResult(output.trim(), code))
                        } catch (t: Throwable) {
                            if (continuation.isActive) continuation.resume(ExecResult(t.message ?: "error", -1))
                        }
                    })
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ExecResult("Root 命令超时（${timeoutMs / 1000} 秒）", -1)
        } catch (t: Throwable) {
            ExecResult(t.message ?: "error", -1)
        }
    }
}

/**
 * 免 root 直读 sysfs：守护进程以 poll_interval（默认 3s）才写一次 status.json，
 * 面板只读快照会落在 3s 粒度上，有权限时直接取实时节点覆盖 cap/status/temp/volt/curr。
 */
private fun readSysfsLive(cache: StatusSourceCache): Map<String, String>? {
    val bat = "/sys/class/power_supply/battery"
    if (cache.cooledDown(bat)) return null
    val out = LinkedHashMap<String, String>()
    try {
        // 直接读首关键节点探路：先 File.isDirectory 会把 EACCES 吞成「目录不存在」。
        val cap = cache.readNode(File("$bat/capacity"), SOURCE_RETRY_MS, MISSING_RETRY_MS)
        if (cap == null) {
            // 节点不存在（机型差异）整组短退避；权限拒绝由 NodeDenied 走长退避。
            if (!cache.cooledDown(bat)) cache.markUnreadable(bat, MISSING_RETRY_MS)
            return null
        }
        out["cap"] = cap
        cache.readNode(File("$bat/status"), SOURCE_RETRY_MS, MISSING_RETRY_MS)?.let { out["status"] = it }
        cache.readNode(File("$bat/temp"), SOURCE_RETRY_MS, MISSING_RETRY_MS)?.let { out["temp"] = it }
        cache.readNode(File("$bat/voltage_now"), SOURCE_RETRY_MS, MISSING_RETRY_MS)?.let { out["volt"] = it }
        (cache.readNode(File("$bat/current_now"), SOURCE_RETRY_MS, MISSING_RETRY_MS)
            ?: cache.readNode(File("/sys/class/power_supply/bms/current_now"), SOURCE_RETRY_MS, MISSING_RETRY_MS))
            ?.let { out["curr"] = it }
        cellUv(cache)?.let { out["cell"] = it }
    } catch (_: NodeDenied) {
        // 权限被拒：本轮不再逐个探剩余节点（一刷只留一条 denial），已读到的值照用；
        // 整组都读不到时整组退避，由冷却到期低频重探。
        if (out.isEmpty()) cache.markUnreadable(bat, SOURCE_RETRY_MS)
    }
    return out.ifEmpty { null }
}

/** 与引擎 cell_uv 同口径：双电芯机型的 voltage_now 可能是串联总压。 */
private fun cellUv(cache: StatusSourceCache): String? {
    val bat = "/sys/class/power_supply/battery"
    for (path in listOf("$bat/voltage_avg", "/sys/class/qcom-battery/cell1_volt", "$bat/voltage_now")) {
        val v = cache.readNode(File(path), SOURCE_RETRY_MS, MISSING_RETRY_MS)?.toLongOrNull() ?: continue
        if (v < 2_500_000L) continue
        if (v <= 5_400_000L) return v.toString()
    }
    val v = cache.readNode(File("$bat/voltage_now"), SOURCE_RETRY_MS, MISSING_RETRY_MS)?.toLongOrNull()
    return if (v != null && v > 0) (v / 2).toString() else null
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

private fun chargingProtocol(map: Map<String, String>): String {
    if ((map["usb_online"] ?: map["online"]) != "1") return "--"
    val usbTypes = map["usb_types"].orEmpty().trim()
    val activeUsbType = if ('[' in usbTypes) {
        usbTypes.substringAfter('[', "").substringBefore(']', "")
    } else {
        usbTypes.takeIf { it.none(Char::isWhitespace) }
    }
    val protocol = sequenceOf(
        map["dt|快充类型"], map["dt|实际充电器类型"], map["dt|USB 实际类型"],
        map["usb_real_type"], activeUsbType, map["usb_type"]?.takeIf { it.none(Char::isWhitespace) },
        map["usb_supply_type"].takeIf { map["usb_online"] == "1" },
    ).filterNotNull().map(String::trim).firstOrNull {
        it.any(Char::isLetter) && it.lowercase() !in setOf("unknown", "none", "n/a", "null", "not available")
    }
    return when (protocol) {
        "USB_PD" -> "USB PD"
        else -> protocol ?: "--"
    }
}

private fun mapStatus(map: Map<String, String>, environment: String = "Root · 模块在线"): DeviceStatus {
    val tempC = (num(map, "temp") / 10.0).toFloat()
    val currUa = num(map, "curr")
    val packUv = num(map, "volt")
    val cellUv = num(map, "cell").takeIf { it > 0 } ?: packUv

    // 统一约定：充电为正、放电为负（sysfs current_now 充电时为负，这里取反）。
    val currentMa = (-currUa / 1000.0).toInt()
    val voltageMv = (cellUv / 1000.0).toInt()
    val powerW = (-currUa / 1000.0 * packUv / 1_000_000_000.0).toFloat()

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
            val temp = if (abs(value) > 1000f) value / 1000f else value
            entry.key.removePrefix("tz|") to temp
        }
        .take(8)

    val powerTier = map["power_tier"] ?: "inf"

    return DeviceStatus(
        soc = asInt(map, "cap"),
        status = zhStatus(map["status"] ?: ""),
        protocol = chargingProtocol(map),
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
        environment = environment,
        date = map["date"] ?: "",
        deviceCode = map["device_code"] ?: "",
        osVersion = map["os_version"] ?: "",
        baseFirmware = map["base_firmware"] ?: "",
        thermalMode = map["thermal_mode"] ?: "default",
        thermalDefault = map["thermal_default"] ?: "default",
        powerTier = powerTier,
        powerApplied = map["power_applied"] ?: "",
        powerTargetW = map["power_target_w"]?.toFloatOrNull(),
        powerApplyState = map["power_apply_state"] ?: "",
        unlock = asBool(map, "unlock", true),
        freepps = asBool(map, "freepps", false),
        cloudGuard = asBool(map, "cloud_guard", true),
        joyoseClean = asBool(map, "joyose_clean", true),
        chgCutoff = map["chg_cutoff"].orEmpty(),
        chgCutoffPhase = map["chg_cutoff_phase"].orEmpty(),
        fgHard = map["fg_hard"].orEmpty(),
        aplEnable = asBool(map, "apl_enable", false),
        cycleFake = map["cycle_fake"] ?: "",
        termV = map["term_v"] ?: "",
        bypassManual = asBool(map, "bypass_manual", false),
        rearScreen = asBool(map, "rearscreen", false),
        rearScreenSupported = asBool(map, "rearscreen_supported", true),
        daemonPid = map["daemon_pid"] ?: "",
        daemonUptime = map["daemon_uptime"]?.toIntOrNull() ?: 0,
        baaState = map["baa_state"] ?: "",
        chargeGov = asBool(map, "charge_gov", true),
        bypassActive = asBool(map, "bypass_active", false),
        thermalHandback = asBool(map, "thermal_handback", false),
        powerThermalMa = map["power_thermal_ma"]?.toIntOrNull() ?: 0,
        ppsVerified = map["pps_verified"] ?: "",
        cloudBlocked = map["cloud_blocked"]?.toIntOrNull() ?: 0,
        aplTargetMa = map["apl_target_ma"]?.toIntOrNull() ?: 0,
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
