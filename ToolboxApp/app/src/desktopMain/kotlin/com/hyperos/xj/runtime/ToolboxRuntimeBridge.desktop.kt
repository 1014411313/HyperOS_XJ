package com.hyperos.xj.runtime

import kotlinx.coroutines.delay
import kotlin.math.sin

private class DesktopToolboxBridge : ToolboxRuntimeBridge {
    private var tick = 0L

    override suspend fun readStatus(): DeviceStatus {
        delay(1000L)
        tick += 1L
        val t = tick.toDouble()
        val soc = (52 + (sin(t / 14.0) * 8).toInt()).coerceIn(1, 100)
        val charging = (t / 40.0).toInt() % 2 == 0
        val temp = 34.8f + (sin(t / 9.0) * 2.4).toFloat()
        val current = if (charging) 1450 + ((t * 37) % 900).toInt() else -(420 + ((t * 53) % 360)).toInt()
        val power = (current * 4.42f / 1000f).let { if (it < 0f) -it else it }
        return DeviceStatus(
            soc = soc,
            status = if (soc >= 100) "满电" else if (charging) "充电中" else "放电中",
            protocol = if (charging) "PPS 120W" else "PD 3.0",
            tempC = temp,
            currentMa = current,
            voltageMv = 4380 + ((t * 11) % 220).toInt(),
            powerW = power,
            fullSpeed = false,
            remainingMah = 3480 + ((t * 7) % 180).toInt(),
            fullMah = 4680,
            designMah = 5000,
            health = 94,
            soh = 92,
            cycles = 128,
            fakeCycles = 12,
            thermalZones = listOf(36f, 38f + (sin(t / 7.0) * 1.2).toFloat(), 35f, 41f, 37f, 34f),
            gear = if (charging) "充电场景·三档" else "普通场景·三档",
            model = "HyperOS XJ",
            moduleVersion = "2.1.0-beta14",
            kernelVersion = "5.15.160",
            systemVersion = "HyperOS 2.0.12",
            environment = "桌面预览",
            date = "2026-11-07",
        )
    }

    override suspend fun readThermalSettings(): ThermalSettings {
        delay(80L)
        return ThermalSettings(
            defaultMode = "level_3",
            chargeLevel = "unlimited",
            sleepLevel = "level_1",
            gameLevel = "unlimited",
        )
    }

    override suspend fun exec(command: String): String {
        delay(120L)
        return "OK"
    }
}

actual fun createToolboxBridge(): ToolboxRuntimeBridge = DesktopToolboxBridge()
