#!/usr/bin/env python3
"""电池守护隔离回归：低电轮询、关机门槛、拔线恢复、PSS/cycle 与低频调度。

在临时沙箱里 source 真实 engine/lib/battery.sh，节点、配置、状态、pm/reboot
全部由临时目录与桩命令提供；不访问真实 /sys、/data、/storage，也不重启设备。

用法：python3 scripts/battery_idle_regression.py
"""

import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BATTERY_SH = ROOT / "engine/lib/battery.sh"

BASE_CFG = {
    "unlock": "1",
    "pss_disable": "1",
    "pss_low_soc": "0",
    "pss_low_hyst": "5",
    "cycle_fake": "",
    "term_v": "",
    "cloud_guard": "0",
    "shutdown_check_soc": "20",
    "shutdown_exit_soc": "25",
    "shutdown_check_interval": "2",
}

PRELUDE = r'''
PATH="@@BIN@@:$PATH"
export PATH
MODDIR="@@MODDIR@@"
BT_STATE="@@STATE@@"
BAT_STATE="@@STATE@@"
LOGDIR="@@LOG@@"
BT_CONF="@@CONF@@"
BAT="@@BATTERY@@"
BGU_BATT_DIR="@@BATTERY@@"
BGU_SMART_FV="@@FV@@"
BGU_FV_DOWN="@@FV_DOWN@@"
BGU_QMAX_NODE="@@QMAX@@"
BGU_CYCLE_REAL_FALLBACK="@@CYCLE_FALLBACK@@"
BGU_CYCLE_NODES="@@CYCLE_NODES@@"
CALL_LOG="@@CALL_LOG@@"
CFG_LOG="@@CFG_LOG@@"
XJ_READONLY=${XJ_READONLY_OVERRIDE:-0}
mkdir -p "$BAT_STATE" "$LOGDIR" "@@NODES@@"
log() { echo "$(date '+%F %T') [engine] $*" >> "$LOGDIR/engine.log" 2>/dev/null; }
cfg_get() {
  echo "$1" >> "$CFG_LOG"
  [ -s "$BT_CONF" ] || { printf '%s' "${2:-}"; return 0; }
  _v=$(grep -aE "^$1=" "$BT_CONF" 2>/dev/null | tail -n1 | cut -d= -f2-)
  [ -n "$_v" ] && printf '%s' "$_v" || printf '%s' "${2:-}"
}
. "@@BATTERY_SH@@"
baa_state() { echo "baa_state $XJ_NOW" >> "$CALL_LOG"; cat "@@BAA_STATE@@" 2>/dev/null || echo inactive; }
baa_refresh_src() { echo baa_refresh >> "$CALL_LOG"; return 0; }
baa_mount() {
  echo baa_mount >> "$CALL_LOG"
  [ "${FAKE_BAA_MOUNT_OK:-1}" = "1" ] && echo active > "@@BAA_STATE@@"
  return 0
}
cloud_tick() { echo "cloud_tick $XJ_NOW" >> "$CALL_LOG"; }
tick() {
  XJ_NOW=$1; XJ_CONFIG_CHANGED=$2; XJ_CONNECTION_CHANGED=$3
  BAT_CAP=$4; IS_CHARGING=$5; BAT_VOLT=$6; BAT_TEMP=${7:-300}
  battery_guard_tick
}
'''


def write_exe(path, text):
    path.write_text(text)
    path.chmod(0o755)


