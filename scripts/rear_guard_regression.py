#!/usr/bin/env python3
"""背屏支持守卫回归：无背屏设备不反复安装/广播/写配置，判据有缓存且可重探。

在沙箱里跑真实 engine/lib/rear.sh，pm/am/dumpsys 用桩记录调用；不碰真实
/data、/storage，也不执行任何设备命令。

用法：python3 scripts/rear_guard_regression.py
"""

import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
# 每次运行独立的沙箱根（本轮结束只清自己这个目录），避开与 review/并发实例互删。
SESSION_ROOT = None

PM_STUB = """#!/bin/sh
echo "pm $*" >> "$CALL_LOG"
case "$1" in
  path)
    case "$2" in
      com.xiaomi.subscreencenter) [ "${FAKE_PM_SUB:-0}" = "1" ] && { echo "package:/system/app/SubScreen/SubScreen.apk"; exit 0; } ;;
      com.hyperos.xj) [ "${FAKE_PM_CARD:-1}" = "1" ] && { echo "package:/data/app/xj/base.apk"; exit 0; } ;;
    esac
    exit 1 ;;
  install)
    [ "${FAKE_INSTALL_OK:-1}" = "1" ] || exit 1
    exit 0 ;;
esac
exit 0
"""

AM_STUB = """#!/bin/sh
echo "am $*" >> "$CALL_LOG"
case "$*" in
  *broadcast*) [ "${FAKE_AM_MKDIR:-0}" = "1" ] && mkdir -p "$FAKE_EXT_DIR" ;;
  *start-foreground-service*) [ "${FAKE_AM_START_FAIL:-0}" = "1" ] && exit 1 ;;
esac
[ "${FAKE_AM_FAIL:-0}" = "1" ] && exit 1
exit 0
"""

DUMPSYS_STUB = """#!/bin/sh
echo "dumpsys $*" >> "$CALL_LOG"
case "$1" in
  package) printf '%s\\n' "${FAKE_PKG_DUMP:-}" ;;
  display) printf '%s\\n' "${FAKE_DISPLAY_TEXT:-}" ;;
esac
exit 0
"""

STAT_STUB = """#!/bin/sh
echo "stat $*" >> "$CALL_LOG"
if [ -n "${FAKE_STAT_UID:-}" ]; then printf '%s\\n' "$FAKE_STAT_UID"; exit 0; fi
exec "${REAL_STAT:-/usr/bin/stat}" "$@"
"""

PRELUDE = """PATH="@SB@/bin:$PATH"
export PATH
STATE="@SB@/state"; LOGDIR="@SB@/log"; MODDIR="@SB@/moddir"
mkdir -p "$STATE" "$LOGDIR"
cfg_get() {
  _ck=$1; _cd=$2
  _cv=$(sed -n "s/^${_ck}=//p" "@SB@/cfg" 2>/dev/null | tail -n1)
  [ -n "$_cv" ] && printf '%s' "$_cv" || printf '%s' "$_cd"
}
cfg_set() {
  sed -i "/^$1=/d" "@SB@/cfg" 2>/dev/null
  printf '%s=%s\\n' "$1" "$2" >> "@SB@/cfg"
}
. "@ENGINE@/lib/rear.sh"
CARD_CONF_DIR="@SB@/ext"
CARD_CONF="$CARD_CONF_DIR/rear.conf"
RS_HOOK_CONF="@SB@/hook/rear.conf"
"""


class Case:
    def __init__(self, sb, proc):
        self.sb = sb
        self.proc = proc
        self.fails = []

    def calls(self, prefix):
        log = (self.sb / "calls.log").read_text(errors="replace").splitlines()
        return [l for l in log if l.startswith(prefix)]

    def engine_log(self):
        p = self.sb / "log" / "rearscreen.log"
        return p.read_text(errors="replace") if p.exists() else ""

    def check(self, cond, what):
        if not cond:
            self.fails.append(what)

    def eq(self, got, want, what):
        self.check(got == want, "%s（期望 %s，实际 %s）" % (what, want, got))


