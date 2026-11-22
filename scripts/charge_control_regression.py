#!/usr/bin/env python3
"""隔离回归：APL 游标、inf/APL 电流控制和 charge_control_limit 节点边界。

所有节点、状态和命令均由临时目录桩提供，不访问真实 /sys、/data、/storage，
也不执行设备命令。
"""

import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APL = ROOT / "engine/lib/apl.sh"
POWER = ROOT / "engine/lib/power.sh"
THERMAL = ROOT / "engine/lib/thermal.sh"


def run(script, env):
    p = subprocess.run(["sh", "-c", script], env=env, text=True,
                       capture_output=True)
    if p.returncode != 0:
        raise AssertionError("shell failed (%d):\n%s\n%s" %
                             (p.returncode, p.stdout, p.stderr))
    return p.stdout


def write_exe(path, text):
    path.write_text(text)
    path.chmod(0o755)


def apl_case(sb):
    bindir = sb / "bin"
    bindir.mkdir(parents=True)
    write_exe(bindir / "dmesg", "cat \"$DMSG_FILE\"\n")
    boot = sb / "boot_id"
    boot.write_text("boot-a\n")
    dmesg = sb / "dmesg.txt"
    dmesg.write_text("[1.000] cp work abnormal\n")
    env = dict(os.environ)
    env.update({"PATH": str(bindir) + ":" + env["PATH"],
                "STATE": str(sb / "state"),
                "DMSG_FILE": str(dmesg),
                "APL_BOOT_ID_FILE": str(boot)})
    (sb / "state").mkdir()
    base = ". \"%s\"\n" % APL
    first = run(base + "apl_hard_fault; printf 'first=%s\\n' \"$?\"", env).strip()
    assert first == "first=1", first
    mark = (sb / "state/apl_dmesg_mark").read_text()
    assert mark.startswith("boot-a|1.000|"), mark

    # Adapter power metadata can fluctuate without changing the negotiated voltage tier.
    profile = sb / "adapter-profile"
    profile.mkdir()
    (profile / "profile").write_text("adp=9000000\nseen=9999999999\n")
    apl_env = dict(env, CONFDIR=str(profile))
    apl_fp_script = """
. \"%s\"
apl_rd() { case \"$1\" in */voltage_max) echo \"${USB_VMAX:-9000000}\";; */power_max) echo \"${POWER_MAX:-90}\";; *) echo 0;; esac; }
apl_prof_get() { case \"$1\" in adp) echo \"$(sed -n 's/^adp=//p' \"$PROFILE\")\";; adpc) echo \"$(sed -n 's/^adpc=//p' \"$PROFILE\")\";; adpn) echo \"$(sed -n 's/^adpn=//p' \"$PROFILE\")\";; esac; }
apl_prof_set() { printf '%%s=%%s\\n' \"$1\" \"$2\" >> \"$PROFILE_WRITES\"; }
fp=$(apl_adp_fp)
reason=$(apl_adp_check \"$fp\")
printf '%%s|%%s|%%s\\n' \"$fp\" \"$reason\" \"$(cat \"$PROFILE_WRITES\" 2>/dev/null)\"
""" % APL
    apl_env["PROFILE"] = str(profile / "profile")
    apl_env["PROFILE_WRITES"] = str(profile / "writes")
    apl_env["USB_VMAX"] = "9000000"
    apl_env["POWER_MAX"] = "90"
    out = run(apl_fp_script, apl_env).strip()
    assert out.split("|", 2)[:2] == ["9000000", ""], out
    assert not (profile / "writes").exists(), out
    (profile / "profile").write_text("adp=9000000_90\nseen=9999999999\n")
    out = run(apl_fp_script, apl_env).strip()
    assert out.startswith("9000000||adp=9000000"), out
    assert "adp=9000000" in (profile / "writes").read_text(), out
    power_profile = sb / "adapter-power-profile"
    power_profile.mkdir()
    (power_profile / "profile").write_text("adp=9000000\nseen=9999999999\n")
    power_env = dict(apl_env, CONFDIR=str(power_profile), PROFILE=str(power_profile / "profile"),
                     PROFILE_WRITES=str(power_profile / "writes"), POWER_MAX="60")
    out = run(apl_fp_script, power_env).strip()
    assert out.split("|", 2)[:2] == ["9000000", ""], out
    assert not (power_profile / "writes").exists(), out

    dmesg.write_text("[1.000] cp work abnormal\n[2.000] cp work abnormal\n")
    assert run(base + "apl_hard_fault; printf 'new=%s\\n' \"$?\"", env).strip() == "new=0"
    assert run(base + "apl_hard_fault; printf 'repeat=%s\\n' \"$?\"", env).strip() == "repeat=1"

    # 滚动窗口里旧事件被挤出、总命中数不变时仍识别新事件。
    dmesg.write_text("[10.000] cp work abnormal\n" + "noise\n" * 299)
    run(base + "apl_hard_fault", env)
    dmesg.write_text("noise\n" * 299 + "[11.000] cp work abnormal\n")
    assert run(base + "apl_hard_fault; printf 'roll=%s\\n' \"$?\"", env).strip() == "roll=0"

    # 换启动后同一份历史日志只建立新基线，不误报；随后新增事件仍报一次。
    boot.write_text("boot-b\n")
    assert run(base + "apl_hard_fault; printf 'reboot=%s\\n' \"$?\"", env).strip() == "reboot=1"
    dmesg.write_text(dmesg.read_text() + "[12.000] cp work abnormal\n")
    assert run(base + "apl_hard_fault; printf 'after-reboot=%s\\n' \"$?\"", env).strip() == "after-reboot=0"

    # dmesg 不可读时不推进游标、不制造故障。
    before = (sb / "state/apl_dmesg_mark").read_text()
    write_exe(bindir / "dmesg", "exit 1\n")
    assert run(base + "apl_hard_fault; printf 'unreadable=%s\\n' \"$?\"", env).strip() == "unreadable=2"
    assert (sb / "state/apl_dmesg_mark").read_text() == before

    # 超大内核日志在筛选前限界，保护事件仍能被增量识别。
    write_exe(bindir / "dmesg", 'cat "$DMSG_FILE"\n')
    dmesg.write_text(("noise\n" * 20000) + "[20.000] cp work abnormal\n")
    assert run(base + "apl_hard_fault; printf 'large=%s\\n' \"$?\"", env).strip() == "large=0"