class Sandbox:
    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="battery-idle-")
        self.root = Path(self.tmp.name)
        self.bin = self.root / "bin"
        self.nodes = self.root / "nodes"
        self.battery = self.root / "battery"
        self.state = self.root / "state"
        self.log = self.root / "log"
        self.moddir = self.root / "moddir"
        self.conf = self.root / "settings.conf"
        for d in (self.bin, self.nodes, self.battery, self.state, self.log, self.moddir):
            d.mkdir(parents=True)
        self.call_log = self.root / "calls.log"
        self.cfg_log = self.root / "cfg.log"
        self.pss_state = self.root / "pss_state"
        self.baa_state = self.root / "baa_state"
        self.baa_state.write_text("inactive\n")

        self.fv = self.nodes / "smart_fv"
        self.fv_down = self.nodes / "fv_down"
        self.qmax = self.nodes / "qmax_cyclecount"
        self.cycle_fallback = self.nodes / "fg_cyclecount"
        self.cycle1 = self.nodes / "cyclecount"
        self.cycle2 = self.nodes / "battery_cycle_count"
        for p in (self.fv, self.fv_down):
            p.write_text("0")
        self.qmax.write_text("555")
        self.cycle_fallback.write_text("555")
        self.cycle1.write_text("555")
        self.cycle2.write_text("555")
        (self.battery / "voltage_now").write_text("3900000")

        for name, body in {
            "reboot": 'echo "reboot $*" >> "$CALL_LOG"\n',
            "svc": 'echo "svc $*" >> "$CALL_LOG"\n',
            "setprop": 'echo "setprop $*" >> "$CALL_LOG"\n',
            "mount": 'echo "mount $*" >> "$CALL_LOG"\n',
            "umount": 'echo "umount $*" >> "$CALL_LOG"\n',
            "chcon": 'echo "chcon $*" >> "$CALL_LOG"\n',
            "pm": 'echo "pm $*" >> "$CALL_LOG"\nif [ "${PM_FAIL:-0}" = 0 ]; then if [ "$1" = disable ]; then echo PowerSaveService > "$PSS_STATE"; elif [ "$1" = enable ]; then : > "$PSS_STATE"; fi; exit 0; else exit 1; fi\n',
            "dumpsys": 'echo "dumpsys $*" >> "$CALL_LOG"\nif [ "$1" = package ]; then if [ "${DUMPSYS_TRUNCATED:-0}" = 1 ]; then echo "disabledComponents:"; else echo "disabledComponents:"; cat "$PSS_STATE" 2>/dev/null; echo "enabledComponents:"; fi; fi\n',
        }.items():
            write_exe(self.bin / name, "#!/bin/sh\n" + body + "exit 0\n")
        self.write_cfg()

    def write_cfg(self, **over):
        cfg = dict(BASE_CFG)
        cfg.update(over)
        self.conf.write_text("".join("%s=%s\n" % (k, v) for k, v in cfg.items()))

    def env(self):
        env = dict(os.environ)
        env.update({
            "CALL_LOG": str(self.call_log),
            "CFG_LOG": str(self.cfg_log),
            "PSS_STATE": str(self.pss_state),
            "FAKE_BAA_MOUNT_OK": "1",
        })
        return env

    def run(self, code, **env_over):
        prelude = PRELUDE
        for key, value in {
            "@@BIN@@": str(self.bin), "@@MODDIR@@": str(self.moddir),
            "@@STATE@@": str(self.state), "@@LOG@@": str(self.log),
            "@@CONF@@": str(self.conf), "@@BATTERY@@": str(self.battery),
            "@@FV@@": str(self.fv), "@@FV_DOWN@@": str(self.fv_down),
            "@@QMAX@@": str(self.qmax), "@@CYCLE_FALLBACK@@": str(self.cycle_fallback),
            "@@CYCLE_NODES@@": "%s %s" % (self.cycle1, self.cycle2),
            "@@CALL_LOG@@": str(self.call_log), "@@CFG_LOG@@": str(self.cfg_log),
            "@@NODES@@": str(self.nodes), "@@BAA_STATE@@": str(self.baa_state),
            "@@BATTERY_SH@@": str(BATTERY_SH),
        }.items():
            prelude = prelude.replace(key, value)
        env = self.env()
        env.update(env_over)
        p = subprocess.run(["sh", "-c", prelude + "\n" + code], env=env,
                           text=True, capture_output=True)
        if p.returncode != 0:
            raise AssertionError("shell failed (%d):\n%s\n%s" %
                                 (p.returncode, p.stdout, p.stderr))
        return p.stdout

    def calls(self):
        if not self.call_log.exists():
            return []
        return self.call_log.read_text().splitlines()

    def count(self, prefix):
        return sum(1 for line in self.calls() if line.startswith(prefix))

    def cfg_reads(self):
        if not self.cfg_log.exists():
            return 0
        return len(self.cfg_log.read_text().splitlines())


def assert_eq(got, want, label):
    if got != want:
        raise AssertionError("%s: got %r, want %r" % (label, got, want))


