#!/usr/bin/env python3
"""App 状态读源 + 模块安装判定的回归。

两部分：
1) 源码级检查 ToolboxRuntimeBridge.android.kt + StatusSourceCache.kt：面板 700ms 读状态时先取共享快照，
   读不到的 sysfs 节点/快照文件要进冷却，不再每拍刷 SELinux denial；有权限时仍直读。
   NodeDenied/冷却等策略已移到 StatusSourceCache.kt，断言跟着读两个文件。
2) 用真实 JVM 跑 scripts/status_source_test.py（动态行为，替代纯文本检查）。
3) 沙箱里跑真实 customize.sh：pm/dumpsys 用桩，路径改到临时目录，断言安装失败/
   版本不一致/读不到版本时都不报「已安装且与模块版本一致」，且绝不自动卸载旧 App。

用法：python3 scripts/app_status_install_regression.py
"""

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
DEFAULT_SETTINGS = "{}\n"


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


def run_installer(name, env_extra=None, drop_apk=False):
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
    (sb / "moddir" / "default_settings.json").write_text(DEFAULT_SETTINGS)
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

    return results


def main():
    global SESSION_ROOT
    bridge_fails = check_bridge_source()
    SESSION_ROOT = Path(tempfile.mkdtemp(prefix="xj-install-check-"))
    try:
        results = _run_cases()
    finally:
        shutil.rmtree(SESSION_ROOT, ignore_errors=True)
    behavior_fails = check_status_source_behavior()

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

    for name, case in results:
        if case.fails:
            failed += 1
            print("FAIL %s" % name)
            for item in case.fails:
                print("     - %s" % item)
        else:
            print("PASS %s" % name)

    total = len(results) + 2
    print("%d/%d 通过" % (total - failed, total))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
