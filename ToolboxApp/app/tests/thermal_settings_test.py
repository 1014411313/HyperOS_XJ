#!/usr/bin/env python3
import importlib.util
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ToolboxApp"
COMMON = APP / "app/src/commonMain/kotlin/com/hyperos/xj/runtime/ToolboxRuntimeBridge.kt"
ANDROID = APP / "app/src/androidMain/kotlin/com/hyperos/xj/runtime/ToolboxRuntimeBridge.android.kt"
PAGE = APP / "app/src/commonMain/kotlin/com/hyperos/xj/pages/ThermalPage.kt"
spec = importlib.util.spec_from_file_location("kotlin_runner", ROOT / "scripts/status_source_test.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def between(source, start, end):
    return source[source.index(start):source.index(end, source.index(start))]


def main():
    common = COMMON.read_text()
    android = ANDROID.read_text()
    page = PAGE.read_text()
    assert page.count("bridge.execResult(") == 1
    assert page.count("settingsWriteMutex = Mutex()") == 1
    assert "write(Engine.chargeGov(it))" in page
    assert "write(Engine.setting(\"power_plan_mode\"" in page
    assert "if (!loaded || !planWattsTouched)" in page
    for key in ("time_start", "time_end", "power_plan_start", "power_plan_end"):
        assert 'updateHour("%s", value)' % key in page
    load = between(page, "    LaunchedEffect(Unit)", "    LaunchedEffect(planWatts")
    assert load.index("readThermalSettings()") < load.index("loaded = true") < load.index("catch")
    assert "cachedThermalSettings" not in load
    assert "settingsReadError" in load and "CancellationException" in load
    assert "读取设置失败：" in page and "XJ.Error" not in page

    ns = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.parse(APP / "androidApp/src/main/AndroidManifest.xml").getroot()
    receiver = next(r for r in manifest.findall("application/receiver")
                    if r.get(ns + "name") == ".rear.ControlReceiver")
    assert receiver.get(ns + "exported") == "false"
    service = manifest.find("application/service")
    assert service.get(ns + "exported") == "false"
    receiver_source = (APP / "androidApp/src/main/java/com/hyperos/xj/rear/ControlReceiver.java").read_text()
    for unreliable in ("getCallingUid(", "getSentFromUid(", "getSendingUid("):
        assert unreliable not in receiver_source
    print("PASS 页面读取/写入接线与 Manifest 非导出边界检查（非 Android 系统投递实测）", flush=True)

    jars = [runner.newest_jar(group, artifact) for group, artifact in (
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable"),
        ("org.jetbrains.kotlin", "kotlin-stdlib"),
        ("org.jetbrains.kotlin", "kotlin-reflect"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
        ("org.jetbrains", "annotations"),
    )]
    if not all(jars):
        print("SKIP 缺少缓存 Kotlin 编译依赖，无法动态验证")
        return 2
    compiler, stdlib, reflect, coroutines, annotations = jars
    read = between(android, "    override suspend fun readThermalSettings()", "    override suspend fun exec(")
    command_mutex = between(android, "private val engineCommandMutex = Mutex()", "\n\n/**")
    exec_methods = between(android, "    override suspend fun exec(command: String)", "    private suspend fun runAsRoot(")
    exec_methods = exec_methods.replace("Dispatchers.Main", "Dispatchers.Default")
    write = between(page, "    suspend fun write(", "    fun save(")
    source = """package com.hyperos.xj.runtime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
"""
    source += command_mutex + "\n"
    source += between(common, "data class ThermalSettings(", "interface ToolboxRuntimeBridge")
    source += between(common, "data class ExecResult(", "expect fun createToolboxBridge")
    source += between(common, "internal fun thermalHour(", "/** xjctl status")
    source += between(android, "private fun num(", "private fun zhStatus(")
    source += between(android, "private fun mapThermal(", "actual fun createToolboxBridge")
    source += """
private object Engine {
    fun settingsDump() = "dump"
    fun build(command: String) = "build:$command"
    fun status() = "status"
}
private object AppVisibility {
    var visible = true
    val statusRevision = IntState()
}
private class IntState { var intValue = 0 }
private class ThermalReadHarness {
    var settingsCache: ThermalSettings? = null
    var response = ExecResult("", 0)
    suspend fun runAsRoot(command: String): ExecResult = response
"""
    source += read.replace("override suspend", "suspend") + "}\n"
    source += """
private class ExecBridgeHarness(private val run: suspend (String) -> ExecResult) {
    private var settingsCache: ThermalSettings? = null
    private var statusDirty = false
    private fun invalidateRootBase() = Unit
    private suspend fun runAsRoot(command: String): ExecResult = run(command)
"""
    source += exec_methods.replace("override suspend", "suspend") + "}\n"
    source += "private val settingsWriteMutex = Mutex()\n"
    source += "private class ThermalWriteHarness(private val bridge: FakeWriter) {\n" + write + "}\n"
    test = Path(__file__).with_name("ThermalSettingsBehaviorTest.kt").read_text()
    source += test[test.index("private val requiredSettings"):]
    with tempfile.TemporaryDirectory(prefix="xj-thermal-test-") as directory:
        temp = Path(directory)
        generated = temp / "ThermalSettingsTest.kt"
        generated.write_text(source)
        cp = runner.compiler_classpath([stdlib, coroutines, annotations])
        result = subprocess.run([
            "java", "-cp", runner.compiler_classpath(jars), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-classpath", cp, "-d", str(temp / "classes"), str(generated),
        ])
        if result.returncode:
            return result.returncode
        return subprocess.run([
            "java", "-cp", str(temp / "classes") + ":" + cp,
            "com.hyperos.xj.runtime.ThermalSettingsTestKt",
        ]).returncode


if __name__ == "__main__":
    raise SystemExit(main())
