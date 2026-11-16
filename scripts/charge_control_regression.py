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
    boot = sb / "boot_id"
    boot.write_text("boot-a")
    env = dict(os.environ)
    env.update({"STATE": str(state), "BT_STATE": str(state), "APL_BOOT_ID_FILE": str(boot),
                "IS_CHARGING": "1", "BAT_STATUS": "Charging", "BAT_CUR": "-3200000",
                "BAT_VOLT": "4000000", "BAT_TEMP": "400", "BAT_CAP": "20",
                "APL_ONLINE": "1", "FAULT_RC": "0", "LOCK_UNTIL": "9999999999"})
    script = """
. \"%s\"
cfg_get() { case \"$1\" in apl_enable) echo 1;; power_limit) echo inf;; cg_min_write_gap) echo 60;; apl_max_ma) echo 20000;; *) echo \"$2\";; esac; }
apl_num() { case \"$1\" in ''|*[!0-9]*) echo 0;; *) echo \"$1\";; esac; }
apl_rd() { case \"$1\" in *online) echo 1;; *) echo 0;; esac; }
apl_vterm_mv() { echo 4570; }
apl_ceiling_ma() { echo 20000; }
dt_curr_cap_ma() { echo 20000; }
apl_mode_temp_c() { echo 50; }
apl_adp_fp() { echo test; }
apl_prof_get() { case \"$1\" in seen) echo 9999999999;; *) echo 0;; esac; }
apl_adp_check() { :; }
apl_prof_best() { :; }
apl_prof_set() { :; }
apl_state_load() { apl_prev_uon=1; apl_warm_start=0; apl_lock_until=$LOCK_UNTIL; apl_low_cnt=0; apl_stable_cnt=0; apl_stab_ibat=0; apl_prev_ibat=0; apl_last_up=900; apl_thermal_prev=6000; }
apl_state_save() { :; }
apl_hard_fault() { return "$FAULT_RC"; }
blog() { :; }
apl_tick
cat \"$APL_TGT\"
""" % APL
    # 低目标硬故障不得被 cap 的 50% floor 抬高。
    (state / "apl_state").write_text("1 0 0 0 0 0 0 900 0")
    env["FAULT_RC"] = "0"
    env["LOCK_UNTIL"] = "9999999999"
    out = run(script, env).strip()
    assert out == "2560", out

    # 锁内温度回落时不应用温控回升斜率。
    (state / "apl_target_ma").write_text("3000")
    env["LOCK_UNTIL"] = "9999999999"
    env["FAULT_RC"] = "1"
    script_locked = script
    # 此次不报告硬故障，隔离温控回升逻辑；未来锁期内不应回升。
    # 当前测试时钟固定为1000，锁截至1030；读回目标不得大于3000mA。
    out = run(script_locked, env).strip()
    assert out == "3000", out


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


def main():
    with tempfile.TemporaryDirectory(prefix="xj-charge-regression-") as raw:
        sb = Path(raw)
        apl_case(sb / "apl")
        apl_tick_case(sb / "apl_tick")
        power_case(sb / "power")
        thermal_case(sb / "thermal")
    print("charge control regression: PASS (APL cursor, inf/APL target throttling, no-APL settle, node boundaries)")


if __name__ == "__main__":
    main()