def power_env(sb):
    nodes = sb / "nodes"
    nodes.mkdir(parents=True)
    for name, value in {
        "wired_curr": "12400000", "wired_curr2": "12400000",
        "wireless_curr": "12400", "wired_ctrl": "0", "wireless_ctrl": "0",
        "wired_th": "1", "wireless_th": "1",
    }.items():
        (nodes / name).write_text(value)
    state = sb / "state"
    state.mkdir()
    bat = sb / "battery"
    bat.mkdir()
    (bat / "status").write_text("Charging")
    env = dict(os.environ)
    env.update({"STATE": str(state), "BT_STATE": str(state), "BAT": str(bat),
                "WIRED_CURR": str(nodes / "wired_curr"),
                "WIRED_CURR2": str(nodes / "wired_curr2"),
                "WIRELESS_CURR": str(nodes / "wireless_curr"),
                "WIRED_CTRL": str(nodes / "wired_ctrl"),
                "WIRELESS_CTRL": str(nodes / "wireless_ctrl"),
                "WIRED_TH": str(nodes / "wired_th"),
                "WIRELESS_TH": str(nodes / "wireless_th"), "BAT_TEMP": "400",
                "APL_TARGET": str(state / "apl_target_ma")})
    return env, nodes, state


def apl_tick_case(sb):
    state = sb / "state"
    state.mkdir(parents=True)
    (state / "apl_target_ma").write_text("3000")
    conf = sb / "config"
    conf.mkdir()
    (conf / "current_mode").write_text("level_5")
    boot = sb / "boot_id"
    boot.write_text("boot-a")
    env = dict(os.environ)
    env.update({"STATE": str(state), "BT_STATE": str(state), "CONFDIR": str(conf), "APL_BOOT_ID_FILE": str(boot),
                "IS_CHARGING": "1", "BAT_STATUS": "Charging", "BAT_CUR": "-3200000",
                "BAT_VOLT": "4000000", "BAT_TEMP": "400", "BAT_CAP": "20", "APL_VTERM": "4570",
                "APL_ONLINE": "1", "FAULT_RC": "0", "LOCK_UNTIL": "9999999999",
                "USB_CONNECTED": "1", "APL_TEMP_LINE": "50", "APL_CEILING": "20000", "BATTERY_MAX": "6000000"})
    script = """
. \"%s\"
date() { echo \"${TEST_NOW:-1000}\"; }
cfg_get() { case \"$1\" in apl_enable) echo 1;; power_limit) echo inf;; cg_min_write_gap) echo 60;; apl_max_ma) echo 20000;; *) echo \"$2\";; esac; }
apl_num() { case \"$1\" in ''|*[!0-9]*) echo 0;; *) echo \"$1\";; esac; }
apl_rd() { case \"$1\" in *online) echo 1;; *constant_charge_current_max) echo \"${BATTERY_MAX:-0}\";; *) echo 0;; esac; }
apl_vterm_mv() { echo "${APL_VTERM:-4570}"; }
apl_ceiling_ma() {
  local c=${APL_TEST_CAP:-20000} raw
  raw=$(apl_rd /sys/class/power_supply/battery/constant_charge_current_max)
  case "$raw" in ''|*[!0-9]*) raw=0;; esac
  [ "$raw" -lt 100000 ] || [ "$raw" -gt 100000000 ] || { raw=$((raw / 1000)); [ "$raw" -ge "$c" ] || c=$raw; }
  echo "$c"
}
dt_curr_cap_ma() { echo "${APL_CEILING:-20000}"; }
apl_mode_temp_c() { apl_level_temp_c "$(cat "$CONFDIR/current_mode" 2>/dev/null)"; }
apl_level_temp_c() { case "$1" in level_1) echo 30;; level_2) echo 35;; level_3) echo 40;; level_4) echo 45;; level_5) echo 50;; *) echo '';; esac; }
apl_adp_fp() { echo test; }
apl_prof_get() { case \"$1\" in seen) echo 9999999999;; *) echo 0;; esac; }
apl_adp_check() { :; }
apl_prof_best() { :; }
apl_prof_set() { :; }
apl_state_load() {
  apl_prev_uon=1; apl_warm_start=0; apl_lock_until=${LOCK_UNTIL:-0}; apl_low_cnt=0
  apl_stable_cnt=0; apl_stab_ibat=0; apl_prev_ibat=0; apl_last_up=${APL_LAST_UP:-900}; apl_thermal_prev=0; apl_last_tier=
  [ -n "${APL_STATE_FIXTURE:-}" ] && apl_stable_cnt=$APL_STATE_FIXTURE
}
apl_state_save() { APL_STATE_FIXTURE=$apl_stable_cnt; export APL_STATE_FIXTURE; }
apl_hard_fault() { return "$FAULT_RC"; }
blog() { :; }
apl_tick
cat \"$APL_TGT\"
""" % APL
    # 低目标硬故障不得被 cap 的 50% floor 抬高。
    (state / "apl_state").write_text("1 0 0 0 0 0 0 900 0")
    env["FAULT_RC"] = "0"
    env["BATTERY_MAX"] = "100000000"
    env["LOCK_UNTIL"] = "9999999999"
    out = run(script, env).strip()
    assert out == "2560", out

    # 锁内温度回落时不应用温控回升斜率。
    (state / "apl_target_ma").write_text("3000")
    env["LOCK_UNTIL"] = "1030"
    env["FAULT_RC"] = "1"
    out = run(script, env).strip()
    assert out == "3000", out

    # 适配器档案重置不能把锁内已降目标抬回设备上限。
    (state / "apl_target_ma").write_text("10000")
    (state / "apl_state").write_text("1 0 1060 0 0 0 12000 900 0")
    env["LOCK_UNTIL"] = "1060"
    env["APL_FP"] = "changed"
    env["ADP_REASON"] = "适配器变更"
    reset_script = script.replace(
        'apl_adp_fp() { echo test; }',
        'apl_adp_fp() { echo "${APL_FP:-test}"; }').replace(
        'apl_prof_best() { :; }',
        'apl_prof_best() { echo 20000; }').replace(
        'apl_adp_check() { :; }',
        'apl_adp_check() { [ -n "${ADP_REASON:-}" ] && echo "$ADP_REASON"; }')
    out = run(reset_script, env).strip()
    assert out == "10000", out

    # 持续超温时APL按斜率降低目标，且不突破5W安全底线。
    env.pop("ADP_REASON", None)
    (conf / "current_mode").write_text("level_1")
    env["LOCK_UNTIL"] = "0"
    env["BAT_TEMP"] = "510"
    env["BAT_CUR"] = "-2100000"
    (state / "apl_target_ma").write_text("2100")
    targets = [int(run(script, env).strip()) for _ in range(4)]
    assert targets[0] < 2000, targets
    assert all(b <= a for a, b in zip(targets, targets[1:])), targets
    assert min(targets) >= 1250, targets

    # Leaving the one档 thermal line clears its saved recovery cap and resumes the DTBO policy.
    (state / "apl_state").write_text("1 0 0 0 0 0 5800 900 1 level_1")
    (state / "apl_target_ma").write_text("8800")
    (conf / "current_mode").write_text("unlimited")
    env.update({"LOCK_UNTIL": "0", "FAULT_RC": "1", "BAT_TEMP": "450", "APL_CEILING": "20000", "APL_TEST_CAP": "6000", "BAT_CUR": "-5500000"})
    out = run(script, env).strip()
    assert out == "6000", out

    # A driver-reported 6A ceiling, not the 20A software ceiling, bounds APL ramp-up.
    (state / "apl_state").write_text("1 0 0 0 0 0 0 0 0")
    (state / "apl_target_ma").write_text("5500")
    env.update({"BAT_TEMP": "350", "BAT_CUR": "-5500000", "BAT_VOLT": "4000000", "APL_STATE_FIXTURE": "6", "APL_LAST_UP": "900"})
    env["TEST_NOW"] = "1000"
    target = int(run(script, env).strip())
    assert 5500 < target <= 6000, target

    # High SOC taper: let power.sh take over instead of treating it as a collapse.
    env.update({"BAT_CAP": "97", "BAT_TEMP": "400", "BAT_CUR": "-900000", "BAT_VOLT": "4500000",
                "LOCK_UNTIL": "0", "FAULT_RC": "1", "TEST_NOW": "2000"})
    (conf / "current_mode").write_text("unlimited")
    (state / "apl_target_ma").write_text("2000")
    let_script = script.replace('cat \"$APL_TGT\"', '[ -f \"$APL_TGT\" ] && cat \"$APL_TGT\" || echo gone')
    assert run(let_script, env).strip() == "gone"
    env["BAT_CAP"] = "50"
    out = run(let_script, env).strip()
    assert out.isdigit() and int(out) >= 1250, out


