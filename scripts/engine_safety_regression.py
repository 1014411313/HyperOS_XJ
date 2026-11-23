#!/usr/bin/env python3
"""模拟节点与系统命令验证恢复路径，所有写入只在临时目录。"""
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(code, env):
    result = subprocess.run(['sh', '-c', code], env=env, text=True, capture_output=True, timeout=10)
    assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
    return result.stdout.strip()


def main():
    with tempfile.TemporaryDirectory(prefix='xj-engine-safety-') as raw:
        sb = Path(raw)
        for name in ('battery', 'state', 'conf', 'nodes'):
            (sb / name).mkdir()
        env = dict(os.environ, BAT=str(sb/'battery'), BT_STATE=str(sb/'state'),
                   STATE=str(sb/'state'), CONFDIR=str(sb/'conf'), ROOT=str(ROOT),
                   WIRED_CURR=str(sb/'nodes/current'), WIRED_CURR2=str(sb/'nodes/current2'),
                   WIRED_CTRL=str(sb/'nodes/ctrl'), WIRED_TH=str(sb/'nodes/th'),
                   WIRELESS_CURR=str(sb/'nodes/absent'), SB=str(sb))
        for name, value in {'status':'Charging', 'capacity':'40', 'current_now':'-5000000',
                            'voltage_now':'4000000', 'constant_charge_current_max':'6000000'}.items():
            (sb/'battery'/name).write_text(value+'\n')
        for name, value in {'current':'12000000','current2':'11000000','ctrl':'0','th':'0'}.items():
            (sb/'nodes'/name).write_text(value)
        base = '''
. "$ROOT/engine/lib/power.sh"
. "$ROOT/engine/lib/data.sh"
. "$ROOT/engine/lib/apl_temp.sh"
cfg_get() { echo "$2"; }
json_get() { echo "$2"; }
norm_power() { echo "$1"; }
log() { :; }
'''
        out = run(base+'''sample
printf 'valid=%s gate=%s\n' "$BAT_TEMP_VALID" "$(power_thermal_target)"
power_apply 38; printf 'rc=%s\n' "$?"
cat "$WIRED_CURR"
''',env)
        assert 'valid=0 gate=0' in out and 'rc=1' in out and out.endswith('12000000'),out
        (sb/'nodes/th').write_text('1')
        out=run(base+'''sample
power_apply inf; echo "rc=$?"
cat "$WIRED_TH"
''',env)
        assert 'rc=1' in out and out.endswith('0'),out
        for temp in ('0','-50','350'):
            (sb/'battery/temp').write_text(temp+'\n')
            assert run(base+'sample; echo "$BAT_TEMP_VALID"',env)=='1'
        (sb/'battery/temp').write_text('bad\n')
        assert run(base+'sample; echo "$BAT_TEMP_VALID"',env)=='0'
        assert run(base+'device_current_cap_ma 9500',env)=='6000'
        run(base+'''save_orig_gate
printf 1 > "$WIRED_TH"
printf 7 > "$WIRED_CTRL"
save_orig_gate
restore_gate
''',env)
        assert (sb/'nodes/th').read_text().strip()=='0'
        assert (sb/'nodes/ctrl').read_text().strip()=='0'
        run(base+'''save_orig_current
printf 500000 > "$WIRED_CURR"
printf 500000 > "$WIRED_CURR2"
restore_current
''',env)
        assert (sb/'nodes/current').read_text().strip()=='12000000'
        assert (sb/'nodes/current2').read_text().strip()=='11000000'
        for name in ('inf_settled','inf_node_capped','closed_loop'):
            (sb/'state'/name).write_text('1')
        run(base+'power_restore',env)
        assert all(not (sb/'state'/name).exists() for name in ('inf_settled','inf_node_capped','closed_loop'))
        out=run(base+'''save_orig_gate
wnode() { return 1; }
power_restore; echo "rc=$?"
test -f "$BT_STATE/power_orig_th" && echo kept
''',env)
        assert 'rc=1' in out and 'kept' in out,out
        thermal=(ROOT/'engine/lib/thermal.sh').read_text()
        release='release_charge() {'+thermal.split('release_charge() {',1)[1].split('\nguard_apply()',1)[0]
        assert 'bootstrap' not in release
        out=run(base+release+'''
charge_node_supported() { [ "$1" = "$SB/nodes/current" ]; }
power_restore() { return 0; }
printf '%s|%s\n' "$WIRED_CURR" 12000000 > "$BT_STATE/bypass_current"
: > "$BT_STATE/bypass_current_saved"
: > "$CONFDIR/bypass_active"
printf 500000 > "$WIRED_CURR"
wnode() { return 1; }
release_charge; echo "rc=$?"
test -f "$CONFDIR/bypass_active" && echo pending
wnode() { printf '%s' "$2" > "$1"; }
release_charge
cat "$WIRED_CURR"
''',env)
        assert 'rc=1' in out and 'pending' in out and out.endswith('12000000'),out
        daemon=(ROOT/'engine/xjdaemon.sh').read_text()
        fps='apply_fps() {'+daemon.split('apply_fps() {',1)[1].split('\nruntime_now()',1)[0]
        assert 'while true' not in fps
        run(fps+'''
settings() {
  case "$1" in
    get) [ ! -f "$CONFDIR/$3" ] && echo null || cat "$CONFDIR/$3" ;;
    put) printf '%s' "$4" > "$CONFDIR/$3" ;;
    delete) rm -f "$CONFDIR/$3" ;;
  esac
}
printf 60 > "$CONFDIR/min_refresh_rate"
apply_fps 120
apply_fps 0
test ! -f "$CONFDIR/peak_refresh_rate"
test "$(cat "$CONFDIR/min_refresh_rate")" = 60
''',env)
        plan='plan_in_window() {'+daemon.split('plan_in_window() {',1)[1].split('\n# 用户上限',1)[0]
        out=run(plan+'''
json_get() { case "$1" in power_plan_start) echo 25;; *) echo 7;; esac; }
date() { echo 06; }
plan_in_window; echo "rc=$?"
''',env)
        assert out=='rc=1',out
        battery_source=(ROOT/'engine/lib/battery.sh').read_text()
        unmount='baa_umount() {'+battery_source.split('baa_umount() {',1)[1].split('\npss_state()',1)[0]
        assert 'mount -o bind' not in unmount
        out=run(unmount+'''
baa_tgt() { echo /test/baa; }
grep() { return 0; }
umount() { return 1; }
setprop() { echo unexpected_restart; }
blog() { :; }
baa_umount; echo "rc=$?"
''',env)
        assert out=='rc=1',out
        config_source=(ROOT/'engine/lib/env.sh').read_text()
        setter='cfg_set() {'+config_source.split('cfg_set() {',1)[1].split('\n# ---------- 功率档',1)[0]
        assert 'seed_settings' not in setter
        out=run(setter+'''
BT_CONF="$CONFDIR/test.conf"
config_changed() { echo notified; }
printf 'a=1' > "$BT_CONF"
cfg_set b 'value|with/slash'
cat "$BT_CONF"
mv() { return 1; }
cfg_set a 2; echo "rc=$?"
cat "$BT_CONF"
''',env)
        assert out.count('notified')==1 and 'rc=1' in out and out.count('a=1')==2,out
        assert 'b=value|with/slash' in out,out
        boot_source=(ROOT/'engine/boot.sh').read_text()
        assert 'kill_stale_daemons' in boot_source and 'xjdaemon.sh' in boot_source
    print('engine safety regression: PASS (invalid temperature, zero snapshots, distinct currents, failed restore, bypass retry, FPS, hours)')


if __name__=='__main__':
    main()