def low_battery_case(sb):
    sb.write_cfg(term_v="3000", shutdown_check_soc="20", shutdown_exit_soc="25")
    out = sb.run(r'''
tick 0 1 0 20 0 3900000
printf "poll-low=[%s]\n" "$(battery_poll_interval)"
[ -f "$BAT_STATE/low_batt" ] && echo low-state=1 || echo low-state=0
# 0 / 空 / 超范围电压都不得关机
printf 0 > "$BGU_BATT_DIR/voltage_now"; tick 2 0 0 20 0 0
: > "$BGU_BATT_DIR/voltage_now"; tick 4 0 0 20 0 0
printf 500000 > "$BGU_BATT_DIR/voltage_now"; tick 6 0 0 20 0 0
printf 20000000 > "$BGU_BATT_DIR/voltage_now"; tick 8 0 0 20 0 0
echo early-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
# 节点无效时回退 BAT_VOLT；节点本身为 0 时仍不关机
: > "$BGU_BATT_DIR/voltage_now"; tick 10 0 0 20 0 2900000
echo fallback-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
printf 2900000 > "$BGU_BATT_DIR/voltage_now"; tick 12 0 0 20 0 0
echo node-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
printf 0 > "$BGU_BATT_DIR/voltage_now"; tick 14 0 0 20 0 2900000
echo zero-node-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
# 滞回：24% 仍在低电，25% 退出；充电立即退出
printf 3800000 > "$BGU_BATT_DIR/voltage_now"; tick 16 0 0 24 0 3900000
echo mid-state=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
tick 18 0 0 25 0 3900000
echo exit-poll=[$(battery_poll_interval)]
echo exit-state=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
tick 20 0 0 15 1 3900000
echo charge-poll=[$(battery_poll_interval)]
echo charge-state=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
''')
    assert "poll-low=[2]" in out, out
    assert "low-state=1" in out, out
    assert "early-reboot=0" in out, out
    assert "fallback-reboot=1" in out, out
    assert "node-reboot=2" in out, out
    assert "zero-node-reboot=2" in out, out
    assert "mid-state=1" in out, out
    assert "exit-poll=[]" in out, out
    assert "exit-state=0" in out, out
    assert "charge-poll=[]" in out, out
    assert "charge-state=0" in out, out


def low_unknown_case(sb):
    sb.write_cfg(term_v="3000", shutdown_check_soc="20", shutdown_exit_soc="25")
    out = sb.run(r'''
# SOC 未知/异常时仍进入电压保护，不因门槛直接跳过
printf 2950000 > "$BGU_BATT_DIR/voltage_now"
tick 0 1 0 "" 0 0
echo unknown-state=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
echo unknown-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
# 非数字 SOC 同样进入；无效电压不关机
printf 3900000 > "$BGU_BATT_DIR/voltage_now"
tick 2 0 0 abc 0 0
echo abc-state=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
# 越界 SOC 同样不能绕过
tick 4 0 0 999 0 0
echo bad-soc-state=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
# 非法 XJ_NOW 不崩，且仍可进入
tick xyz 1 0 10 0 3900000
echo invalid-now=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
''')
    assert "unknown-state=1" in out, out
    assert "unknown-reboot=1" in out, out
    assert "abc-state=1" in out, out
    assert "bad-soc-state=1" in out, out
    assert "invalid-now=1" in out, out


def sampled_voltage_case(sb):
    # 正常电量不触发用户自定义关机电压，即使节点或当前样本低于阈值。
    sb.write_cfg(term_v="3400", shutdown_check_soc="20", shutdown_exit_soc="25")
    out = sb.run(r'''
printf 3900000 > "$BGU_BATT_DIR/voltage_now"
tick 0 1 0 50 0 3300000
echo sampled-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
echo sampled-low=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
echo sampled-poll=[$(battery_poll_interval)]
printf 3300000 > "$BGU_BATT_DIR/voltage_now"
tick 2 0 0 50 0 3900000
echo node-low-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
printf 0 > "$BGU_BATT_DIR/voltage_now"
tick 4 0 0 50 0 0
echo invalid-volt-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
''')
    assert "sampled-reboot=0" in out, out
    assert "sampled-low=0" in out, out
    assert "sampled-poll=[]" in out, out
    assert "node-low-reboot=0" in out, out
    assert "invalid-volt-reboot=0" in out, out