def wireless_untouched_case(sb):
    env, nodes, state = power_env(sb)
    sentinel = sb / "wireless-writes"
    env["WIRELESS_WRITE_LOG"] = str(sentinel)
    (state / "power_orig_curr").write_text("12400000")
    (state / "power_orig_wless").write_text("5")
    (state / "power_orig_wireless_ctrl").write_text("4")
    (state / "power_orig_wireless_th").write_text("0")
    script = """
wnode() {
  case "$1" in *wireless*) printf '%%s=%%s\\n' "$1" "$2" >> "$WIRELESS_WRITE_LOG";; esac
  printf '%%s' "$2" > "$1"
}
cfg_get() { case "$1" in apl_enable) echo 0;; cg_min_write_gap) echo 60;; thermal_ceil) echo 580;; thermal_ceil_hyst) echo 30;; *) echo "$2";; esac; }
norm_power() { printf '%%s' "$1"; }
apl_ceiling_ma() { echo 20000; }
ctrl_level() { echo 4; }
tier_watt() { echo 20; }
read_voltage_uv() { echo 4000000; }
watt_to_ma() { echo 5000; }
power_closed_loop_ma() { echo "$2"; }
cg_gap_ok() { return 0; }
cg_note_write() { :; }
log() { :; }
. "%s"
power_apply level_3
restore_gate
restore_current
""" % POWER
    run(script, env)
    assert not sentinel.exists() or sentinel.read_text() == "", sentinel.read_text()
    assert (nodes / "wireless_curr").read_text() == "12400"
    assert (nodes / "wireless_ctrl").read_text() == "0"
    assert (nodes / "wireless_th").read_text() == "1"
    run(f'. "{POWER}"\npower_restore', env)
    assert not sentinel.exists() or sentinel.read_text() == "", sentinel.read_text()
    assert (nodes / "wireless_curr").read_text() == "12400"
    assert (nodes / "wireless_ctrl").read_text() == "0"
    assert (nodes / "wireless_th").read_text() == "1"


