#!/usr/bin/env python3
"""动态功率控制的隔离回归，不访问设备节点。"""

import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POWER = ROOT / "engine/lib/power.sh"


def run(script, env):
    result = subprocess.run(["sh", "-c", script], env=env, text=True, capture_output=True, timeout=10)
    assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
    return result.stdout.strip()


def main():
    with tempfile.TemporaryDirectory(prefix="xj-dynamic-power-") as raw:
        sandbox = Path(raw)
        state = sandbox / "state"
        conf = sandbox / "conf"
        battery = sandbox / "battery"
        for path in (state, conf, battery):
            path.mkdir()
        (battery / "status").write_text("Charging")
        (battery / "current_now").write_text("-13000000")
        (conf / "current_mode").write_text("level_2")
        env = dict(os.environ, BT_STATE=str(state), STATE=str(state), CONFDIR=str(conf),
                   BAT=str(battery), BAT_TEMP="359", BAT_TEMP_VALID="1", BAT_VOLT="4000000", TEST_NOW="1000")
        base = f'''
. "{POWER}"
. "{ROOT / 'engine/lib/apl_temp.sh'}"
json_get() {{ [ "$1" = charge_gov ] && echo "${{GOV:-true}}" || echo "$2"; }}
cfg_get() {{ echo "$2"; }}
date() {{ echo "$TEST_NOW"; }}
'''
        thermal = base + '\npower_thermal_limit_ma "${WANT:-9500}"\n'
        first = int(run(thermal, env))
        assert 1250 <= first < 9500, first
        assert first not in (6250, 2500), first
        assert int(run(thermal, env)) == first
        values = [first]
        for tick in range(1, 18):
            env["TEST_NOW"] = str(1000 + tick * 2)
            values.append(int(run(thermal, env)))
        assert all(right <= left for left, right in zip(values, values[1:])), values
        assert values[-1] == 1250, values
        env["BAT_TEMP"] = "340"
        env["TEST_NOW"] = "1100"
        assert int(run(thermal, env)) == 1250
        env["BAT_TEMP"] = "329"
        env["TEST_NOW"] = "1102"
        recovered = int(run(thermal, env))
        assert 1250 < recovered < 9500, recovered
        env["TEST_NOW"] = "1104"
        assert int(run(thermal, dict(env, WANT="1000"))) <= 1000
        assert int(run(thermal, dict(env, GOV="false"))) == 9500
        assert not (state / "power_thermal_ma").exists()
        for level, line in enumerate((300, 350, 400, 450, 500), 1):
            (conf / "current_mode").write_text(f"level_{level}")
            env.update(BAT_TEMP=str(line), TEST_NOW=str(1200 + level * 2))
            assert int(run(thermal, env)) < 9500, (level, line)
        (conf / "current_mode").write_text("unlimited")
        assert int(run(thermal, env)) == 9500

        loop = base + '\npower_closed_loop_ma 38 9500\n'
        history = []
        for tick in range(20):
            history.append(int(run(loop, dict(env, TICK_N=str(tick)))))
        assert all(right <= left for left, right in zip(history, history[1:])), history
        assert history[-1] == 2375, history

        for name, value in {"wired_curr": "13000000", "wired_curr2": "13000000",
                            "wired_ctrl": "0", "wired_th": "1"}.items():
            (sandbox / name).write_text(value)
        env.update(WIRED_CURR=str(sandbox / "wired_curr"), WIRED_CURR2=str(sandbox / "wired_curr2"),
                   WIRED_CTRL=str(sandbox / "wired_ctrl"), WIRED_TH=str(sandbox / "wired_th"),
                   BAT_TEMP="359", TEST_NOW="2000", TICK_N="1")
        (conf / "current_mode").write_text("level_2")
        apply = base + '''
norm_power() { echo "$1"; }
apl_target_ma() { return 1; }
apl_ceiling_ma() { echo 20000; }
read_voltage_uv() { echo 4000000; }
max_ma() { echo 20000; }
log() { :; }
power_apply 38
'''
        run(apply, env)
        assert (state / "power_apply_state").read_text() == "applied"
        assert 1250000 <= int((sandbox / "wired_curr").read_text()) < 9500000
        assert 5 <= float((state / "power_target_w").read_text()) < 38
        env["TEST_NOW"] = "2002"
        failed = apply.replace('power_apply 38', '''wnode() { return 1; }
power_apply 38
printf 'result=%s' "$?"
''')
        assert run(failed, env) == "result=1"
        assert (state / "power_apply_state").read_text() == "failed"

        (state / "power_sig").unlink(missing_ok=True)
        env.update(TEST_NOW="2004", BAT_TEMP="350")
        run(apply.replace('power_apply 38', 'power_apply inf'), env)
        assert (state / "power_apply_state").read_text() == "applied"
        assert int((sandbox / "wired_curr").read_text()) < 20000000
        held = int((sandbox / "wired_curr").read_text())
        stable = apply.replace('power_apply 38', '''wnode() { echo unexpected_write; return 1; }
power_apply inf
''')
        assert run(stable, env) == ""
        assert (state / "power_apply_state").read_text() == "applied"
        deferred = apply.replace('power_apply 38', '''json_get() { echo false; }
cg_gap_ok() { return 1; }
power_apply inf
''')
        run(deferred, env)
        assert (state / "power_apply_state").read_text() == "pending"
        assert int((sandbox / "wired_curr").read_text()) == held
        assert (state / "power_sig").read_text().endswith("|thermal")
        resume = deferred.replace('cg_gap_ok() { return 1; }', 'cg_gap_ok() { return 0; }')
        run(resume, env)
        assert (state / "power_apply_state").read_text() == "applied"
        assert int((sandbox / "wired_curr").read_text()) == 20000000

        env.update(TEST_NOW="2006", BAT_TEMP="350")
        apl_active = apply.replace('power_apply 38', '''apl_target_ma() { echo 20000; }
cg_gap_ok() { return 1; }
power_apply inf
''')
        run(apl_active, env)
        assert (state / "power_apply_state").read_text() == "applied"
        assert int((sandbox / "wired_curr").read_text()) < 20000000
        (state / "apl_urgent").write_text("1")
        run(apl_active, env)
        assert not (state / "apl_urgent").exists()
        assert run(apl_active.replace('power_apply inf', 'wnode() { echo unexpected_write; return 1; }\npower_apply inf'), env) == ""

        daemon = (ROOT / "engine/xjdaemon.sh").read_text()
        effective = 'effective_tier() {' + daemon.split('effective_tier() {', 1)[1].split('\nbypass_decide() {', 1)[0]
        assert '\nbootstrap ||' not in effective and 'while true;' not in effective
        limits = base + '''
power_tier() { echo 38; }
power_rank() { [ "$1" = inf ] && echo 999 || echo "$1"; }
tier_watt() { case "$1" in slow) echo 45;; mid) echo 15;; *) echo "$1";; esac; }
norm_power() { echo "$1"; }
plan_in_window() { return 0; }
json_get() { case "$1" in power_plan_on) echo true;; power_plan_mode) echo "${PLAN:-20}";; *) echo "$2";; esac; }
''' + effective + '\neffective_tier\n'
        assert run(limits, env) == "20"
        assert run(limits, dict(env, PLAN="80")) == "38"
        assert run(limits, dict(env, PLAN="slow")) == "38"
        assert run(limits, dict(env, PLAN="mid")) == "mid"
        control = (ROOT / "engine/xjctl.sh").read_text()
        setter = control.split('  power)\n', 1)[1].split('\n  thermal)', 1)[0]
        assert 'power_apply' not in setter
        assert 'cfg_set power_limit' in setter
        set_command = base + '''
set -- power set "${REQUEST:-38}"
norm_power() { echo "$1"; }
cfg_set() { printf '%s' "$2" > "$STATE/saved_limit"; }
power_tier() { cat "$STATE/saved_limit"; }
power_apply() { echo unexpected_node_write; return 1; }
''' + '\ncase "$1" in power)\n' + setter + '\nesac\n'
        assert run(set_command, env) == "saved=38"
        (state / "saved_limit").unlink()
        invalid = subprocess.run(["sh", "-c", set_command], env=dict(env, REQUEST="invalid"),
                                 text=True, capture_output=True, timeout=10)
        assert invalid.returncode == 2 and not (state / "saved_limit").exists()

    print("dynamic power regression: PASS (temperature lines, continuous reduction/recovery, caps, hysteresis, closed-loop convergence)")


if __name__ == "__main__":
    main()