def config_change_case(sb):
    # 低电状态建立后，修改 term_v 必须当拍生效。
    sb.write_cfg(term_v="3400", shutdown_check_soc="20", shutdown_exit_soc="25")
    sb.run(r'''
tick 0 1 0 20 0 3900000
printf 3300000 > "$BGU_BATT_DIR/voltage_now"
''')
    sb.write_cfg(term_v="3000", shutdown_check_soc="20", shutdown_exit_soc="25")
    out = sb.run(r'''
tick 1 1 0 20 0 3900000
echo higher-threshold-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
''')
    assert "higher-threshold-reboot=0" in out, out

    sb.write_cfg(term_v="3400", shutdown_check_soc="20", shutdown_exit_soc="25")
    out = sb.run(r'''
tick 2 1 0 20 0 3900000
echo lower-threshold-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
''')
    assert "lower-threshold-reboot=1" in out, out

    # 关闭 term_v 后立即清掉低电状态。
    sb.write_cfg(term_v="")
    out = sb.run(r'''
tick 3 1 0 10 0 3900000
echo off-state=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
echo off-poll=[$(battery_poll_interval)]
''')
    assert "off-state=0" in out, out
    assert "off-poll=[]" in out, out

    # 无效门槛回退默认 20/25/2，避免配置错误导致漏保护。
    sb.write_cfg(term_v="3000", shutdown_check_soc="abc",
                 shutdown_exit_soc="abc", shutdown_check_interval="abc")
    out = sb.run(r'''
tick 0 1 0 19 0 3900000
echo fallback-state=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
echo fallback-poll=[$(battery_poll_interval)]
''')
    assert "fallback-state=1" in out, out
    assert "fallback-poll=[2]" in out, out

    # 超过手机/页面范围的 term_v 按未配置处理，不能一到正常电压就关机。
    sb.write_cfg(term_v="99999", shutdown_check_soc="20", shutdown_exit_soc="25")
    out = sb.run(r'''
: > "$CALL_LOG"
printf 3900000 > "$BGU_BATT_DIR/voltage_now"
tick 0 1 0 50 0 3900000
echo invalid-term-reboot=$(grep -c "^reboot" "$CALL_LOG" || true)
echo invalid-term-low=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
echo invalid-term-poll=[$(battery_poll_interval)]
''')
    assert "invalid-term-reboot=0" in out, out
    assert "invalid-term-low=0" in out, out
    assert "invalid-term-poll=[]" in out, out