def wireless_restore_case(sb):
    env, nodes, state = power_env(sb)
    (state / "power_orig_curr").write_text("12400000")
    (nodes / "wireless_curr").write_text("5")
    (nodes / "wired_ctrl").write_text("4")
    (nodes / "wired_th").write_text("1")
    (nodes / "wireless_th").write_text("0")
    run(f'. "{POWER}"\nsave_orig_current\nsave_orig_gate', env)
    assert (state / "power_orig_wless").read_text() == "5"
    for name in ("wireless_ctrl", "wireless_th"):
        (nodes / name).write_text("12")
    (nodes / "wireless_curr").write_text("20")
    (nodes / "wireless_ctrl").write_text("12")
    (nodes / "wireless_th").write_text("0")
    run(f'. "{POWER}"\nrestore_current\nrestore_gate', env)
    assert (nodes / "wireless_curr").read_text().strip() == "20"
    assert (nodes / "wireless_ctrl").read_text().strip() == "12"
    assert (nodes / "wireless_th").read_text().strip() == "0"


def power_case(sb):
    env, nodes, state = power_env(sb)
    env["POWER_CFG"] = ""
    src = """
norm_power() { printf '%%s' \"$1\"; }
cfg_get() { case \"$1\" in apl_enable) [ \"$APL_OFF\" = 1 ] && echo 0 || echo 1;; cg_min_write_gap) echo 60;; thermal_ceil) echo 580;; thermal_ceil_hyst) echo 30;; *) echo \"$2\";; esac; }
apl_ceiling_ma() { echo 20000; }
apl_target_ma() { APL_TARGET_ACTIVE=1; cat \"$STATE/apl_target_ma\"; }
log() { :; }
. \"%s\"
if [ \"$DO_APPLY\" = 1 ]; then power_apply inf; cat \"$WIRED_CURR\"; else printf 'loaded\\n'; fi
""" % POWER
    env["DO_APPLY"] = "1"

    (state / "apl_target_ma").write_text("12000")
    assert run(src, env).strip().endswith("12000000")
    (state / "apl_target_ma").write_text("7800")
    # 目标变化遵守最小间隔，不能每 tick 重协商。
    assert run(src, env).strip().endswith("12000000")
    (state / "apl_urgent").write_text("1")
    assert run(src, env).strip().endswith("7800000")
    assert not (state / "apl_urgent").exists()
    # APL 退出后清除旧安顿标记，切回无 APL 要重新走一次安顿。
    (state / "inf_settled").write_text("1")
    (state / "inf_node_capped").write_text("1")
    env_no_apl = dict(env)
    env_no_apl["APL_OFF"] = "1"
    (state / "cg_last_write_ts").unlink(missing_ok=True)
    switched = run(src, env_no_apl).strip()
    assert switched.endswith("20000000"), switched
    assert (state / "inf_settled").exists()
    assert not (state / "inf_node_capped").exists()
    # 同一 APL 目标、节点已符合时不重复写；清除上一场景时钟只为隔离该断言。
    (state / "cg_last_write_ts").unlink(missing_ok=True)
    assert run(src, env).strip().endswith("7800000")
    (state / "power_write_ts").write_text("sentinel")
    assert run(src, env).strip().endswith("7800000")
    assert (state / "power_write_ts").read_text() == "sentinel"

    # 无 APL 时保持一次性安顿，不因每拍调用再次写电流节点。
    env2, nodes2, state2 = power_env(sb / "no_apl")
    src2 = """
norm_power() { printf '%%s' \"$1\"; }
cfg_get() { case \"$1\" in apl_enable) echo 0;; cg_min_write_gap) echo 60;; thermal_ceil) echo 580;; thermal_ceil_hyst) echo 30;; *) echo \"$2\";; esac; }
apl_ceiling_ma() { echo 20000; }
log() { :; }
. \"%s\"
power_apply inf
cat \"$WIRED_CURR\"
""" % POWER
    (state2 / "inf_settled").unlink(missing_ok=True)
    assert run(src2, env2).strip().endswith("20000000")
    before2 = (nodes2 / "wired_curr").stat().st_mtime_ns
    assert run(src2, env2).strip().endswith("20000000")
    assert (nodes2 / "wired_curr").stat().st_mtime_ns == before2

    # 高温交还 DT 温控时，APL 不应继续写电流节点。
    env_hot, nodes_hot, state_hot = power_env(sb / "hot")
    (state_hot / "apl_target_ma").write_text("7800")
    env_hot["BAT_TEMP"] = "600"
    hot_script = """
norm_power() { printf '%%s' \"$1\"; }
cfg_get() { case \"$1\" in apl_enable) echo 1;; cg_min_write_gap) echo 60;; thermal_ceil) echo 580;; thermal_ceil_hyst) echo 30;; *) echo \"$2\";; esac; }
apl_ceiling_ma() { echo 20000; }
log() { :; }
. \"%s\"
power_apply inf
cat \"$WIRED_CURR\"
""" % POWER
    assert run(hot_script, env_hot).strip() == "12400000"
    assert (nodes_hot / "wired_th").read_text().strip() == "0"

    # 写命令成功但节点不跟随时，连续计数增长、日志限频，后续仍会重试。
    env_fail, nodes_fail, state_fail = power_env(sb / "readback_fail")
    (state_fail / "apl_target_ma").write_text("7800")
    logs = sb / "power.log"
    failure_script = """
norm_power() { printf '%%s' \"$1\"; }
cfg_get() { case \"$1\" in apl_enable) echo 1;; cg_min_write_gap) echo 60;; thermal_ceil) echo 580;; thermal_ceil_hyst) echo 30;; *) echo \"$2\";; esac; }
apl_ceiling_ma() { echo 20000; }
. \"%s\"
log() { printf '%%s\\n' \"$*\" >> \"$TEST_LOG\"; }
wnode() { [ \"$1\" = \"$WIRED_CURR\" ] && return 0; printf '%%s' \"$2\" > \"$1\"; }
date() { echo 1000; }
power_apply inf
printf 0 > \"$BT_STATE/cg_last_write_ts\"
power_apply inf
printf 0 > \"$BT_STATE/cg_last_write_ts\"
power_apply inf
cat \"$BT_STATE/apl_target_fail_count\"
""" % POWER
    env_fail["TEST_LOG"] = str(logs)
    assert run(failure_script, env_fail).strip() == "3"
    assert logs.read_text().count("回读未生效") == 1
    assert (nodes_fail / "wired_curr").read_text() == "12400000"

    # 缺 wired current 节点不应被 nodes_ok 当作已生效。
    missing = sb / "missing"
    env3, nodes3, state3 = power_env(sb / "missing_curr")
    (nodes3 / "wired_curr").unlink()
    env3["WIRED_CURR"] = str(missing)
    missing_script = """
norm_power() { printf '%%s' \"$1\"; }
cfg_get() { case \"$1\" in apl_enable) echo 0;; cg_min_write_gap) echo 60;; thermal_ceil) echo 580;; thermal_ceil_hyst) echo 30;; *) echo \"$2\";; esac; }
apl_ceiling_ma() { echo 20000; }
log() { :; }
. \"%s\"
power_apply inf
test ! -f \"$BT_STATE/inf_settled\"
""" % POWER
    assert run(missing_script, env3).strip() == ""


