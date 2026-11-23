package com.hyperos.xj.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val requiredSettings = """
    enabled=true
    default_mode=level_3
    charge_mode=false
    charge_level=unlimited
    sleep_level=level_1
    time_start=23
    time_end=0
    time_level=level_2
    game_mode=true
    game_level=unlimited
""".trimIndent()

private class FakeWriter {
    val settings = mutableMapOf<String, String>()
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val entered = Channel<String>(Channel.UNLIMITED)
    var active = 0
    var maxActive = 0

    suspend fun execResult(command: String): ExecResult {
        active++
        maxActive = maxOf(maxActive, active)
        try {
            entered.send(command)
            started.complete(Unit)
            release.await()
            val field = if ('=' in command) command.substringAfter("=") else command
            settings[field] = if ('=' in command) command.substringBefore("=") else command
            return if (field == "fail") ExecResult("root denied", 23) else ExecResult("OK:$field", 0)
        } finally {
            active--
        }
    }
}

fun main() = runBlocking {
    val reader = ThermalReadHarness()
    reader.response = ExecResult(requiredSettings, 0)
    val success = reader.readThermalSettings()
    check(success.defaultMode == "level_3" && !success.chargeMode)
    check(success.timeStart == 23 && success.timeEnd == 0)
    check(success.powerPlanStart == 23 && success.powerPlanEnd == 7)
    check(success.chargeGov && success.powerPlanMode == "slow")
    check(reader.settingsCache == success)

    reader.response = ExecResult(requiredSettings + "\npower_plan_mode=\npower_plan_start=\n", 0)
    check(reader.readThermalSettings().powerPlanMode == "slow")

    suspend fun rejects(output: String, exitCode: Int = 0) {
        reader.settingsCache = success
        reader.response = ExecResult(output, exitCode)
        val failure = runCatching { reader.readThermalSettings() }.exceptionOrNull()
        check(failure is IllegalStateException)
        check(!failure.message.isNullOrBlank())
        check(reader.settingsCache == null)
    }
    rejects(requiredSettings, 1)
    rejects("su: denied", -1)
    rejects("")
    rejects("enabled=true\ndefault_mode=default\ncharge_mode=true")
    rejects(requiredSettings.replace("enabled=true", "enabled="))
    rejects(requiredSettings.replace("enabled=true", "enabled=garbage"))
    rejects(requiredSettings.replace("default_mode=level_3", "default_mode=garbage"))
    rejects(requiredSettings.replace("time_start=23", "time_start=24"))
    rejects(requiredSettings + "\npower_plan_start=24")
    rejects(requiredSettings + "\npower_plan_end=-1")
    reader.response = ExecResult(requiredSettings + "\npower_plan_start=0\npower_plan_end=23", 0)
    check(reader.readThermalSettings().powerPlanEnd == 23)

    for (hour in 0..23) {
        check(thermalHour(hour.toString()) == hour)
        check(thermalHour(hour.toString().padStart(2, '0')) == hour)
    }
    for (input in listOf("", "24", "99", "100", "-1", "+1", "1.0", " 1", "1 ", "１２", "abc", "2a")) {
        check(thermalHour(input) == null) { "accepted invalid hour: $input" }
    }

    val bridge = FakeWriter()
    bridge.release.complete(Unit)
    val writer = ThermalWriteHarness(bridge)
    coroutineScope {
        repeat(20) { index -> launch(Dispatchers.Default) { writer.write("key$index") } }
    }
    check(bridge.settings.size == 20)
    check(bridge.maxActive == 1)

    val shared = FakeWriter()
    val bridgeA = ExecBridgeHarness { shared.execResult(it) }
    val bridgeB = ExecBridgeHarness { shared.execResult(it) }
    val commands = (0 until 20).map { "write=field$it" }
    val jobs = commands.mapIndexed { index, command ->
        launch(Dispatchers.Default) { (if (index % 2 == 0) bridgeA else bridgeB).exec(command) }
    }
    shared.entered.receive()
    check(shared.entered.tryReceive().isFailure) { "parallel command entered root while the first was blocked" }
    shared.release.complete(Unit)
    repeat(19) { shared.entered.receive() }
    jobs.joinAll()
    check(shared.settings.size == 20) { "lost settings fields: ${shared.settings}" }
    check(shared.maxActive == 1) { "cross-instance exec overlapped: ${shared.maxActive}" }

    val cancelRoot = FakeWriter()
    val cancelBridgeA = ExecBridgeHarness { cancelRoot.execResult(it) }
    val cancelBridgeB = ExecBridgeHarness { cancelRoot.execResult(it) }
    val cancelled = launch(Dispatchers.Default) { cancelBridgeA.exec("write=first") }
    cancelRoot.entered.receive()
    cancelled.cancel()
    val waiting = launch(Dispatchers.Default) { cancelBridgeB.exec("write=second") }
    yield()
    check(cancelRoot.entered.tryReceive().isFailure) { "second root call entered before first returned" }
    cancelRoot.release.complete(Unit)
    waiting.join()
    check(cancelled.isCancelled)
    check(cancelRoot.settings.keys == setOf("first", "second"))
    check(cancelRoot.maxActive == 1)

    val failure = ExecBridgeHarness { ExecResult("root denied", 23) }.execResult("write=fail")
    check(failure == ExecResult("root denied", 23)) { "execResult masked failure: $failure" }
    println("PASS 温控设置行为与源码提取 exec：20路跨实例串行、字段保留、取消锁保持及失败结果透传")
}