def pss_case(sb):
    # 静态模式：不在每拍触发 pm/dumpsys，交给开机/开关一次性处理。
    sb.write_cfg(pss_low_soc="0")
    out = sb.run(r'''
tick 0 1 0 15 0 3900000
tick 1 0 0 15 0 3900000
tick 2 0 0 15 0 3900000
echo static-pm=$(grep -c "^pm" "$CALL_LOG" || true)
''')
    assert "static-pm=0" in out, out

    # 动态模式：低电禁用一次、重复拍不重复调用；回升启用一次。
    sb.write_cfg(pss_low_soc="20", pss_low_hyst="5")
    out = sb.run(r'''
tick 0 1 0 15 0 3900000
tick 1 0 0 15 0 3900000
echo disable-count=$(grep -c "^pm disable" "$CALL_LOG" || true)
tick 2 0 0 22 0 3900000
echo hold-count=$(grep -c "^pm " "$CALL_LOG" || true)
tick 3 0 0 25 0 3900000
tick 4 0 0 26 0 3900000
echo enable-count=$(grep -c "^pm enable" "$CALL_LOG" || true)
echo total-pm=$(grep -c "^pm " "$CALL_LOG" || true)
''')
    assert "disable-count=1" in out, out
    assert "hold-count=1" in out, out
    assert "enable-count=1" in out, out
    assert "total-pm=2" in out, out

    # pm 失败时不得写入或清除模块所有权标记。
    failure = sb.run(r'''
rm -f "$BAT_STATE/pss_disabled_by_module"
rm -f "$PSS_STATE"
pm() { return 1; }
pss_disable; echo disable-rc=$?
echo disable-owner=$(cat "$BAT_STATE/pss_disabled_by_module" 2>/dev/null)
printf '1' > "$BAT_STATE/pss_disabled_by_module"
printf 'PowerSaveService' > "$PSS_STATE"
pss_enable; echo enable-rc=$?
echo enable-owner=$(cat "$BAT_STATE/pss_disabled_by_module" 2>/dev/null)
''')
    assert "disable-rc=1" in failure, failure
    assert "disable-owner=" in failure, failure
    assert "enable-rc=1" in failure, failure
    assert "enable-owner=1" in failure, failure
    # 禁用命令失败时，不写入所有权标记。
    sb.pss_state.write_text("")
    sb.call_log.write_text("")
    (sb.state / "pss_disabled_by_module").unlink(missing_ok=True)
    out = sb.run(r'''
pm() { return 1; }
pss_disable; echo disable-rc=$?
echo disable-owner=$(cat "$BAT_STATE/pss_disabled_by_module" 2>/dev/null)
''')
    assert "disable-rc=1" in out, out
    assert "disable-owner=" in out, out
    sb.pss_state.write_text("")
    out = sb.run(r'''
pss_state() { return 1; }
pm() { echo unexpected-pm; }
pss_disable; echo truncated-state-rc=$?
echo truncated-owner=$(cat "$BAT_STATE/pss_disabled_by_module" 2>/dev/null)
''')
    assert "truncated-state-rc=1" in out, out
    assert "truncated-owner=" in out, out
    out = sb.run(r'''
pm() { return 1; }
pss_state() { n=$(cat "$BAT_STATE/pss_reads" 2>/dev/null); n=$((n + 1)); echo "$n" > "$BAT_STATE/pss_reads"; [ "$n" -eq 2 ] && return 1; echo enabled; }
pss_disable; echo failed-state-rc=$?
echo failed-state-owner=$(cat "$BAT_STATE/pss_disabled_by_module" 2>/dev/null)
echo pending=$(test -e "$BAT_STATE/pss_disable_pending" && echo 1 || echo 0)
''')
    assert "failed-state-rc=1" in out, out
    assert "failed-state-owner=" in out, out
    assert "pending=0" in out, out
    (sb.state / "pss_reads").unlink(missing_ok=True)
    out = sb.run(r'''
pm() { echo PowerSaveService > "$PSS_STATE"; return 0; }
pss_state() { n=$(cat "$BAT_STATE/pss_reads" 2>/dev/null); n=$((n + 1)); echo "$n" > "$BAT_STATE/pss_reads"; [ "$n" -eq 2 ] && return 1; [ -s "$PSS_STATE" ] && echo disabled || echo enabled; }
pss_disable; echo first-rc=$?
echo pending-first=$(cat "$BAT_STATE/pss_disable_pending" 2>/dev/null)
echo owner-first=$(cat "$BAT_STATE/pss_disabled_by_module" 2>/dev/null)
pss_disable; echo retry-rc=$?
echo pending-retry=$(test -e "$BAT_STATE/pss_disable_pending" && echo 1 || echo 0)
echo owner-retry=$(test -e "$BAT_STATE/pss_disabled_by_module" && echo 1 || echo 0)
''')
    assert "first-rc=1" in out, out
    assert "pending-first=issued" in out, out
    assert "owner-first=" in out, out
    assert "retry-rc=0" in out, out
    assert "pending-retry=0" in out, out
    assert "owner-retry=1" in out, out

    # 动态模式不能恢复从未由模块禁用的 PSS。
    sb.write_cfg(pss_low_soc="20", pss_low_hyst="5")
    sb.pss_state.write_text("PowerSaveService")
    sb.call_log.write_text("")
    (sb.state / "pss_dynamic").unlink(missing_ok=True)
    (sb.state / "pss_disabled_by_module").unlink(missing_ok=True)
    out = sb.run(r'''
tick 0 1 0 50 0 3900000
echo predisabled-enable=$(grep -c "^pm enable" "$CALL_LOG" || true)
echo predisabled-mark=$(test -e "$BAT_STATE/pss_disabled_by_module" && echo 1 || echo 0)
''')
    assert "predisabled-enable=0" in out, out
    assert "predisabled-mark=0" in out, out
    assert (sb.state / "pss_dynamic").read_text() == "external_disabled"

    # 回升阈值封顶100%，高合法进入阈值仍可退出低电状态。
    sb.write_cfg(pss_low_soc="98", pss_low_hyst="5")
    sb.pss_state.write_text("")
    sb.call_log.write_text("")
    (sb.state / "pss_dynamic").unlink(missing_ok=True)
    (sb.state / "pss_disabled_by_module").unlink(missing_ok=True)
    out = sb.run(r'''
BGU_CFG_LOADED=0
tick 0 1 0 98 0 3900000
echo enter-low=$(cat "$BAT_STATE/pss_dynamic")
tick 1 0 0 99 0 3900000
echo hold-low=$(cat "$BAT_STATE/pss_dynamic")
tick 2 0 0 100 0 3900000
echo exit-high=$(cat "$BAT_STATE/pss_dynamic")
''')
    assert "enter-low=disabled" in out, out
    assert "hold-low=disabled" in out, out
    assert "exit-high=enabled" in out, out


