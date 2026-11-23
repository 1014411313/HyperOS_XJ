#!/usr/bin/env python3
"""从真实桥接/页面提取纯映射及读源方法，在隔离 JVM 验证状态契约。"""
import importlib.util
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNTIME = ROOT / "ToolboxApp/app/src"
spec = importlib.util.spec_from_file_location("runner", ROOT / "scripts/status_source_test.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def between(source, start, end):
    return source[source.index(start):source.index(end, source.index(start))]


def main():
    common = (RUNTIME / "commonMain/kotlin/com/hyperos/xj/runtime/ToolboxRuntimeBridge.kt").read_text()
    android = (RUNTIME / "androidMain/kotlin/com/hyperos/xj/runtime/ToolboxRuntimeBridge.android.kt").read_text()
    home = (RUNTIME / "commonMain/kotlin/com/hyperos/xj/pages/HomePage.kt").read_text()
    battery = (RUNTIME / "commonMain/kotlin/com/hyperos/xj/pages/BatteryPage.kt").read_text()
    visibility = (RUNTIME / "androidMain/kotlin/com/hyperos/xj/runtime/AppVisibility.android.kt").read_text()
    cache = runner.SOURCE.read_text()
    source = """package com.hyperos.xj.runtime
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
"""
    source += between(common, "data class DeviceStatus(", "interface ToolboxRuntimeBridge")
    source += between(common, "data class ExecResult(", "expect fun createToolboxBridge")
    source += between(common, "object Engine {", "private fun unescape(")
    source += common[common.index("private fun unescape("):]
    source += "\n" + cache[cache.index("/** 权限被拒"):]
    source += between(android, "private fun num(", "actual fun createToolboxBridge")
    source += home[home.index("private fun fmt1("):]
    source += """
private object AppVisibility { object visible { var value = true } }
private const val SHARED_STATUS_STALE_MS = 15_000L
private const val SOURCE_RETRY_MS = 60_000L
private const val MISSING_RETRY_MS = 5_000L
private val sharedStatusFiles = listOf(File("/isolated/status.json"))
private var snapshotRaw = ""
private var liveValues: Map<String, String>? = null
private fun readSysfsLive(cache: StatusSourceCache) = liveValues
private class ReadHarness {
    val sourceCache = StatusSourceCache(readText = { snapshotRaw })
    var statusDirty = false
    var minimumSnapshotTs = 0L
    var rootCalls = 0
    var rootValues: Map<String, String>? = null
    val rootHintText = "Root 不可用"
    fun withDeviceInfo(map: Map<String, String>) = map
    suspend fun rootBaseCached(): Map<String, String>? { rootCalls++; return rootValues }
    suspend fun readRootStatus(command: String) = ExecResult("", -1)
"""
    source += between(android, "    override suspend fun readStatus()", "    private suspend fun rootBaseCached()") .replace("override suspend", "suspend")
    source += "}\n"
    source += """
private const val ROOT_STATUS_CACHE_MS = 1_000L
private class RootBaseHarness {
    var rootBaseAt = 0L
    var rootBase: Map<String, String>? = null
    var rootHintText = ""
    var calls = 0
    var response = ExecResult("", -1)
    suspend fun readRootStatus(): ExecResult { calls++; return response }
"""
    source += between(android, "    private suspend fun rootBaseCached()", "    private suspend fun readRootStatus(").replace("private suspend", "suspend") + "}\n"
    source += """
private lateinit var leaseFile: File
private lateinit var visibilityScope: CoroutineScope
private var visibilityJob: Job? = null
private fun LaunchedEffect(visible: Boolean, block: suspend CoroutineScope.() -> Unit) {
    val previous = visibilityJob
    visibilityJob = visibilityScope.launch { previous?.cancelAndJoin(); block() }
}
"""
    source += visibility[visibility.index("@Composable"):].replace("@Composable\n", "").replace("actual fun", "fun").replace(
        'File("/storage/emulated/0/Android/data/com.hyperos.xj/files/visible.until")', 'leaseFile')
    save = between(battery, "    fun saveBattery(", "    fun refreshDtboLibrary()")
    voltage_apply = battery.split('"应用关机电压",\n                        {\n', 1)[1].split('\n                        },', 1)[0]
    voltage_off = battery.split('"关闭",\n                        {\n', 1)[1].split('\n                        },', 1)[0]
    source += """
private var toast = ""
private fun showToast(message: String) { toast = message }
private class BatteryHarness(val scope: CoroutineScope) {
    var batteryBusy = false
    var statusReady = true
    var saved = false
    var shutdownVoltage = ""
    val bridge = FakeBatteryBridge()
""" + save + "fun applyVoltage() {\n" + voltage_apply + "\n}\nfun disableVoltage() {\n" + voltage_off + "\n}\n}\n"
    source += """
private class FakeBatteryBridge {
    var response = ExecResult("denied", 1)
    val commands = mutableListOf<String>()
    suspend fun execResult(command: String): ExecResult { commands += command; delay(10); return response }
}
"""
    source += Path(__file__).with_name("StatusContractBehaviorTest.kt").read_text()
    jars = [runner.newest_jar(g, a) for g, a in [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable"),
        ("org.jetbrains.kotlin", "kotlin-stdlib"),
        ("org.jetbrains.kotlin", "kotlin-reflect"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
        ("org.jetbrains", "annotations"),
    ]]
    if not all(jars):
        print("SKIP 缺少 Kotlin 编译缓存")
        return 2
    with tempfile.TemporaryDirectory(prefix="xj-status-contract-") as directory:
        temp = Path(directory)
        test = temp / "StatusContractTest.kt"
        test.write_text(source)
        cp = runner.compiler_classpath([jars[1], jars[3], jars[4]])
        result = subprocess.run(["java", "-cp", runner.compiler_classpath(jars),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-classpath", cp, "-d", str(temp / "classes"), str(test)], capture_output=True, text=True)
        if result.returncode:
            print(result.stdout + result.stderr)
            return result.returncode
        result = subprocess.run(["java", "-cp", str(temp / "classes") + ":" + cp,
            "com.hyperos.xj.runtime.StatusContractTestKt"])
        if result.returncode:
            return result.returncode
    assert "status.controlConfigLoaded" in battery
    assert 'status.environment != "检测中"' not in battery
    assert "status.powerThermalActive" in home
    assert 'unlock = it; saveBattery' not in battery
    assert 'cloudBlock = it; saveBattery' not in battery
    assert 'apl = it; saveBattery' not in battery
    for key in ("unlock", "freepps", "cloudGuard", "aplEnable", "cycleFake", "termV"):
        assert "remember(status.controlConfigLoaded, status.%s)" % key in battery
    print("PASS 页面配置加载/保存与温控状态接线")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
