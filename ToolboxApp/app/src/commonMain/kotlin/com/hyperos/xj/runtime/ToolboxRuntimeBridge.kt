package com.hyperos.xj.runtime

data class DeviceStatus(
    val soc: Int = 0,
    val status: String = "未知",
    val protocol: String = "--",
    val tempC: Float = 0f,
    val currentMa: Int = 0,
    val voltageMv: Int = 0,
    val powerW: Float = 0f,
    val fullSpeed: Boolean = false,
    val remainingMah: Int = 0,
    val fullMah: Int = 0,
    val designMah: Int = 0,
    val health: Int = 0,
    val soh: Int = 0,
    val cycles: Int = 0,
    val fakeCycles: Int = 0,
    val thermalZones: List<Float> = emptyList(),
    val gear: String = "系统默认",
    val model: String = "HyperOS XJ",
    val moduleVersion: String = "--",
    val kernelVersion: String = "",
    val systemVersion: String = "--",
    val environment: String = "检测中",
    val date: String = "",
    val deviceCode: String = "",
    val osVersion: String = "",
    val baseFirmware: String = "",
    val thermalMode: String = "default",
    val thermalDefault: String = "default",
    val powerTier: String = "inf",
    val powerApplied: String = "",
    val unlock: Boolean = true,
    val freepps: Boolean = false,
    val cloudGuard: Boolean = true,
    val aplEnable: Boolean = false,
    val cycleFake: String = "",
    val fvV: String = "",
    val termV: String = "",
    val fvForce: Boolean = false,
    val bypassManual: Boolean = false,
    val rearScreen: Boolean = false,
    val rearScreenSupported: Boolean = true,
) {
    val charging: Boolean get() = status == "充电中" || status == "满电"
}

data class ThermalSettings(
    val enabled: Boolean = true,
    val defaultMode: String = "default",
    val chargeMode: Boolean = true,
    val chargeLevel: String = "unlimited",
    val sleepLevel: String = "level_1",
    val timeStart: Int = 0,
    val timeEnd: Int = 0,
    val timeLevel: String = "level_1",
    val gameMode: Boolean = true,
    val gameLevel: String = "unlimited",
    val gameApps: String = "",
    val gameFps: Int = 0,
    val gameBypass: Boolean = false,
    val bypassTempOn: Int = 0,
    val bypassTempOff: Int = 0,
    val bypassLevel: Int = 0,
    val bypassCurrent: Int = 500_000,
    val hyperos4: Boolean = false,
    val noHitempStop: Boolean = false,
    val chargeGov: Boolean = true,
    val powerPlanOn: Boolean = false,
    val powerPlanStart: Int = 23,
    val powerPlanEnd: Int = 7,
    val powerPlanMode: String = "slow",
)

interface ToolboxRuntimeBridge {
    suspend fun readStatus(): DeviceStatus

    suspend fun readThermalSettings(): ThermalSettings

    suspend fun exec(command: String): String
}

expect fun createToolboxBridge(): ToolboxRuntimeBridge

/** 唯一引擎入口：所有命令都走同一个 xjctl.sh，避免 App 侧散落路径。 */
object Engine {
    const val MODULE_DIR = "/data/adb/modules/HyperOS_XJ"
    const val SCRIPT = "$MODULE_DIR/engine/xjctl.sh"

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun build(vararg args: String): String =
        "sh " + quote(SCRIPT) + " " + args.joinToString(" ") { quote(it) }

    fun status(): String = build("status")

    fun power(value: String): String = build("power", "set", value)

    fun thermal(mode: String): String = build("thermal", "set", mode)

    fun chargeGov(on: Boolean): String = build("thermal", "gov", if (on) "on" else "off")

    fun battery(key: String, value: String): String = build("battery", key, value)

    fun batteryRaw(vararg parts: String): String = build("battery", *parts)

    fun bypass(on: Boolean): String = build("bypass", if (on) "on" else "off")

    fun setting(key: String, value: String): String = build("setting", "set", key, value)

    fun settingsDump(): String = build("setting", "dump")

    fun rear(on: Boolean): String = build("rear", if (on) "on" else "off")

    fun rearConfig(fields: String, fontScale: String, accent: String): String =
        build("rear", "set", "fields=$fields", "font_scale=$fontScale", "accent=$accent")

    fun dtboStatus(): String = build("dtbo", "status")

    fun dtboFlash(img: String): String = build("dtbo", "flash", img)

    fun dtboList(): String = build("dtbo", "list")

    fun dtboAddDir(dir: String): String = build("dtbo", "adddir", dir)

    fun dtboDelDir(dir: String): String = build("dtbo", "deldir", dir)

    fun dtboDirs(): String = build("dtbo", "dirs")

    fun dtboHasLocal(): String = build("dtbo", "haslocal")

    fun dtboFlashLocal(): String = build("dtbo", "flashlocal")
}

internal fun parseKv(raw: String): Map<String, String> =
    raw.lineSequence()
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        .toMap()

/** xjctl status 输出的是平铺 JSON，用一个零依赖的小解析器避免引入额外依赖。 */
internal fun parseFlatJson(raw: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var i = 0
    val n = raw.length

    fun skipWs() {
        while (i < n && raw[i].isWhitespace()) i++
    }

    while (i < n) {
        skipWs()
        if (i >= n) break
        if (raw[i] != '"') {
            i++
            continue
        }
        i++
        val key = StringBuilder()
        while (i < n && raw[i] != '"') {
            if (raw[i] == '\\' && i + 1 < n) {
                i++
                key.append(unescape(raw[i]))
            } else {
                key.append(raw[i])
            }
            i++
        }
        if (i < n) i++
        skipWs()
        if (i >= n || raw[i] != ':') continue
        i++
        skipWs()

        val value = StringBuilder()
        if (i < n && raw[i] == '"') {
            i++
            while (i < n && raw[i] != '"') {
                if (raw[i] == '\\' && i + 1 < n) {
                    i++
                    value.append(unescape(raw[i]))
                } else {
                    value.append(raw[i])
                }
                i++
            }
            if (i < n) i++
        } else {
            while (i < n && raw[i] != ',' && raw[i] != '}') {
                value.append(raw[i])
                i++
            }
        }
        out[key.toString()] = value.toString().trim()
    }
    return out
}

private fun unescape(c: Char): Char = when (c) {
    'n' -> '\n'
    't' -> '\t'
    'r' -> '\r'
    else -> c
}