def run_case(name, body, sub=0, card=1, display="", readonly=False,
             cfg_init="", install_ok=1, keep=False, pre=None,
             am_fail=0, am_mkdir=0, am_start_fail=0,
             stat_uid="", pkg_dump="", config_changed=0, now=None):
    sb = SESSION_ROOT / name
    if not keep:
        if sb.exists():
            shutil.rmtree(sb)
        for d in ("bin", "state", "log", "moddir", "hook"):
            (sb / d).mkdir(parents=True, exist_ok=True)
        (sb / "cfg").write_text(cfg_init)
        (sb / "calls.log").write_text("")
        for stub, text in (("pm", PM_STUB), ("am", AM_STUB),
                           ("dumpsys", DUMPSYS_STUB), ("stat", STAT_STUB)):
            p = sb / "bin" / stub
            p.write_text(text)
            p.chmod(0o755)
    if pre:
        pre(sb)
    env = dict(os.environ)
    env.update({
        "CALL_LOG": str(sb / "calls.log"),
        "FAKE_PM_SUB": str(sub),
        "FAKE_PM_CARD": str(card),
        "FAKE_DISPLAY_TEXT": display,
        "FAKE_INSTALL_OK": str(install_ok),
        "FAKE_AM_FAIL": str(am_fail),
        "FAKE_AM_MKDIR": str(am_mkdir),
        "FAKE_AM_START_FAIL": str(am_start_fail),
        "FAKE_EXT_DIR": str(sb / "ext"),
        "FAKE_STAT_UID": stat_uid,
        "FAKE_PKG_DUMP": pkg_dump,
        "XJ_CONFIG_CHANGED": str(config_changed),
        "REAL_STAT": shutil.which("stat") or "/usr/bin/stat",
    })
    if now is not None:
        env["XJ_NOW"] = str(now)
    if readonly:
        env["XJ_READONLY"] = "1"
    else:
        env.pop("XJ_READONLY", None)
    script = PRELUDE.replace("@SB@", str(sb)).replace("@ENGINE@", str(ENGINE)) + body
    proc = subprocess.run(["sh", "-c", script], env=env, capture_output=True, text=True)
    return Case(sb, proc)


def no_side_effects(c, label):
    c.eq(len(c.calls("am ")), 0, label + "：不应有 am 调用")
    c.eq(len(c.calls("pm install")), 0, label + "：不应 pm install")
    c.check(not (c.sb / "ext").exists(), label + "：不应创建外部配置目录")
    c.check(not (c.sb / "hook" / "rear.conf").exists(), label + "：不应写 hook 兜底配置")