def thermal_case(sb):
    conf = sb / "conf"
    conf.mkdir(parents=True)
    (conf / "rt_thermal_daemon").write_text("fake-daemon")
    (conf / "rt_thermal_path").write_text(str(sb))
    (conf / "rt_charge_node").write_text("/sys/class/power_supply/battery/charge_control_limit")
    (conf / "rt_charge_orig").write_text("7")
    env = dict(os.environ)
    env.update({"CONFDIR": str(conf), "MODDIR": str(sb), "STATE": str(sb / "state"),
                "DATA_DIR": str(sb), "BT_STATE": str(sb / "state")})
    (sb / "state").mkdir()
    src = ". \"%s\"\ncharge_node_supported /sys/class/power_supply/battery/charge_control_limit; printf 'bad=%%s\\n' \"$?\"\nbootstrap; printf 'node=%%s\\n' \"$CHARGE_NODE\"" % THERMAL
    out = run(src, env)
    assert "bad=1" in out
    assert "node=" in out
    assert not (conf / "rt_charge_node").exists()
    assert not (conf / "rt_charge_orig").exists()

    (conf / "rt_charge_node").write_text("")
    (conf / "rt_charge_orig").write_text("1234567")
    run(src, env)
    assert (conf / "rt_charge_node").exists()
    assert (conf / "rt_charge_node").read_text() == ""
    assert (conf / "rt_charge_orig").read_text() == "1234567"

    (conf / "rt_charge_node").write_text("/sys/class/power_supply/battery/unknown_current")
    run(src, env)
    assert not (conf / "rt_charge_node").exists()
    assert (conf / "rt_charge_orig").read_text() == "1234567"


