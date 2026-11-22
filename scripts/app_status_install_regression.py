#!/usr/bin/env python3
"""App 状态读源 + 模块安装判定的回归。

两部分：
1) 源码级检查 ToolboxRuntimeBridge.android.kt + StatusSourceCache.kt：面板 700ms 读状态时先取共享快照，
   读不到的 sysfs 节点/快照文件要进冷却，不再每拍刷 SELinux denial；有权限时仍直读。
   NodeDenied/冷却等策略已移到 StatusSourceCache.kt，断言跟着读两个文件。
2) 用真实 JVM 跑 scripts/status_source_test.py（动态行为，替代纯文本检查），并隔离运行
   从 bridge 提取的 runAsRoot：su 替为 sh，验证退出码和真实 30 秒超时终止，不执行 root。
3) 沙箱里跑真实 customize.sh：pm/dumpsys 用桩，路径改到临时目录，断言安装失败/
   版本不一致/读不到版本时都不报「已安装且与模块版本一致」，且绝不自动卸载旧 App。

用法：python3 scripts/app_status_install_regression.py
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "ToolboxApp/app/src/androidMain/kotlin/com/hyperos/xj/runtime/ToolboxRuntimeBridge.android.kt"
STATUS_CACHE = ROOT / "ToolboxApp/app/src/androidMain/kotlin/com/hyperos/xj/runtime/StatusSourceCache.kt"
STATUS_TEST_DRIVER = ROOT / "scripts/status_source_test.py"
CUSTOMIZE = ROOT / "dist/HyperOS_XJ_New/customize.sh"
SESSION_ROOT = None

PM_STUB = """#!/bin/sh
echo "pm $*" >> "$CALL_LOG"
case "$1" in
  install)
    echo "${PM_INSTALL_OUT:-Success}"
    [ "${PM_INSTALL_OK:-1}" = "1" ] && exit 0
    exit 1 ;;
  path)
    [ "${PM_PATH_OK:-1}" = "1" ] || exit 1
    echo "package:/data/app/~~x/com.hyperos.xj-y/base.apk"
    exit 0 ;;