def _run_cases():
    results = []

    # 1) 无背屏 + 配置误设为 1：连续 5 拍无 am/install/写配置；dumpsys 里的 Display #1
    #    （外接屏/投屏）不能当背屏依据。
    c = run_case("unsupported_cfg_on", 'i=0; while [ $i -lt 5 ]; do rear_tick; i=$((i+1)); done',
                 sub=0, cfg_init="rearscreen=1\n",
                 display="Display Devices: displayId 1, uniqueId local:1, Display #1")
    no_side_effects(c, "无背屏+配置1")
    c.eq(c.engine_log().count("subscreen not supported"), 1, "无背屏+配置1：日志只应出现一次")
    c.check(len(c.calls("pm path")) <= 1, "无背屏+配置1：pm path 应走缓存（≤1 次）")
    results.append(("无背屏+配置1 连续 tick 无 am/install/写 rear.conf", c))

    # 2) 无背屏 + 配置 0：连续 tick 且重复 off，不广播、不刷日志。
    c = run_case("unsupported_cfg_off", 'i=0; while [ $i -lt 5 ]; do rear_tick; i=$((i+1)); done; rear_stop; rear_stop',
                 sub=0, cfg_init="rearscreen=0\n")
    no_side_effects(c, "无背屏+配置0")
    c.eq(c.engine_log().count("lsposed overlay disabled"), 0, "无背屏+配置0：重复 off 不应写日志")
    results.append(("无背屏+配置0 重复 tick/off 幂等", c))

    # 3) 无背屏 + 旧 RS_STATE/on 残留：一次性安全收尾（广播 + stopservice 各一次），之后安静。
    def seed_on(sb):
        (sb / "state" / "rear").mkdir(parents=True, exist_ok=True)
        (sb / "state" / "rear" / "on").write_text("")

    c = run_case("unsupported_stale_on", 'i=0; while [ $i -lt 3 ]; do rear_tick; i=$((i+1)); done',
                 sub=0, cfg_init="rearscreen=1\n", pre=seed_on)
    c.eq(len(c.calls("am broadcast")), 1, "旧 on 残留：只应广播一次关闭")
    c.eq(len(c.calls("am stopservice")), 1, "旧 on 残留：只应停服务一次")
    c.check(not (c.sb / "state" / "rear" / "on").exists(), "旧 on 残留：on 标记应清除")
    c.check(not (c.sb / "ext").exists(), "旧 on 残留：不应创建外部配置目录")
    results.append(("旧 on 残留一次性收尾，不每拍发 off", c))

    # 4) 有 subscreencenter 包但 dumpsys 无 Display #1（且熄屏）：仍可开启。
    c = run_case("supported_no_display1",
                 'i=0; while [ $i -lt 3 ]; do rear_tick; i=$((i+1)); done',
                 sub=1, cfg_init="rearscreen=1\n", display="Display Devices: displayId 0, uniqueId local:0")
    c.check((c.sb / "state" / "rear" / "on").exists(), "有包无 Display#1：应进入开启态")
    conf = c.sb / "ext" / "rear.conf"
    c.check(conf.exists() and "enabled=1" in conf.read_text(), "有包无 Display#1：rear.conf 应为 enabled=1")
    c.check(len(c.calls("am broadcast")) >= 1, "有包无 Display#1：应下发一次控制广播")
    c.eq(len(c.calls("dumpsys display")), 0, "有包无 Display#1：判据不应再读 dumpsys display")
    c.eq(len(c.calls("pm install")), 0, "有包无 Display#1：APK 已在装，不应重装")
    results.append(("支持包但无 Display #1 仍可开启", c))

    # 5) 缓存：60 秒内连续取判据只探一次 pm。
    c = run_case("cache_pm_frequency",
                 'a=$(subscreen_supported_cached); b=$(subscreen_supported_cached); echo "v=$a$b"',
                 sub=0)
    c.check("v=00" in c.proc.stdout, "缓存：两次判据应为 0/0，实际 %r" % c.proc.stdout.strip())
    c.eq(len(c.calls("pm path")), 1, "缓存：60 秒内只应探一次 pm path")
    results.append(("判据缓存降低 pm 频率", c))

    # 6) 后续包安装可重探：缓存过期后自动重新探测并启用。
    c = run_case("reprobe_after_install", 'echo "v=$(subscreen_supported_cached)"', sub=0, cfg_init="rearscreen=1\n")
    ts = c.sb / "state" / "rear" / "supported.ts"
    ts.write_text("0")
    c2 = run_case("reprobe_after_install", 'echo "v=$(subscreen_supported_cached)"; rear_tick', sub=1, keep=True)
    c2.check("v=1" in c2.proc.stdout, "重探：缓存过期后应探到已安装，实际 %r" % c2.proc.stdout.strip())
    c2.check((c2.sb / "state" / "rear" / "on").exists(), "重探：探到支持后应能开启")
    results.append(("包后续安装可重探", c2))

    # 7) 只读模式（selfcheck）不落缓存。
    c = run_case("readonly_no_cache", 'echo "v=$(subscreen_supported_cached)"', sub=0, readonly=True)
    c.check("v=0" in c.proc.stdout, "只读：仍应返回判据")
    c.check(not (c.sb / "state" / "rear" / "supported").exists(), "只读：不应写支持缓存")
    results.append(("只读模式不写缓存", c))

    # 8) rear on：无背屏时拒绝，且不先写 rearscreen=1。
    c = run_case("rear_on_rejected", 'rear_enable; echo "rc=$?"', sub=0, cfg_init="")
    c.check("rc=4" in c.proc.stdout, "on 拒绝：应返回 4，实际 %r" % c.proc.stdout.strip())
    c.check("不支持" in c.proc.stdout, "on 拒绝：应给出无背屏提示")
    c.check("rearscreen=1" not in (c.sb / "cfg").read_text(), "on 拒绝：不应先写 rearscreen=1")
    no_side_effects(c, "on 拒绝")
    results.append(("rear on 无背屏拒绝不写配置", c))

    # 9) rear on：有包时正常开启。
    c = run_case("rear_on_supported", 'rear_enable; echo "rc=$?"; rear_tick', sub=1, cfg_init="")
    c.check("rc=0" in c.proc.stdout, "on 成功：应返回 0，实际 %r" % c.proc.stdout.strip())
    c.check("rearscreen=1" in (c.sb / "cfg").read_text(), "on 成功：应写 rearscreen=1")
    c.check((c.sb / "state" / "rear" / "on").exists(), "on 成功：应进入开启态")
    results.append(("rear on 有包正常开启", c))

    # 10) 遗留 enabled=1 但 RS_STATE/on 丢失：重复 off 首次也要清理一次，之后安静。
    def seed_leftover_conf(sb):
        (sb / "ext").mkdir(parents=True)
        (sb / "ext" / "rear.conf").write_text("enabled=1\nkeep_on=1\n")

    c = run_case("stop_leftover_conf", 'rear_stop; rear_stop; rear_stop',
                 sub=1, cfg_init="rearscreen=0\n", pre=seed_leftover_conf)
    c.eq(len(c.calls("am broadcast")), 1, "遗留 enabled=1：应只广播一次")
    c.check("enabled=0" in (c.sb / "ext" / "rear.conf").read_text(), "遗留 enabled=1：应改写成 enabled=0")
    c.eq(c.engine_log().count("lsposed overlay disabled"), 1, "遗留 enabled=1：日志只应出现一次")
    results.append(("遗留 enabled=1 重复 off 不漏首次清理", c))

    # 11) 无背屏 + 遗留 enabled=1：同样只清理一次，不创建新目录。
    c = run_case("stop_leftover_conf_unsupported", 'rear_stop; rear_stop',
                 sub=0, cfg_init="rearscreen=0\n", pre=seed_leftover_conf)
    c.eq(len(c.calls("am broadcast")), 1, "无背屏遗留配置：应只广播一次")
    c.check("enabled=0" in (c.sb / "ext" / "rear.conf").read_text(), "无背屏遗留配置：应改写成 enabled=0")
    results.append(("无背屏遗留配置一次性清理", c))

    # 12) 开机恢复：无 clock_disabled 残留时不无条件 pm enable。
    c = run_case("boot_recover_clean", 'rear_boot_recover', sub=1, cfg_init="rearscreen=0\n")
    c.eq(len(c.calls("pm enable")), 0, "boot 恢复：无残留时不应 pm enable")
    results.append(("boot 恢复无残留不碰 pm", c))

    # 13) 开机恢复：有真实 clock_disabled 时恢复原生时钟。
    def seed_clock_disabled(sb):
        (sb / "state" / "rear").mkdir(parents=True)
        (sb / "state" / "rear" / "clock_disabled").write_text("com.xiaomi.subscreencenter/.SubScreenLauncher\n")

    c = run_case("boot_recover_clock", 'rear_boot_recover', sub=1, cfg_init="rearscreen=0\n",
                 pre=seed_clock_disabled)
    c.eq(len(c.calls("pm enable")), 1, "boot 恢复：应恢复一次原生时钟")
    c.check(not (c.sb / "state" / "rear" / "clock_disabled").exists(), "boot 恢复：clock_disabled 标记应清除")
    results.append(("boot 恢复只在有 clock_disabled 时 pm enable", c))

    # 14) 无背屏 + 配置 1 + 主 conf 仍 enabled=1 但 on 已丢失：首次 tick 也要清理一次。
    def seed_main_on(sb):
        (sb / "ext").mkdir(parents=True)
        (sb / "ext" / "rear.conf").write_text("enabled=1\nkeep_on=1\n")

    c = run_case("unsupported_mainconf_no_on", 'i=0; while [ $i -lt 3 ]; do rear_tick; i=$((i+1)); done',
                 sub=0, cfg_init="rearscreen=1\n", pre=seed_main_on)
    c.eq(len(c.calls("am broadcast")), 1, "无背屏主 conf 残留：应只广播一次")
    c.eq(len(c.calls("am stopservice")), 0, "无背屏主 conf 残留：无 on 标记不应 stopservice")
    c.check("enabled=0" in (c.sb / "ext" / "rear.conf").read_text(), "无背屏主 conf 残留：应改写 enabled=0")
    c.eq(c.engine_log().count("lsposed overlay disabled"), 1, "无背屏主 conf 残留：清理日志只一次")
    results.append(("无背屏 cfg1 主 conf 残留无 on 清一次", c))

    # 15) 同上但配置已为 0（关闭态 tick 分支）：也不能漏。
    c = run_case("unsupported_mainconf_cfg0", 'i=0; while [ $i -lt 3 ]; do rear_tick; i=$((i+1)); done',
                 sub=0, cfg_init="rearscreen=0\n", pre=seed_main_on)
    c.eq(len(c.calls("am broadcast")), 1, "无背屏 cfg0 主 conf 残留：应只广播一次")
    c.check("enabled=0" in (c.sb / "ext" / "rear.conf").read_text(), "无背屏 cfg0 主 conf 残留：应改写 enabled=0")
    c.check(not (c.sb / "state" / "rear" / "on").exists(), "无背屏 cfg0 主 conf 残留：不应进入开态")
    results.append(("无背屏 cfg0 tick 主 conf 残留清一次", c))

    # 16) 仅 hook 兜底 conf enabled=1（主目录不存在）：也要关掉，且不建主目录。
    def seed_hook_on(sb):
        (sb / "hook").mkdir(parents=True, exist_ok=True)
        (sb / "hook" / "rear.conf").write_text("enabled=1\nkeep_on=1\n")

    c = run_case("unsupported_hookconf_only", 'i=0; while [ $i -lt 3 ]; do rear_tick; i=$((i+1)); done',
                 sub=0, cfg_init="rearscreen=1\n", pre=seed_hook_on)
    c.eq(len(c.calls("am broadcast")), 1, "仅 hook conf 残留：应只广播一次")
    c.check("enabled=0" in (c.sb / "hook" / "rear.conf").read_text(), "仅 hook conf 残留：应改写 enabled=0")
    c.check(not (c.sb / "ext").exists(), "仅 hook conf 残留：不应创建主配置目录")
    c.check(not (c.sb / "state" / "rear" / "on").exists(), "仅 hook conf 残留：不应进入开态")
    results.append(("仅 hook 兜底 conf 残留也清一次", c))

    # 17) 支持设备 + 配置 0 + 主 conf 仍 enabled=1：cfg0 tick 首次走幂等 stop，不启服务。
    c = run_case("supported_cfg0_leftover", 'i=0; while [ $i -lt 3 ]; do rear_tick; i=$((i+1)); done',
                 sub=1, cfg_init="rearscreen=0\n", pre=seed_main_on)
    c.eq(len(c.calls("am broadcast")), 1, "支持+cfg0 残留：应只广播一次")
    c.eq(len(c.calls("am start-foreground-service")), 0, "支持+cfg0 残留：不应启服务")
    c.check("enabled=0" in (c.sb / "ext" / "rear.conf").read_text(), "支持+cfg0 残留：应改写 enabled=0")
    results.append(("支持设备 cfg0 tick 残留清一次且不启服务", c))

    # 18) 支持设备 boot：无 on 但主 conf enabled=1，也要收尾一次。
    c = run_case("supported_boot_leftover", 'rear_boot_recover', sub=1, cfg_init="rearscreen=0\n",
                 pre=seed_main_on)
    c.eq(len(c.calls("am broadcast")), 1, "boot 残留：应只广播一次")
    c.check("enabled=0" in (c.sb / "ext" / "rear.conf").read_text(), "boot 残留：主 conf 应改写 enabled=0")
    c.check("enabled=0" in (c.sb / "hook" / "rear.conf").read_text(), "boot 残留：hook conf 应改写 enabled=0")
    results.append(("支持设备 boot 收尾残留 conf 一次", c))

    # 19) 开启顺序：先广播让 ControlReceiver 建目录；即使 FGS 会被 Java 早停也能开。
    c = run_case("enable_broadcast_first", 'rear_enable; echo "rc=$?"', sub=1,
                 cfg_init="", am_mkdir=1, am_start_fail=1)
    c.check("rc=0" in c.proc.stdout, "开启顺序：应成功开启，实际 %r" % c.proc.stdout.strip())
    am_calls = c.calls("am ")
    c.check(bool(am_calls) and "broadcast" in am_calls[0], "开启顺序：第一条 am 应为 broadcast")
    c.eq(len(c.calls("am start-foreground-service")), 0,
         "开启顺序：广播已建目录，不应再拉 FGS")
    conf = c.sb / "ext" / "rear.conf"
    c.check(conf.exists() and "enabled=1" in conf.read_text(), "开启顺序：rear.conf 应为 enabled=1")
    c.check("lsposed overlay ready" in c.engine_log(), "开启顺序：应记 ready")
    results.append(("开启先广播后等目录，FGS 早停也能开", c))

    # 20) 配置写失败：return 非 0，不写 ready。
    c = run_case("enable_config_write_fail",
                 'write_config() { return 1; }\nrear_ensure; echo "rc=$?"',
                 sub=1, cfg_init="", am_mkdir=1)
    c.check("rc=1" in c.proc.stdout, "写失败：应返回 1，实际 %r" % c.proc.stdout.strip())
    c.check("config write failed" in c.engine_log(), "写失败：应记 config write failed")
    c.check("lsposed overlay ready" not in c.engine_log(), "写失败：不应记 ready")
    results.append(("配置写失败返回非0且不报 ready", c))

    # 21) 无背屏收尾失败（广播失败）：不落 unsupported 标记，也不每拍广播。
    c = run_case("cleanup_fail_retry", 'i=0; while [ $i -lt 3 ]; do rear_tick; i=$((i+1)); done',
                 sub=0, cfg_init="rearscreen=1\n", pre=seed_main_on, am_fail=1)
    c.eq(len(c.calls("am broadcast")), 1, "收尾失败：60 秒内只应广播一次")
    c.check(not (c.sb / "state" / "rear" / "unsupported").exists(),
            "收尾失败：不应落 unsupported 标记")
    c.check((c.sb / "state" / "rear" / "cleanup_retry").exists(), "收尾失败：应记 cleanup_retry")
    c.eq(c.engine_log().count("cleanup incomplete"), 1, "收尾失败：日志只报一次")
    results.append(("收尾失败不落标记不刷屏", c))

    # 22) 重试窗口到期且广播恢复：补一次 off 后落 unsupported 标记。
    retry_ts = c.sb / "state" / "rear" / "cleanup_retry"
    retry_ts.write_text("0")
    c2 = run_case("cleanup_fail_retry", 'rear_tick', sub=0, cfg_init="rearscreen=1\n",
                  keep=True, am_fail=0)
    c2.eq(len(c2.calls("am broadcast")), 2, "重试成功：应补发一次 off（累计 2 次）")
    c2.check((c2.sb / "state" / "rear" / "unsupported").exists(),
             "重试成功：应收尾后落 unsupported 标记")
    c2.check(not (c2.sb / "state" / "rear" / "cleanup_retry").exists(),
             "重试成功：cleanup_retry 应删除")
    c2.eq(c2.engine_log().count("subscreen not supported"), 1, "重试成功：skipped 日志只一次")
    results.append(("重试成功后才落 unsupported 标记", c2))

    # 23) card_uid：App 数据目录属主可用时直接用，不 fork dumpsys。
    c = run_case("uid_from_stat", 'card_uid', sub=1, stat_uid="10123")
    c.eq(c.proc.stdout.strip(), "10123", "uid/stat：应返回目录属主")
    c.eq(len(c.calls("dumpsys package")), 0, "uid/stat：可用时不应 fork dumpsys")
    results.append(("card_uid 优先用 App 目录属主", c))

    # 24) card_uid：无目录属主时退 dumpsys 的 userId=。
    c = run_case("uid_from_dumpsys_user", 'card_uid', sub=1, stat_uid="",
                 pkg_dump="Packages:\n  Package [com.hyperos.xj] (x):\n    userId=10098")
    c.eq(c.proc.stdout.strip(), "10098", "uid/userId：应解析 userId")
    results.append(("card_uid 回退 dumpsys userId", c))

    # 25) card_uid：Android 17 只给 appId= 时仍可解析。
    c = run_case("uid_from_dumpsys_appid", 'card_uid', sub=1, stat_uid="",
                 pkg_dump="Packages:\n  Package [com.hyperos.xj] (x):\n    appId=10107")
    c.eq(c.proc.stdout.strip(), "10107", "uid/appId：应解析 appId")
    results.append(("card_uid 支持 appId（Android 17）", c))

    # 26) card_uid：多用户 uid（userId*100000+appId）合法应保留；appId 越界才拒绝。
    c = run_case("uid_multiuser", 'card_uid', sub=1, stat_uid="",
                 pkg_dump="    userId=1010099")
    c.eq(c.proc.stdout.strip(), "1010099", "uid/多用户：应保留合法多用户 uid")
    results.append(("card_uid 接受合法多用户 uid", c))

    c = run_case("uid_multiuser_stat", 'card_uid', sub=1, stat_uid="1010123")
    c.eq(c.proc.stdout.strip(), "1010123", "uid/多用户目录：应保留目录属主")
    results.append(("card_uid 接受多用户目录属主", c))

    c = run_case("uid_reject_out_of_range", 'card_uid', sub=1, stat_uid="",
                 pkg_dump="    userId=20500")
    c.eq(c.proc.stdout.strip(), "0", "uid/越界：appId 超出 10000-19999 应返回 0")
    results.append(("card_uid 拒绝越界 appId", c))

    c = run_case("uid_none", 'card_uid', sub=1, stat_uid="", pkg_dump="")
    c.eq(c.proc.stdout.strip(), "0", "uid/无数据：应返回 0")
    results.append(("card_uid 无来源返回 0", c))

    # 27) 开启态健康维护按真实时间节流：连续 5 拍只维护一次，不按拍数。
    c = run_case("health_interval", 'i=0; while [ $i -lt 5 ]; do rear_tick; i=$((i+1)); done',
                 sub=1, cfg_init="rearscreen=1\n", am_mkdir=1)
    c.eq(len(c.calls("am start-foreground-service")), 1, "健康维护：5 拍只应拉一次服务")
    c.check((c.sb / "state" / "rear" / "health.ts").exists(), "健康维护：应记 health.ts")
    results.append(("开启态健康维护按真实时间只做一次", c))

    # 28) 关闭态收尾后置静默：后续拍不读配置、不调 pm/am。
    def seed_idle(sb, ts="now"):
        d = sb / "state" / "rear"
        d.mkdir(parents=True, exist_ok=True)
        (d / "idle").write_text("")
        value = str(int(time.time())) if ts == "now" else ts
        (d / "idle.ts").write_text(value)

    c = run_case("idle_quiet", 'i=0; while [ $i -lt 5 ]; do rear_tick; i=$((i+1)); done',
                 sub=1, cfg_init="rearscreen=0\n", pre=lambda sb: seed_idle(sb))
    c.eq(len(c.calls("am ")), 0, "静默：不应有 am 调用")
    c.eq(len(c.calls("pm ")), 0, "静默：不应有 pm 调用")
    c.eq(len(c.calls("dumpsys")), 0, "静默：不应有 dumpsys 调用")
    results.append(("关闭态静默期内不读配置不调系统命令", c))

    # 29) 静默过期后重查：配置已改开时立即进入开启态。
    c = run_case("idle_expire_recheck",
                 'cfg_set rearscreen 1; rear_tick; echo "on=$([ -f \"$STATE/rear/on\" ] && echo 1 || echo 0)"',
                 sub=1, cfg_init="rearscreen=0\n", pre=lambda sb: seed_idle(sb, ts="0"))
    c.check("on=1" in c.proc.stdout, "重查：配置变开后应进入开启态，实际 %r" % c.proc.stdout.strip())
    c.check(len(c.calls("am broadcast")) >= 1, "重查：应下发控制广播")
    results.append(("静默过期后重查并响应配置变化", c))

    # 30) XJ_CONFIG_CHANGED=1：不等过期立即核验并开启。
    c = run_case("config_changed_immediate",
                 'cfg_set rearscreen 1; rear_tick; echo "on=$([ -f \"$STATE/rear/on\" ] && echo 1 || echo 0)"',
                 sub=1, cfg_init="rearscreen=0\n", pre=lambda sb: seed_idle(sb),
                 config_changed=1)
    c.check("on=1" in c.proc.stdout, "配置变更：应立即开启，实际 %r" % c.proc.stdout.strip())
    results.append(("XJ_CONFIG_CHANGED 立即核验不等静默期", c))

    # 32) 注入 XJ_NOW 单调秒：窗口内静默、过期后重查，与 date epoch 不混用。
    c = run_case("monotonic_idle", 'rear_tick', sub=1, cfg_init="rearscreen=0\n",
                 pre=lambda sb: seed_idle(sb, ts="100000"), now=100030)
    c.eq(len(c.calls("am ")), 0, "XJ_NOW：窗口内应静默")
    c.check((c.sb / "state" / "rear" / "idle").exists(), "XJ_NOW：静默标记应保留")

    c = run_case("monotonic_expire",
                 'cfg_set rearscreen 1; rear_tick; echo "on=$([ -f \"$STATE/rear/on\" ] && echo 1 || echo 0)"',
                 sub=1, cfg_init="rearscreen=0\n",
                 pre=lambda sb: seed_idle(sb, ts="100000"), now=100070)
    c.check("on=1" in c.proc.stdout, "XJ_NOW：过期后应重查并开启，实际 %r" % c.proc.stdout.strip())
    results.append(("XJ_NOW 单调秒驱动窗口", c))

    # 33) XJ_CONFIG_CHANGED 只穿透本次，不删除静默标记（不丢状态）。
    c = run_case("changed_keeps_idle", 'rear_tick', sub=1, cfg_init="rearscreen=0\n",
                 pre=lambda sb: seed_idle(sb), config_changed=1)
    c.check((c.sb / "state" / "rear" / "idle").exists(),
            "配置变更信号：不应删除静默标记")
    results.append(("配置变更信号不丢静默状态", c))

    # 34) 配置变更即时下发展示参数（不只补 enabled=1）。
    def seed_enabled(sb):
        (sb / "ext").mkdir(parents=True, exist_ok=True)
        (sb / "ext" / "rear.conf").write_text("enabled=1\nkeep_on=1\nfields=temp\n")
        d = sb / "state" / "rear"
        d.mkdir(parents=True, exist_ok=True)
        (d / "on").write_text("")
        (d / "health.ts").write_text(str(int(time.time())))

    c = run_case("changed_push_params", 'rear_tick', sub=1,
                 cfg_init="rearscreen=1\nrear_fields=soc\n",
                 pre=seed_enabled, config_changed=1)
    conf = {"exists": False, "text": ""}
    f = c.sb / "ext" / "rear.conf"
    conf["exists"] = f.exists()
    conf["text"] = f.read_text() if f.exists() else ""
    c.check(len(c.calls("am broadcast")) >= 1, "参数即时：应下发控制广播")
    c.check("fields=soc" in conf["text"], "参数即时：rear.conf 应写入新 fields")
    results.append(("配置变更即时下发展示参数", c))

    # 31) 只读模式（selfcheck）不落静默标记。
    c = run_case("readonly_no_idle", 'rear_tick', sub=1, cfg_init="rearscreen=0\n", readonly=True)
    c.check(not (c.sb / "state" / "rear" / "idle").exists(), "只读：不应写静默标记")
    results.append(("只读模式不写静默标记", c))

    # 35) tick 异常兜底：catch 分支必须先撤下卡片并释放唤醒锁，不能只安排重试。
    overlay_src = (ROOT / "ToolboxApp" / "androidApp" / "src" / "main" / "java"
                   / "com" / "hyperos" / "xj" / "rear" / "Overlay.java")
    case = Case(SESSION_ROOT, subprocess.CompletedProcess([], 0, "", ""))
    text = overlay_src.read_text(errors="replace") if overlay_src.exists() else ""
    tick_mark = "visible ? 2000L : 10000L"
    case.check(bool(text), "失败兜底：应能读取 Overlay.java")
    pos = text.find(tick_mark)
    case.check(pos >= 0, "失败兜底：应找到 tick 调度位置")
    if pos >= 0:
        catch_pos = text.find("} catch (Throwable ignored) {", pos)
        case.check(catch_pos >= 0, "失败兜底：应找到 tick 的 catch 分支")
        if catch_pos >= 0:
            block = text[catch_pos:catch_pos + 900]
            case.check("setVisibility(View.GONE)" in block, "失败兜底：catch 应隐藏卡片")
            case.check("WakeGuard.release()" in block, "失败兜底：catch 应释放唤醒锁")
    results.append(("tick 异常兜底撤卡并释放锁", case))

    failed = 0
    for title, case in results:
        if case.fails:
            failed += 1
            print("FAIL %s" % title)
            for f in case.fails:
                print("     - %s" % f)
            if case.proc.returncode != 0 or case.proc.stderr.strip():
                print("     stderr: %s" % case.proc.stderr.strip()[:400])
        else:
            print("PASS %s" % title)
    print("\n%d/%d 通过" % (len(results) - failed, len(results)))
    return 1 if failed else 0


def main():
    global SESSION_ROOT
    cache = Path(os.environ.get("XDG_CACHE_HOME") or (Path.home() / ".cache"))
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="xj-rear-guard-", dir=str(cache)) as tmp:
        SESSION_ROOT = Path(tmp)
        return _run_cases()


if __name__ == "__main__":
    sys.exit(main())