def voltage_shutdown_case(sb):
    bat = sb / "battery"
    bat.mkdir(parents=True)
    for name, value in {"temp": "250", "current_now": "0", "capacity": "50",
                        "status": "Discharging"}.items():
        (bat / name).write_text(value)
    state = sb / "state"
    logdir = sb / "log"
    module = sb / "module"
    for d in (state, logdir, module):
        d.mkdir(parents=True, exist_ok=True)
    script = '''
BT_STATE="%s"; BAT_STATE="%s"; LOGDIR="%s"; MODDIR="%s"; XJ_READONLY=0
. "%s"
. "%s"
log() { :; }
cfg_get() {
  case "$1" in
    term_v) echo 3400 ;;
    unlock|pss_disable|cloud_guard) echo 0 ;;
    pss_low_soc) echo 0 ;;
    pss_low_hyst) echo 5 ;;
    shutdown_check_soc) echo 20 ;;
    shutdown_exit_soc) echo 25 ;;
    shutdown_check_interval) echo 2 ;;
    cycle_fake) echo "" ;;
    *) echo "${2:-}" ;;
  esac
}
cloud_tick() { :; }
baa_state() { echo active; }
reboot() { echo shutdown_requested; }
svc() { echo shutdown_requested; }
sample
XJ_NOW=0 XJ_CONFIG_CHANGED=1 XJ_CONNECTION_CHANGED=0 battery_guard_tick
''' % (state, state, logdir, module, ROOT / "engine/lib/data.sh",
       ROOT / "engine/lib/battery.sh")
    env = dict(os.environ, BAT=str(bat), BT_STATE=str(state),
               BAT_STATE=str(state), LOGDIR=str(logdir), MODDIR=str(module),
               XJ_READONLY="0")
    cases = [(None, 50, False), ("bad", 50, False), ("0", 50, False),
             ("3300000", 50, False), ("3300000", 20, True)]
    for voltage, soc, shutdown in cases:
        (bat / "capacity").write_text(str(soc))
        node = bat / "voltage_now"
        if voltage is None:
            node.unlink(missing_ok=True)
        else:
            node.write_text(voltage)
        env["BAT_CAP"] = str(soc)
        out = run(script, env)
        assert ("shutdown_requested" in out) == shutdown, (voltage, soc, out)


def thermal_backup_case(sb):
    conf = sb / "control"
    live = sb / "config"
    conf.mkdir(parents=True)
    live.mkdir()
    (live / "thermal-normal.conf").write_text("original")
    env = dict(os.environ, CONFDIR=str(conf), MODDIR=str(sb / "module"),
               DATA_DIR=str(sb), TEST_LIVE=str(live))
    base = '. "%s"\nTHERMAL_PATH="$TEST_LIVE"\n' % THERMAL
    base += 'log() { :; }\nunlock_files() { :; }\nchown() { :; }\nchcon() { :; }\n'
    fail = 'cp() { return 1; }\n'
    out = run(base + fail + 'factory_backup; echo backup=$?\nbackup_ready; echo ready=$?\n', env)
    assert "backup=1" in out and "ready=1" in out, out
    assert not (sb / "backup/thermal-config/.done").exists()
    assert (live / "thermal-normal.conf").read_text() == "original"
    marker = sb / "backup/thermal-config/.done"
    marker.write_text("legacy incomplete backup")
    partial = 'cp() { command cp "$@"; return 1; }\n'
    out = run(base + partial + 'factory_backup; echo backup=$?\nbackup_ready; echo ready=$?\n', env)
    assert "backup=1" in out and "ready=1" in out, out
    assert not marker.exists()
    # 无备份或复制失败时保留现有配置，状态不能提前切到 default。
    (conf / "current_mode").write_text("level_3")
    out = run(base + 'restore_factory; echo restore=$?\n', env)
    assert "restore=1" in out, out
    (sb / "module").mkdir()
    (sb / "module/t_blank").write_text("replacement")
    out = run(base + fail + 'apply_conf_mode unlimited "$MODDIR/t_blank"; echo apply=$?\n', env)
    assert "apply=1" in out, out
    assert (live / "thermal-normal.conf").read_text() == "original"
    run(base + 'factory_backup\n', env)
    assert (sb / "backup/thermal-config/.done").is_file()
    (live / "thermal-normal.conf").write_text("active")
    out = run(base + fail + 'apply_default; echo apply=$?\n', env)
    assert "apply=1" in out, out
    assert (conf / "current_mode").read_text() == "level_3"
    assert (live / "thermal-normal.conf").read_text() == "active"
    out = run(base + 'chcon() { return 1; }\nrestore_factory; echo restore=$?\n', env)
    assert "restore=1" in out, out
    assert (live / "thermal-normal.conf").read_text() == "active"
    # 提交失败时回滚，之后仍能正常还原。
    mv_fail = 'mv() { case "$1" in *.previous) command mv "$@";; *.restore.*) return 1;; *) command mv "$@";; esac; }\n'
    out = run(base + mv_fail + 'restore_factory; echo restore=$?\n', env)
    assert "restore=1" in out, out
    assert (live / "thermal-normal.conf").read_text() == "active"
    out = run(base + 'restore_factory; echo restore=$?\n', env)
    assert "restore=0" in out, out
    assert (live / "thermal-normal.conf").read_text() == "original"
    assert not (live / ".done").exists()
    assert not list(sb.glob("config.restore.*"))