def frequency_case(sb):
    sb.write_cfg(unlock="1", cloud_guard="1", term_v="", pss_low_soc="0")
    out = sb.run(r'''
tick 0 1 0 50 0 3900000
echo cfg-after-init=$(wc -l < "$CFG_LOG")
tick 59 0 0 50 0 3900000
tick 60 0 0 50 0 3900000
echo cfg-idle=$(wc -l < "$CFG_LOG")
tick 299 0 0 50 0 3900000
tick 300 0 0 50 0 3900000
echo cfg-late=$(wc -l < "$CFG_LOG")
echo baa-checks=$(grep -c "^baa_state" "$CALL_LOG" || true)
echo baa-mounts=$(grep -c "^baa_mount" "$CALL_LOG" || true)
echo baa-times=$(grep "^baa_state " "$CALL_LOG" | awk '{printf "%s,", $2}' | sed 's/,$//')
echo cloud-calls=$(grep -c "^cloud_tick" "$CALL_LOG" || true)
echo cloud-times=$(grep "^cloud_tick " "$CALL_LOG" | awk '{printf "%s,", $2}' | sed 's/,$//')
''')
    # 首次加载配置一次；之后 59~300 秒不再解析配置。
    init = int(out.split("cfg-after-init=")[1].split()[0])
    idle = int(out.split("cfg-idle=")[1].split()[0])
    late = int(out.split("cfg-late=")[1].split()[0])
    assert init > 0, out
    assert idle == init, out
    assert late == init, out
    # BAA 真实 300 秒：首拍巡检一次（含挂载后确认），300 秒再检查；60/299 不检查。
    assert "baa-checks=3" in out, out
    assert "baa-mounts=1" in out, out
    assert "baa-times=0,0,300" in out, out
    # 云控真实 60 秒：0、60 各一次；下一次有效拍在 299 秒时已超过 60 秒。
    assert "cloud-calls=3" in out, out
    assert "cloud-times=0,60,299" in out, out


def cloud_off_case(sb):
    # 关闭时只在由开转关时写一次 off；重复配置变更也不反复写。
    sb.write_cfg(cloud_guard="0")
    out = sb.run(r'''
tick 0 1 0 50 0 3900000
tick 1 0 0 50 0 3900000
tick 2 1 0 50 0 3900000
echo off-calls=$(grep -c "^cloud_tick" "$CALL_LOG" || true)
''')
    assert "off-calls=1" in out, out

    sb.write_cfg(cloud_guard="1")
    sb.run(r'''
tick 0 1 0 50 0 3900000
tick 1 0 0 50 0 3900000
tick 60 0 0 50 0 3900000
''')
    sb.call_log.unlink()
    sb.write_cfg(cloud_guard="0")
    out = sb.run(r'''
tick 0 1 0 50 0 3900000
tick 1 0 0 50 0 3900000
tick 2 1 0 50 0 3900000
echo after-toggle=$(grep -c "^cloud_tick" "$CALL_LOG" || true)
''')
    assert "after-toggle=1" in out, out


def cloud_restore_failure_case(sb):
    sb.write_cfg(cloud_guard="1")
    # Isolate cloud restore from the Android paths by remapping its constant.
    cloud_dir = sb.root / "cloud"
    quarantine = sb.state / "cloud_quarantine"
    cloud_dir.mkdir()
    quarantine.mkdir()
    source = cloud_dir / "config.json"
    source.write_text("new-cloud-payload")
    original = sb.root / "missing-original" / "original.json"
    parked = quarantine / "20261122_010101__config.json"
    parked.write_text("payload")
    src_log = quarantine / "_sources.log"
    src_log.write_text(f"{parked.name}|{original}" + chr(10))
    out = sb.run(r'''
CLOUD_DIRS="$TEST_CLOUD_DIR"
CLOUD_QDIR="$TEST_QUARANTINE"
ln() { echo "$*" >> "$CALL_LOG"; return 1; }
cloud_restore; echo restore-rc=$?
echo retained=$(test -f "$CLOUD_QDIR/20261122_010101__config.json" && echo 1 || echo 0)
echo source=$(cat "$TEST_CLOUD_DIR/config.json")
''', TEST_CLOUD_DIR=str(cloud_dir), TEST_QUARANTINE=str(quarantine))
    assert "restore-rc=1" in out, out
    assert "retained=1" in out, out
    assert "source=new-cloud-payload" in out, out
    assert not sb.call_log.exists() or not sb.call_log.read_text(), sb.call_log.read_text()

    # The exact original path can be recreated while the file is quarantined.
    source.write_text("new-cloud-payload")
    src_log.write_text(f"{parked.name}|{source}" + chr(10))
    out = sb.run(r'''
CLOUD_DIRS="$TEST_CLOUD_DIR"
CLOUD_QDIR="$TEST_QUARANTINE"
cloud_restore; echo collision-rc=$?
echo retained=$(test -f "$CLOUD_QDIR/20261122_010101__config.json" && echo 1 || echo 0)
echo source=$(cat "$TEST_CLOUD_DIR/config.json")
''', TEST_CLOUD_DIR=str(cloud_dir), TEST_QUARANTINE=str(quarantine))
    assert "collision-rc=1" in out, out
    assert "retained=1" in out, out
    assert "source=new-cloud-payload" in out, out

    source.unlink()
    out = sb.run(r'''
CLOUD_DIRS="$TEST_CLOUD_DIR"
CLOUD_QDIR="$TEST_QUARANTINE"
ln() { printf race > "$2"; return 1; }
cloud_restore; echo race-rc=$?
echo retained=$(test -f "$CLOUD_QDIR/20261122_010101__config.json" && echo 1 || echo 0)
echo raced=$(cat "$TEST_CLOUD_DIR/config.json")
''', TEST_CLOUD_DIR=str(cloud_dir), TEST_QUARANTINE=str(quarantine))
    assert "race-rc=1" in out, out
    assert "retained=1" in out, out
    assert "raced=race" in out, out

    parked.write_text("new-cloud-payload")
    src_log.write_text(f"{parked.name}|{source}" + chr(10))
    source.unlink(missing_ok=True)
    out = sb.run(r'''
CLOUD_DIRS="$TEST_CLOUD_DIR"
CLOUD_QDIR="$TEST_QUARANTINE"
rm() {
  case "$*" in *"$CLOUD_QDIR/20261122_010101__config.json"*)
    if [ ! -e "$BAT_STATE/rm_failed_once" ]; then : > "$BAT_STATE/rm_failed_once"; return 1; fi ;;
  esac
  command rm "$@"
}
cloud_restore; echo first-rc=$?
echo target-first=$(cat "$TEST_CLOUD_DIR/config.json")
echo retained-first=$(test -f "$CLOUD_QDIR/20261122_010101__config.json" && echo 1 || echo 0)
cloud_restore; echo retry-rc=$?
echo retained-retry=$(test -f "$CLOUD_QDIR/20261122_010101__config.json" && echo 1 || echo 0)
''', TEST_CLOUD_DIR=str(cloud_dir), TEST_QUARANTINE=str(quarantine))
    assert "first-rc=1" in out, out
    assert "target-first=new-cloud-payload" in out, out
    assert "retained-first=1" in out, out
    assert "retry-rc=0" in out, out
    assert "retained-retry=0" in out, out