esac
exit 0
"""

DUMPSYS_STUB = """#!/bin/sh
echo "dumpsys $*" >> "$CALL_LOG"
[ "${DUMPSYS_OK:-1}" = "1" ] || exit 0
[ "$1" = "package" ] || exit 0
[ "$2" = "com.hyperos.xj" ] || exit 0
echo "Packages:"
echo "  Package [com.hyperos.xj] (8f2a):"
echo "    versionCode=${DUMPSYS_VC:-368} minSdk=23 targetSdk=37"
echo "    versionName=${DUMPSYS_VN:-3.1.58}"
exit 0
"""

GETPROP_STUB = """#!/bin/sh
case "$1" in ro.board.platform) echo "${TEST_PLATFORM:-}";; esac
exit 0
"""

NOOP_STUB = """#!/bin/sh
exit 0
"""

UI_PRINT_STUB = """#!/bin/sh
printf '%s\\n' "$*"
"""

# customize.sh 里 `timeout 8 sh -c '... /proc ...'` 会遍历进程表；测试里直接跳过，绝不 kill。
TIMEOUT_STUB = """#!/bin/sh
exit 0
"""

STUBS = {
    "pm": PM_STUB,
    "dumpsys": DUMPSYS_STUB,
    "getprop": GETPROP_STUB,
    "set_perm": NOOP_STUB,
    "set_perm_recursive": NOOP_STUB,
    "ui_print": UI_PRINT_STUB,
    "timeout": TIMEOUT_STUB,
}

MODULE_PROP = "id=rezhale_toolbox\nname=热炸了工具箱\nversion=3.1.58\nversionCode=368\n"
SETTINGS_CONF = "dev_code=\n"


class Case:
    def __init__(self, sb, proc):
        self.sb = sb
        self.out = proc.stdout
        self.fails = []

    def calls(self, prefix):
        log = (self.sb / "calls.log").read_text(errors="replace").splitlines()
        return [line for line in log if line.startswith(prefix)]

    def has(self, text, what):
        if text not in self.out:
            self.fails.append("%s（输出缺少 %r）\n--- 输出 ---\n%s" % (what, text, self.out))

    def lacks(self, text, what):
        if text in self.out:
            self.fails.append("%s（输出不应含 %r）\n--- 输出 ---\n%s" % (what, text, self.out))

    def eq(self, got, want, what):
        if got != want:
            self.fails.append("%s（期望 %s，实际 %s）" % (what, want, got))


def run_installer(name, env_extra=None, drop_apk=False, existing_settings=None):
    sb = SESSION_ROOT / name
    if sb.exists():
        shutil.rmtree(sb)
    (sb / "bin").mkdir(parents=True)
    (sb / "moddir/app").mkdir(parents=True)
    (sb / "moddir/battery/config").mkdir(parents=True)
    (sb / "calls.log").write_text("")
    for stub, text in STUBS.items():
        p = sb / "bin" / stub
        p.write_text(text)
        p.chmod(0o755)
    (sb / "moddir" / "module.prop").write_text(MODULE_PROP)
    (sb / "moddir" / "battery/config/settings.conf").write_text(SETTINGS_CONF)
    for template in ("default_settings.json", "default_settings.mtk.json"):
        shutil.copyfile(CUSTOMIZE.parent / template, sb / "moddir" / template)
    if existing_settings is not None:
        config = sb / "data/adb/rezhale_toolbox/config"
        config.mkdir(parents=True)
        (config / "settings.json").write_text(existing_settings)
    if not drop_apk:
        (sb / "moddir/app/rezhale_toolbox.apk").write_text("fake apk")

    # 绝对路径全部落到沙箱：绝不碰真实 /data/adb。
    script = CUSTOMIZE.read_text()
    script = script.replace("/data/adb", str(sb / "data/adb"))
    target = sb / "customize.sh"
    target.write_text(script)

    env = dict(os.environ)
    env["PATH"] = "%s:%s" % (sb / "bin", env.get("PATH", "/usr/bin:/bin"))
    env["MODPATH"] = str(sb / "moddir")
    env["CALL_LOG"] = str(sb / "calls.log")
    env.update(env_extra or {})
    proc = subprocess.run(["sh", str(target)], env=env, capture_output=True, text=True)
    return Case(sb, proc)


def check_bridge_source():
    """读源策略：bridge 负责三路取舍，StatusSourceCache.kt 负责读/冷却，两个文件一起查。"""
    fails = []
    bridge = BRIDGE.read_text()
    cache = STATUS_CACHE.read_text() if STATUS_CACHE.is_file() else ""
    if not cache:
        fails.append("缺少数据源策略文件 StatusSourceCache.kt")

    def need(text, what, haystack=None):
        if text not in (haystack if haystack is not None else bridge):
            fails.append("缺少：%s" % what)

    read_status = bridge.split("override suspend fun readStatus()", 1)
    if len(read_status) != 2:
        fails.append("bridge 找不到 readStatus 实现")
    else:
        head = read_status[1].split("private fun rootBaseCached", 1)[0]
        snap_at = head.find("sourceCache.readSnapshot(")
        live_at = head.find("readSysfsLive(sourceCache)")
        if snap_at < 0 or live_at < 0:
            fails.append("readStatus 里没有同时读快照和 sysfs")
        elif snap_at > live_at:
            fails.append("readStatus 应先读共享快照再读 sysfs（当前 sysfs 更早）")
        need("pickLiveSource(", "三路数据源取舍", head)
        need("ROOT_WITH_LIVE", "缺字段实时值与 root 基底合并", head)
        need("PARTIAL_LIVE", "无基底时明确标部分字段", head)
        need("hasLiveCore(live)", "关键字段齐全判定", head)
        need("rootBaseCached()", "root 基底限频缓存", head)
        need("if (out.isEmpty()) cache.markUnreadable(bat, SOURCE_RETRY_MS)", "整组不可读时整组退避")

    if "file.isFile" in bridge:
        fails.append("bridge 不应再用 File.isFile 预检（会把 EACCES 吞成不存在）")
    if "File(bat).isDirectory" in bridge:
        fails.append("bridge 不应再用 isDirectory 预检目录（会吞权限）")
    if "permissive" in bridge or "setenforce" in bridge:
        fails.append("不允许通过 SELinux permissive 绕过")
    if "pm uninstall" in bridge:
        fails.append("bridge 不应卸载 App")

    root_marker = "    private suspend fun runAsRoot("
    if root_marker not in bridge:
        fails.append("runAsRoot 必须可挂起，阻塞读取不能直接包 withTimeout")
    else:
        root = bridge.split(root_marker, 1)[1].split("\n}\n", 1)[0]
        need("withTimeout(30_000L) {", "一般 Root 命令 30 秒超时", root)
        need("suspendCancellableCoroutine { continuation ->", "阻塞读取与可取消等待分离", root)
        need("Dispatchers.IO.dispatch(continuation.context, Runnable {", "阻塞读取在 IO 工作线程运行", root)
        need("continuation.invokeOnCancellation { processRef.get()?.destroy() }",
             "超时取消销毁已启动的进程", root)
        need("processRef.set(process)", "记录待销毁进程", root)
        need("if (!continuation.isActive) {\n                                process.destroy()\n",
             "超时与进程启动竞争时销毁新进程", root)
        need("val code = process.waitFor()", "正常结束读取真实退出码", root)
        need("continuation.resume(ExecResult(output.trim(), code))", "正常结果保留输出与退出码", root)
        need("catch (_: kotlinx.coroutines.TimeoutCancellationException)", "超时转为失败结果", root)
        need('ExecResult("Root 命令超时（30 秒）", -1)', "超时返回明确失败而非成功", root)
        if "destroyForcibly" in root:
            fails.append("runAsRoot 不应调用 API26 才有的 destroyForcibly（App 支持 API23）")

    need("class NodeDenied", "权限拒绝时中断本轮采样的标记", cache)
    need("fun readNode(", "节点读取策略", cache)
    need("fun readSnapshot(", "快照读取策略", cache)
    need("fun isDenied(", "EACCES/SecurityException 判定", cache)
    need('msg.contains("EACCES")', "SELinux 拒绝的 EACCES 识别", cache)
    need("fun isMissing(", "ENOENT 与权限拒绝区分", cache)
    need("hasLiveCore(", "关键字段齐全判定", cache)
    need("pickLiveSource(", "三路取舍函数", cache)
    need(
        "markUnreadable(path, if (isMissing(t)) missingRetryMs else deniedRetryMs)",
        "快照异常按 ENOENT/其它分别退避（权限拒绝的 60s 不会被 5s 覆盖）",
        cache,
    )
    if "it.readText()" not in cache:
        fails.append("StatusSourceCache 应直接 readText（否则 EACCES 被预检吞掉）")
    if cache.count("markUnreadable(") < 4:
        fails.append("冷却登记点太少（节点/快照/异常都要覆盖）")
    return fails


def check_status_source_behavior():
    """用真实 JVM 跑 StatusSourceCache 行为测试，代替纯文本断言。"""
    if not STATUS_TEST_DRIVER.is_file():
        return ["缺少 scripts/status_source_test.py"]
    proc = subprocess.run(
        [sys.executable, str(STATUS_TEST_DRIVER)], capture_output=True, text=True,
    )
    out = (proc.stdout + proc.stderr).strip()
    if proc.returncode != 0:
        return ["动态行为测试未通过（rc=%d）：\n%s" % (proc.returncode, out[-2000:])]
    if "ALL PASS" not in out:
        return ["动态行为测试未输出 ALL PASS：\n%s" % out[-2000:]]
    return []


def check_root_command_behavior():
    """缓存编译器编译真实方法体；仅替换 su 为 sh，保留完整 30 秒定时。"""
    from status_source_test import compiler_classpath, newest_jar

    jars = [newest_jar(group, artifact) for group, artifact in [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable"),
        ("org.jetbrains.kotlin", "kotlin-stdlib"),
        ("org.jetbrains.kotlin", "kotlin-reflect"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
        ("org.jetbrains", "annotations"),
    ]]
    java = shutil.which("java")
    if not java or not all(jars):
        return ["SKIP 缺少缓存 Kotlin 编译器/JVM，无法实测 Root 超时"]
    bridge = BRIDGE.read_text()
    marker = "    private suspend fun runAsRoot("
    if marker not in bridge:
        return ["找不到可挂起的 runAsRoot，无法实测 Root 超时"]
    method = marker + bridge.split(marker, 1)[1].split("\n}\n", 1)[0]
    if method.count('ProcessBuilder("su",') != 1:
        return ["无法确认唯一 su 启动点，拒绝运行非隔离 Root 测试"]
    method = method.replace('ProcessBuilder("su",', 'ProcessBuilder("sh",')
    common = BRIDGE.parents[6] / "commonMain/kotlin/com/hyperos/xj/runtime/ToolboxRuntimeBridge.kt"
    common_source = common.read_text()
    result_type = "data class ExecResult(" + common_source.split("data class ExecResult(", 1)[1].split("expect fun", 1)[0]
    engine = "object Engine {" + common_source.split("object Engine {", 1)[1].split("internal fun parseKv", 1)[0]
    source = r'''
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlin.coroutines.resume

object AppVisibility { object visible { val value = true } }
''' + result_type + engine + method + r'''

fun main(args: Array<String>) = runBlocking {
    for ((command, code, output) in listOf(
        Triple("printf '  ok  \\n'; exit 0", 0, "ok"),
        Triple("printf failure >&2; exit 7", 7, "failure"),
    )) {
        val result = runAsRoot(command)
        check(result.exitCode == code && result.output == output) { result }
        check(result.success == (code == 0)) { result }
        println("PASS Root 正常结束：退出码 $code，输出及 success 正确")
    }
    val pidFile = File(args.single())
    val started = System.nanoTime()
    val result = runAsRoot("echo \$\$ > '${pidFile.absolutePath}'; exec sleep 60")
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    val process = ProcessHandle.of(pidFile.readText().trim().toLong()).orElse(null)
    try {
        check(result.exitCode == -1 && !result.success) { result }
        check(result.output == "Root 命令超时（30 秒）") { result }
        check(elapsedMs in 29_000L..35_000L) { "超时耗时异常：${elapsedMs}ms" }
        withTimeout(2_000L) {
            while (process?.isAlive == true) delay(10L)
        }
        println("PASS Root 超时：${elapsedMs}ms，进程已终止，返回 -1/失败结果")
    } finally {
        if (process?.isAlive == true) process.destroyForcibly()
    }
    println("ALL PASS Root 命令行为 3/3")
}
'''
    with tempfile.TemporaryDirectory(prefix="xj-root-test-") as tmp:
        out = Path(tmp)
        test = out / "RootCommandTest.kt"
        test.write_text(source)
        runtime_cp = compiler_classpath([jars[1], jars[3], jars[4]])
        compiled = subprocess.run([
            java, "-cp", compiler_classpath(jars), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-classpath", runtime_cp,
            "-d", str(out), str(test),
        ], capture_output=True, text=True, timeout=60)
        if compiled.returncode != 0:
            return ["Root 隔离 JVM 编译失败：\n" + compiled.stdout + compiled.stderr]
        ran = subprocess.run([
            java, "-cp", str(out) + os.pathsep + runtime_cp,
            "RootCommandTestKt", str(out / "command.pid"),
        ], capture_output=True, text=True, timeout=45)
        output = (ran.stdout + ran.stderr).strip()
        if ran.returncode != 0 or "ALL PASS Root 命令行为 3/3" not in output:
            return ["Root 隔离 JVM 行为失败（rc=%d）：\n%s" % (ran.returncode, output)]
        print(output)
    return []


def _run_cases():
    results = []

    c = run_installer("install_fail", env_extra={
        "PM_INSTALL_OK": "0",
        "PM_INSTALL_OUT": "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]",
        "DUMPSYS_VC": "368",
        "DUMPSYS_VN": "3.1.58",
    })
    c.has("App 安装失败", "安装失败：要报失败")
    c.has("INSTALL_FAILED_UPDATE_INCOMPATIBLE", "安装失败：要显示 pm 的失败原因")
    c.lacks("与模块版本一致", "安装失败：不能报已装好")
    c.eq(len(c.calls("pm uninstall")), 0, "安装失败：不得自动卸载旧 App")
    c.eq(len(c.calls("pm install")), 1, "安装失败：pm install 只尝试一次（不静默重试）")
    results.append(("pm install 失败：原因可见、不卸载、不报成功", c))

    c = run_installer("install_ok_match", env_extra={
        "PM_INSTALL_OK": "1", "DUMPSYS_VC": "368", "DUMPSYS_VN": "3.1.58",
    })
    c.has("与模块版本一致", "版本一致：应报一致")
    c.has("3.1.58 (368)", "版本一致：应显示实际版本")
    c.has("LSPosed", "版本一致：应保留 LSPosed 提示")
    results.append(("安装成功且版本与 module.prop 一致", c))

    c = run_installer("install_ok_stale", env_extra={
        "PM_INSTALL_OK": "1", "DUMPSYS_VC": "367", "DUMPSYS_VN": "3.1.57",
    })
    c.has("版本与模块不一致", "包没被替换：要报不一致")
    c.has("367", "包没被替换：要显示已装版本")
    c.lacks("与模块版本一致", "包没被替换：不能报一致")
    results.append(("安装返回成功但版本是旧的", c))

    c = run_installer("install_no_dumpsys", env_extra={
        "PM_INSTALL_OK": "1", "DUMPSYS_OK": "0",
    })
    c.has("读不到已装版本", "dumpsys 不可用：要说读不到版本")
    c.lacks("与模块版本一致", "dumpsys 不可用：不能报一致")
    results.append(("dumpsys 不可用不假称成功", c))

    c = run_installer("install_apk_missing", drop_apk=True, env_extra={"PM_INSTALL_OK": "1"})
    c.has("模块内缺少 APK", "缺 APK：要提示包不完整")
    c.lacks("与模块版本一致", "缺 APK：不能报已装好")
    c.eq(len(c.calls("pm install")), 0, "缺 APK：不应调 pm install")
    results.append(("模块内缺 APK 不报成功", c))

    c = run_installer("install_ok_no_path", env_extra={
        "PM_INSTALL_OK": "1", "PM_PATH_OK": "0",
    })
    c.has("查不到 com.hyperos.xj", "pm path 查不到：要提示手动安装")
    c.lacks("与模块版本一致", "pm path 查不到：不能报成功")
    results.append(("pm 报成功但查不到包", c))

    for name, platform, existing in [
        ("mtk_first", "mt6989", None),
        ("mtk_upgrade", "mt6989", '{"charge_mode":true,"charge_level":"level_4"}'),
        ("qcom_first", "sm8750", None),
    ]:
        c = run_installer(name, {"TEST_PLATFORM": platform}, existing_settings=existing)
        settings = (c.sb / "data/adb/rezhale_toolbox/config/settings.json").read_text()
        expected = existing or (CUSTOMIZE.parent / (
            "default_settings.mtk.json" if platform.startswith("mt") else "default_settings.json"
        )).read_text()
        c.eq(json.loads(settings), json.loads(expected), "实际配置采用平台模板，升级保留用户配置")
        results.append((name + " 配置初始化", c))

    return results


def uninstall_cases():
    source = (CUSTOMIZE.parent / "uninstall.sh").read_text()
    backup = '_backup_msg=""' + source.split('_backup_msg=""', 1)[1].split('_restore_ok=""', 1)[0]
    steps = source.split('_restore_ok=""', 1)[1].split('rm -f "$DATA_DIR/state/daemon.pid"', 1)[0]
    report = source.split('_body=$( {', 1)[1].split('  echo "不自动回滚:', 1)[0]
    cleanup = next(line for line in source.splitlines() if '&& rm -rf "$DATA_DIR"' in line)
    results = []
    for name, failed_step, backup_mode in [
        ("all_ok", "", "ok"),
        ("apply_failed", "apply", "ok"),
        ("all_failed", "all", "ok"),
        ("tar_fallback", "", "zip_fail"),
        ("backup_command_failed", "", "fail"),
        ("backup_empty", "", "empty"),
        ("backup_missing", "", "missing"),
        ("backup_unwritable", "", "unwritable"),
    ]:
        sb = SESSION_ROOT / name
        data = sb / "data"
        data.mkdir(parents=True)
        (data / "config").mkdir()
        (data / "config/settings.json").write_text('{"preserve":true}')
        download = sb / "Download"
        download.mkdir()
        if backup_mode == "unwritable":
            # 用目录占据目标文件名，避免 root 下 chmod 无法可靠模拟写入失败。
            for suffix in ("zip", "tar.gz"):
                (download / ("rezhale_toolbox_uninstall_test." + suffix)).mkdir()
        (sb / "bin").mkdir()
        for tool in ("zip", "tar"):
            stub = sb / "bin" / tool
            stub.write_text('''#!/bin/sh
printf '%s\\n' "${0##*/}" >> "$BACKUP_LOG"
case "$BACKUP_MODE" in
  fail) printf partial > "$2"; exit 1 ;;
  zip_fail)
    if [ "${0##*/}" = zip ]; then printf partial > "$2"; exit 1; fi ;;
  empty) : > "$2"; exit $? ;;
  missing) exit 0 ;;
esac
printf backup > "$2"
''')
            stub.chmod(0o755)
        engine = sb / "engine"
        engine.mkdir()
        (engine / "xjctl.sh").write_text('''echo "$*" >> "$CALL_LOG"
[ "$FAILED_STEP" != all ] && [ "$*" != "$FAILED_STEP" ]
''')
        script = r'''timeout() {
  printf 'stop %s\n' "$6" >> "$CALL_LOG"
}
''' + backup.replace("/sdcard/Download", str(download))
        script += '_restore_ok=""' + steps + cleanup + '\n' + report
        script += '\n[ -z "$_restore_failed" ]\n'
        env = dict(os.environ, MODDIR=str(sb), DATA_DIR=str(data),
                   CALL_LOG=str(sb / "calls.log"), FAILED_STEP=failed_step,
                   PATH=str(sb / "bin") + os.pathsep + os.environ.get("PATH", "/usr/bin:/bin"),
                   BACKUP_LOG=str(sb / "backup_calls.log"), BACKUP_MODE=backup_mode,
                   _ts="test")
        proc = subprocess.run(["sh", "-c", script], env=env, capture_output=True, text=True)
        c = Case(sb, proc)
        c.eq(proc.returncode, 1 if failed_step else 0, "还原失败返回非零")
        backup_ok = backup_mode in ("ok", "zip_fail")
        keep_data = bool(failed_step) or not backup_ok
        c.eq(data.exists(), keep_data, "仅还原和备份均成功时清理数据")
        if keep_data:
            settings = data / "config/settings.json"
            c.eq(settings.read_text() if settings.exists() else None,
                 '{"preserve":true}', "保留原始用户配置")
        if backup_ok:
            suffix = "tar.gz" if backup_mode == "zip_fail" else "zip"
            archive = download / ("rezhale_toolbox_uninstall_test." + suffix)
            c.eq(archive.is_file() and archive.stat().st_size > 0, True, "成功备份产物存在且非空")
            c.has("已备份:", "备份成功应报告产物")
            c.lacks("备份失败:", "成功备份不报失败")
        else:
            c.has("备份失败:", "失败备份应明确报告")
            c.has("已保留 " + str(data), "失败备份应报告用户数据保留位置")
            c.lacks("已备份:", "失败备份不能假报成功")
        backup_calls = (sb / "backup_calls.log").read_text().splitlines()
        c.eq(backup_calls, ["zip"] if backup_mode == "ok" else ["zip", "tar"],
             "zip 失败或产物无效时尝试 tar")
        c.eq(len(c.calls("")), 9, "失败后仍尝试其余还原步骤与进程停止")
        c.lacks("已还原:", "不把命令结果当作节点已验证")
        if failed_step:
            c.has("还原失败:", "失败项应进入报告")
        else:
            c.lacks("还原失败:", "全部成功不报告失败")
        calls = c.calls("")
        c.eq(calls[-5:], ["setting set enabled false", "apply", "stop boot",
                          "stop xjdaemon", "battery pps restore"],
             "先禁用并恢复默认档，再停止看护和守护，最后恢复 PPS")
        results.append((name + " 卸载还原结果", c))
    return results


def main():
    global SESSION_ROOT
    bridge_fails = check_bridge_source()
    SESSION_ROOT = Path(tempfile.mkdtemp(prefix="xj-install-check-"))
    try:
        results = _run_cases() + uninstall_cases()
    finally:
        shutil.rmtree(SESSION_ROOT, ignore_errors=True)
    behavior_fails = check_status_source_behavior()
    root_fails = check_root_command_behavior()

    failed = 0
    if bridge_fails:
        failed += 1
        print("FAIL bridge+StatusSourceCache 读源策略")
        for item in bridge_fails:
            print("     - %s" % item)
    else:
        print("PASS bridge+StatusSourceCache 读源策略（快照优先 / 冷却 / 三路取舍 / 无 permissive）")

    if behavior_fails:
        failed += 1
        print("FAIL 数据源动态行为（真实 JVM）")
        for item in behavior_fails:
            print("     - %s" % item)
    else:
        print("PASS 数据源动态行为（真实 JVM：EACCES/ENOENT/60不被5覆盖/部分live/真0/恢复）")

    if root_fails:
        failed += 1
        print("FAIL Root 命令动态行为（隔离 JVM）")
        for item in root_fails:
            print("     - %s" % item)
    else:
        print("PASS Root 命令动态行为（隔离 JVM：退出码 / 30 秒超时 / 进程终止 / 失败结果）")

    for name, case in results:
        if case.fails:
            failed += 1
            print("FAIL %s" % name)
            for item in case.fails:
                print("     - %s" % item)
        else:
            print("PASS %s" % name)

    total = len(results) + 3
    print("%d/%d 通过" % (total - failed, total))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