def governor_case(sb):
    state = sb / "state"
    conf = sb / "config"
    state.mkdir(parents=True)
    conf.mkdir()
    daemon = (ROOT / "engine/xjdaemon.sh").read_text()
    governor = "charge_gov_limit() {" + daemon.split("charge_gov_limit() {", 1)[1].split("\nplan_in_window() {", 1)[0]
    governor += "\nplan_in_window() { return 1; }\n"
    governor += "effective_tier() {" + daemon.split("effective_tier() {", 1)[1].split("\nbypass_decide() {", 1)[0]
    script = '''
CONFDIR="$CONF"; BT_STATE="$STATE"
json_get() { echo true; }
cfg_get() { [ "$1" = power_limit ] && echo inf || echo "${2:-}"; }
power_tier() { echo inf; }
power_rank() { case "$1" in inf) echo 999;; fast) echo 40;; mid) echo 25;; slow) echo 10;; *) echo 999;; esac; }
''' + governor + "\neffective_tier\n"
    env = dict(os.environ, CONF=str(conf), STATE=str(state), BT_STATE=str(state))
    expected = {
        ("level_1", 29): "inf", ("level_1", 30): "mid", ("level_1", 33): "mid", ("level_1", 34): "slow",
        ("level_2", 34): "inf", ("level_2", 35): "mid", ("level_3", 39): "inf",
        ("level_3", 40): "mid", ("level_4", 44): "inf", ("level_4", 45): "mid",
        ("level_5", 49): "inf", ("level_5", 50): "mid",
    }
    for (mode, temp), tier in expected.items():
        (conf / "current_mode").write_text(mode)
        (state / "charge_gov.state").unlink(missing_ok=True)
        out = run(script, dict(env, BAT_TEMP=str(temp * 10))).strip()
        assert out == tier, (mode, temp, tier, out)
    (conf / "current_mode").write_text("level_4")
    (state / "charge_gov.state").write_text("level_4|mid")
    assert run(script, dict(env, BAT_TEMP="430")).strip() == "mid"
    assert run(script, dict(env, BAT_TEMP="420")).strip() == "inf"
    (state / "charge_gov.state").write_text("level_4|slow")
    assert run(script, dict(env, BAT_TEMP="470")).strip() == "slow"
    assert run(script, dict(env, BAT_TEMP="460")).strip() == "mid"
    assert run(script, dict(env, BAT_TEMP="420")).strip() == "inf"
    (state / "charge_gov.state").write_text("level_4|mid")
    assert run(script, dict(env, BAT_TEMP="440")).strip() == "mid"
    assert run(script, dict(env, BAT_TEMP="420")).strip() == "inf"
    (state / "charge_gov.state").unlink(missing_ok=True)
    (state / "charge_gov.state.tmp").mkdir()
    assert run(script, dict(env, BAT_TEMP="450")).strip() == "mid"
    assert run(script, dict(env, BAT_TEMP="490")).strip() == "slow"