def smart_fv_case(sb):
    sb.write_cfg(unlock="1", cloud_guard="0", term_v="")
    sb.fv.write_text("4450000")
    sb.fv_down.write_text("4450000")
    out = sb.run(r'''
tick 0 1 0 80 1 4400000
echo fv0=$(cat "$BGU_SMART_FV")
echo down0=$(cat "$BGU_FV_DOWN")
echo def0=$(cat "$BAT_STATE/fv_default")
echo hold0=$(test -f "$BAT_STATE/fv_hold" && echo 1 || echo 0)
# 驱动把 smart_fv 复位，但未到 300 秒复查点：不得每拍写回
printf 4450000 > "$BGU_SMART_FV"
tick 299 0 0 80 1 4400000
echo early=$(cat "$BGU_SMART_FV")
tick 300 0 0 80 1 4400000
echo recheck=$(cat "$BGU_SMART_FV")
# 拔线：按快照恢复 smart_fv；fv_down 保持旧行为
tick 301 0 1 80 0 4300000
echo restored=$(cat "$BGU_SMART_FV")
echo hold1=$(test -f "$BAT_STATE/fv_hold" && echo 1 || echo 0)
echo down1=$(cat "$BGU_FV_DOWN")
# 恢复成功后不在每拍读写；300 秒后再做一次完整性检查
printf 0 > "$BGU_SMART_FV"
tick 302 0 0 80 0 4300000
echo quiet=$(cat "$BGU_SMART_FV")
tick 601 0 0 80 0 4300000
echo integrity=$(cat "$BGU_SMART_FV")
''')
    assert "fv0=0" in out, out
    assert "down0=0" in out, out
    assert "def0=4450000" in out, out
    assert "hold0=1" in out, out
    assert "early=4450000" in out, out
    assert "recheck=0" in out, out
    assert "restored=4450000" in out, out
    assert "hold1=0" in out, out
    assert "down1=0" in out, out
    assert "quiet=0" in out, out
    assert "integrity=4450000" in out, out


