#!/usr/bin/env python3
import importlib.util
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNTIME = ROOT / "ToolboxApp/app/src"
spec = importlib.util.spec_from_file_location("runner", ROOT / "scripts/status_source_test.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

FIXTURE = '''package com.hyperos.xj.runtime
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.system.measureTimeMillis

object AppVisibility {
    object visible { var value = true }
    object statusRevision { var intValue = 0 }
}
object Fixture {
    var direct = false
    var fail = false
    var partial = false
    var slow = false
    var cancelled = false
    var calls = 0
    var current = 1000000
    var voltage = 4200000
    var cell = 4200000
    val core get() = "cap=85\\nstatus=Discharging\\ntemp=390\\nvolt=$voltage\\ncell=$cell\\ncurr=$current"
    val snapshot get() = "{\\"cap\\":\\"85\\",\\"status\\":\\"Discharging\\",\\"temp\\":\\"390\\",\\"volt\\":\\"4200000\\",\\"cell\\":\\"4200000\\",\\"curr\\":\\"999000\\",\\"ts\\":\\"${System.currentTimeMillis()/1000}\\",\\"module_version\\":\\"3.1.69\\",\\"power_tier\\":\\"fast\\",\\"device_name\\":\\"Test Device\\"}"
    fun read(file: java.io.File): String {
        if (file.path.endsWith("status.json")) return snapshot
        if (!direct) throw FileNotFoundException("EACCES Permission denied")
        return when(file.name) {
            "capacity" -> "85"
            "status" -> "Discharging"
            "temp" -> "390"
            "voltage_now", "voltage_avg" -> "4200000"
            "current_now" -> current.toString()
            else -> throw FileNotFoundException("ENOENT")
        }
    }
}
fun main() = runBlocking {
    val bridge = createToolboxBridge()
    for (current in listOf(1000000, 500000, -500000, 0)) {
        Fixture.current = current
        val status = bridge.readStatus()
        check(status.currentMa == -current/1000) { "fresh snapshot hid live current: $status" }
        check(status.moduleVersion == "3.1.69" && status.powerTier == "fast")
        check(status.voltageMv == 4200)
        check(kotlin.math.abs(status.powerW - (-current/1000000f*4.2f)) < 0.001f)
    }
    check(Fixture.calls == 4)
    Fixture.voltage = 8400000
    Fixture.current = -1000000
    val dual = bridge.readStatus()
    check(dual.voltageMv == 4200 && dual.powerW == 8.4f && dual.currentMa == 1000)
    Fixture.voltage = 4200000
    Fixture.current = 0
    Fixture.partial = true
    val partial = createToolboxBridge()
    check(partial.readStatus().currentMa == 0)
    Fixture.partial = false
    Fixture.slow = true
    val slow = createToolboxBridge()
    val elapsed = measureTimeMillis { check(slow.readStatus().currentMa == -999) }
    check(elapsed in 1_800..2_800 && Fixture.cancelled) { "live timeout failed: $elapsed" }
    val beforeRetry = Fixture.calls
    slow.readStatus()
    check(Fixture.calls == beforeRetry)
    Fixture.slow = false
    Fixture.fail = true
    val failed = createToolboxBridge()
    val beforeFailure = Fixture.calls
    check(failed.readStatus().currentMa == -999)
    failed.readStatus()
    check(Fixture.calls == beforeFailure+1) { "failed root read did not cool down" }
    Fixture.fail = false
    Fixture.direct = true
    val direct = createToolboxBridge()
    val beforeDirect = Fixture.calls
    check(direct.readStatus().currentMa == 0)
    check(Fixture.calls == beforeDirect) { "read root despite complete direct data" }
    AppVisibility.visible.value = false
    direct.readStatus()
    check(Fixture.calls == beforeDirect) { "read root while hidden" }
    println("PASS live current including zero, voltage/power mapping, snapshot metadata, failure cooldown, direct source and background stop")
}
'''

bridge = (RUNTIME / "androidMain/kotlin/com/hyperos/xj/runtime/ToolboxRuntimeBridge.android.kt").read_text()
bridge = bridge.replace("StatusSourceCache()", "StatusSourceCache(readText = { Fixture.read(it) })")
start = bridge.index("    private suspend fun readRootStatus(")
end = bridge.index("    private fun invalidateRootBase()", start)
bridge = bridge[:start] + '''    private suspend fun readRootStatus(command: String = Engine.status()): ExecResult {
        check(command == Engine.readLive()) { "unexpected full status request" }
        Fixture.calls++
        if (Fixture.slow) {
            try { kotlinx.coroutines.delay(10_000L) } finally { Fixture.cancelled = true }
        }
        return when {
            Fixture.fail -> ExecResult("denied", 1)
            Fixture.partial -> ExecResult(Fixture.core.lineSequence().filterNot { it.startsWith("temp=") }.joinToString("\\n"), 0)
            else -> ExecResult(Fixture.core, 0)
        }
    }

''' + bridge[end:]
bridge = bridge.replace("actual fun createToolboxBridge", "fun createToolboxBridge")
common = (RUNTIME / "commonMain/kotlin/com/hyperos/xj/runtime/ToolboxRuntimeBridge.kt").read_text()
common = common.replace("expect fun createToolboxBridge(): ToolboxRuntimeBridge", "")
jars = [runner.newest_jar(g, a) for g, a in [
    ("org.jetbrains.kotlin", "kotlin-compiler-embeddable"),
    ("org.jetbrains.kotlin", "kotlin-stdlib"),
    ("org.jetbrains.kotlin", "kotlin-reflect"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
    ("org.jetbrains", "annotations"),
]]
assert all(jars), "Missing cached Kotlin compiler dependencies"
classpath = runner.compiler_classpath(jars)
with tempfile.TemporaryDirectory(prefix="xj-bridge-live-") as directory:
    work = Path(directory)
    (work / "Bridge.kt").write_text(bridge)
    (work / "Common.kt").write_text(common)
    (work / "Fixture.kt").write_text(FIXTURE)
    (work / "Cache.kt").write_text(runner.SOURCE.read_text())
    for mutation in (False, True):
        candidate = bridge.replace('if (snap != null && !hasLiveCore', 'if (snap == null && !hasLiveCore') if mutation else bridge
        assert not mutation or candidate != bridge
        (work / "Bridge.kt").write_text(candidate)
        out = work / ("mutant" if mutation else "classes")
        compiled = subprocess.run(["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", classpath, "-d", str(out), *map(str, work.glob("*.kt"))], capture_output=True, text=True)
        assert compiled.returncode == 0, compiled.stderr
        result = subprocess.run(["java", "-cp", str(out)+":"+classpath, "com.hyperos.xj.runtime.FixtureKt"], capture_output=True, text=True)
        if mutation:
            assert result.returncode != 0 and "fresh snapshot hid live current" in result.stderr, result.stderr
            print("PASS mutation: disabling live fallback is detected")
        else:
            assert result.returncode == 0, result.stderr
            print(result.stdout.strip())