def governor_scene_order_case(sb):
    daemon = (ROOT / "engine/xjdaemon.sh").read_text()
    tick = 'run_tick() {' + daemon.split('run_tick() {', 1)[1].split('\nbootstrap ||', 1)[0]
    once = daemon.split('TICK_N=0\n', 1)[1].split('# 单实例', 1)[0]
    governor = 'effective_tier() {' + daemon.split('effective_tier() {', 1)[1].split('\nbypass_decide() {', 1)[0]
    script = '''
THERMAL_DAEMON=test; THERMAL_PATH="$CONFDIR"; CHARGE_INTERVAL=1; POLL_INTERVAL=5
TICK_N=0; BAT_TEMP=320; BAT_CAP=50; BAT_STATUS=Charging; BAT_CUR=-3000000; BAT_VOLT=4000000
runtime_now() { XJ_NOW=100; }
runtime_config_refresh() { XJ_CONFIG_CHANGED=1; }
sample() { IS_CHARGING=1; }
connection_read() { USB_CONNECTED=1; WIRELESS_CONNECTED=0; XJ_CONNECTION_CHANGED=0; }
json_get() { case "$1" in charge_mode|charge_gov|enabled) echo true;; charge_level) echo level_4;; game_mode|power_plan_on) echo false;; *) echo "${2:-}";; esac; }
cfg_get() { case "$1" in power_limit) echo inf;; apl_enable) echo 0;; *) echo "${2:-}";; esac; }
power_tier() { echo inf; }
power_rank() { case "$1" in inf) echo 999;; mid) echo 25;; slow) echo 10;; *) echo 999;; esac; }
norm_power() { echo "$1"; }
plan_in_window() { return 1; }
date() { case "$1" in +%H) echo 12;; +%s) echo 100;; *) echo 2026-11-22;; esac; }
app_visible() { return 1; }
screen_on() { return 0; }
apply_default() { printf 'default' > "$CONFDIR/current_mode"; }
apply_conf_mode() { printf '%s' "$1" > "$CONFDIR/current_mode"; }
src_for_mode() { echo "$MODDIR/thermal/${1#level_}/thermal-scene.conf"; }
guard_apply() { echo "$1"; }
valid_mode() { return 0; }
apply_fps() { :; }
apply_bypass() { :; }
release_charge() { :; }
rear_tick() { :; }
rear_stop() { :; }
retention_tick() { :; }
battery_guard_tick() { :; }
publish_tick() { :; }
write_status_json() { :; }
update_status() { :; }
diag_charge_tick() { :; }
apl_tick() { :; }
power_apply() { echo "$1" > "$STATE/effective_tier"; echo "POWER_APPLY=$1"; }
power_restore() { :; }
hitemp_guard() { :; }
hyperos_keepalive() { :; }
''' + governor + '\n' + tick + '\nset -- --once\n' + once
    for d in ("conf", "state", "mod"):
        (sb / d).mkdir(parents=True, exist_ok=True)
    for level in range(1, 6):
        path = sb / "mod" / "thermal" / str(level) / "thermal-scene.conf"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("x" * 600)
    (sb / "conf" / "current_mode").write_text("default")
    env = dict(os.environ, MODDIR=str(sb / "mod"), CONFDIR=str(sb / "conf"), STATE=str(sb / "state"), BT_STATE=str(sb / "state"), LOGDIR=str(sb / "state"))
    out = run(script, env)
    assert "POWER_APPLY=inf" in out, out
    assert (sb / "conf" / "current_mode").read_text() == "level_4", (sb / "conf" / "current_mode").read_text()
    assert (sb / "state" / "effective_tier").read_text().strip() == "inf", (sb / "state" / "effective_tier").read_text()


def daemon_apply_case(sb):
    sb.mkdir(parents=True)
    (sb / "current_mode").write_text("level_3")
    daemon = (ROOT / "engine/xjdaemon.sh").read_text()
    tick = 'run_tick() {' + daemon.split('run_tick() {', 1)[1].split('\nbootstrap ||', 1)[0]
    once = daemon.split('TICK_N=0\n', 1)[1].split('# 单实例', 1)[0]
    script = '''
THERMAL_DAEMON=test; THERMAL_PATH="$CONFDIR"
runtime_now() { XJ_NOW=100; }
runtime_config_refresh() { XJ_CONFIG_CHANGED=1; }
sample() { IS_CHARGING=0; }
connection_read() { XJ_CONNECTION_CHANGED=0; USB_CONNECTED=0; WIRELESS_CONNECTED=0; }
ensure_thermal_dir() { :; }
hitemp_guard() { :; }
hyperos_keepalive() { :; }
json_get() { echo false; }
release_charge() { :; }
rear_stop() { :; }
apply_default() { return 1; }
update_status() { echo unexpected_update; }
''' + tick + '\nset -- --once\n' + once
    env = dict(os.environ, MODDIR=str(sb), CONFDIR=str(sb))
    proc = subprocess.run(["sh", "-c", script], env=env, text=True, capture_output=True)
    assert proc.returncode == 1, (proc.returncode, proc.stdout, proc.stderr)
    assert "unexpected_update" not in proc.stdout
    assert (sb / "current_mode").read_text() == "level_3"


def main():
    with tempfile.TemporaryDirectory(prefix="xj-charge-regression-") as raw:
        sb = Path(raw)
        apl_case(sb / "apl")
        apl_tick_case(sb / "apl_tick")
        wireless_untouched_case(sb / "wireless_untouched")
        wireless_restore_case(sb / "wireless_restore")
        power_case(sb / "power")
        thermal_case(sb / "thermal")
        voltage_shutdown_case(sb / "voltage")
        thermal_backup_case(sb / "backup")
        governor_case(sb / "governor")
        governor_scene_order_case(sb / "governor_scene")
        daemon_apply_case(sb / "daemon")
    print("charge control regression: PASS (APL, governor thresholds/hysteresis, inf/APL throttling, node boundaries, shutdown voltage, thermal backup/restore)")


if __name__ == "__main__":
    main()