def smart_fv_retry_case(sb):
    sb.write_cfg(unlock="1", cloud_guard="0", term_v="")
    sb.fv.write_text("4450000")
    sb.run(r'''
tick 0 1 0 80 1 4400000
''')
    assert (sb.state / "fv_hold").exists()

    # 拔线后把节点换成一个不可写目录，模拟恢复失败。
    sb.fv.unlink()
    sb.fv.mkdir()
    out = sb.run(r'''
tick 1 0 1 80 0 4300000
echo pending=$(test -f "$BAT_STATE/fv_restore_pending" && echo 1 || echo 0)
''')
    assert "pending=1" in out, out
    fail_logs = (sb.log / "battery.log").read_text().count("smart_fv 恢复失败")
    assert fail_logs == 1, fail_logs
    # 仍在失败状态：重试不重复刷日志。
    out = sb.run(r'''
tick 2 0 0 80 0 4300000
echo pending=$(test -f "$BAT_STATE/fv_restore_pending" && echo 1 || echo 0)
''')
    assert "pending=1" in out, out
    assert (sb.log / "battery.log").read_text().count("smart_fv 恢复失败") == 1

    # 故障解除后，下一拍必须重试成功并清除 pending。
    sb.fv.rmdir()
    sb.fv.write_text("0")
    out = sb.run(r'''
tick 3 0 0 80 0 4300000
echo fv=$(cat "$BGU_SMART_FV")
echo pending=$(test -f "$BAT_STATE/fv_restore_pending" && echo 1 || echo 0)
''')
    assert "fv=4450000" in out, out
    assert "pending=0" in out, out


def smart_fv_no_snapshot_case(sb):
    sb.write_cfg(unlock="1", cloud_guard="0", term_v="")
    sb.fv.write_text("0")
    (sb.state / "fv_hold").write_text("1")
    (sb.state / "fv_restore_pending").write_text("1")
    out = sb.run(r'''
tick 0 0 0 80 0 4300000
tick 1 0 0 80 0 4300000
echo hold=$(test -f "$BAT_STATE/fv_hold" && echo 1 || echo 0)
echo pending=$(test -f "$BAT_STATE/fv_restore_pending" && echo 1 || echo 0)
echo fv=$(cat "$BGU_SMART_FV")
''')
    assert "hold=0" in out, out
    assert "pending=0" in out, out
    assert "fv=0" in out, out


def cycle_case(sb):
    sb.write_cfg(cycle_fake="", cloud_guard="0", term_v="")
    out = sb.run(r'''
tick 0 1 0 80 0 3900000
echo empty=$(cat "@@C1@@")
'''.replace("@@C1@@", str(sb.cycle1)))
    assert "empty=555" in out, out

    sb.write_cfg(cycle_fake="999", cloud_guard="0", term_v="")
    out = sb.run(r'''
tick 0 1 0 80 0 3900000
echo cycle1=$(cat "@@C1@@")
echo cycle2=$(cat "@@C2@@")
echo real=$(cat "$BAT_STATE/cycle_real")
printf 555 > "@@C1@@"
tick 1 0 0 80 0 3900000
echo recoil=$(cat "@@C1@@")
'''.replace("@@C1@@", str(sb.cycle1)).replace("@@C2@@", str(sb.cycle2)))
    assert "cycle1=999" in out, out
    assert "cycle2=999" in out, out
    assert "real=555" in out, out
    assert "recoil=999" in out, out

    sb.write_cfg(cycle_fake="", cloud_guard="0", term_v="")
    out = sb.run(r'''
printf 777 > "@@C1@@"
tick 2 1 0 80 0 3900000
echo off=$(cat "@@C1@@")
'''.replace("@@C1@@", str(sb.cycle1)))
    assert "off=777" in out, out


def read_only_case(sb):
    sb.write_cfg(term_v="3000", cloud_guard="1", unlock="1")
    out = sb.run(r'''
tick 0 1 0 10 0 2900000
echo low=$(test -f "$BAT_STATE/low_batt" && echo 1 || echo 0)
echo calls=$(test -f "$CALL_LOG" && wc -l < "$CALL_LOG" || echo 0)
''', XJ_READONLY_OVERRIDE="1")
    assert "low=0" in out, out
    assert "calls=0" in out, out


def main():
    cases = [
        ("low-battery", low_battery_case),
        ("low-unknown", low_unknown_case),
        ("sampled-voltage", sampled_voltage_case),
        ("config-change", config_change_case),
        ("pss", pss_case),
        ("frequency", frequency_case),
        ("cloud-off", cloud_off_case),
        ("cloud-restore-failure", cloud_restore_failure_case),
        ("smart-fv", smart_fv_case),
        ("smart-fv-retry", smart_fv_retry_case),
        ("smart-fv-no-snapshot", smart_fv_no_snapshot_case),
        ("cycle", cycle_case),
        ("read-only", read_only_case),
    ]
    for name, fn in cases:
        sb = Sandbox()
        try:
            fn(sb)
        finally:
            sb.tmp.cleanup()
        print("ok %s" % name)
    print("all battery idle regression cases passed")


if __name__ == "__main__":
    main()