#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def shell(code, env):
    p = subprocess.run(['sh', '-c', code], env=dict(os.environ, **env), text=True, capture_output=True)
    assert p.returncode == 0, (p.stdout, p.stderr)
    return p.stdout


def main():
    with tempfile.TemporaryDirectory(prefix='xj-idle-') as raw:
        sb = Path(raw)
        for n in ['config', 'state', 'battery', 'module']:
            (sb / n).mkdir()
        settings = sb / 'config/settings.json'
        settings.write_text('{\n"enabled": true,\n"default_mode": "default",\n"game_mode": true,\n"game_apps": "",\n"charge_mode": true\n}\n')
        conf = sb / 'battery/settings.conf'
        conf.write_text('power_limit=inf\nfreepps=1\nunlock=1\n')
        env = {'ROOT': str(ROOT), 'SB': str(sb)}
        prelude = '''
XJ_READONLY=1
. "$ROOT/engine/lib/env.sh"
MODDIR="$SB/module"; CONFDIR="$SB/config"; STATE="$SB/state"; CACHE="$SB/cache"
BT_CONF="$SB/battery/settings.conf"; BT_STATE="$SB/battery"
runtime_config_refresh
[ "$XJ_CONFIG_CHANGED" = 1 ]
[ "$(json_get enabled false)" = true ]
[ "$(json_get default_mode none)" = default ]
[ "$(cfg_get freepps 0)" = 1 ]
runtime_config_refresh
[ "$XJ_CONFIG_CHANGED" = 0 ]
'''
        shell(prelude + 'json_get enabled false\n', env)
        daemon = (ROOT / 'engine/xjdaemon.sh').read_text()
        script = prelude + '''
TICK_N=0; XJ_PPS_OWNER=1; BT_GUARDS_EXTERNAL=0; MTK_MODE=0; FG_INTERVAL=10
THERMAL_DAEMON=test; THERMAL_PATH="$SB/config"
runtime_now() { XJ_NOW=1000; }
runtime_config_refresh() { XJ_CONFIG_CHANGED=0; }
sample() { IS_CHARGING=0; BAT_STATUS=Discharging; BAT_TEMP=350; BAT_CAP=80; }
connection_read() { USB_CONNECTED=0; WIRELESS_CONNECTED=0; XJ_CONNECTION_CHANGED=0; }
ensure_thermal_dir() { echo BAD_directory; }
hitemp_guard() { :; }; hyperos_keepalive() { :; }; pps_tick() { :; }
retention_tick() { :; }; screen_on() { echo BAD_screen; return 1; }
foreground_pkg() { echo BAD_foreground; }
apl_tick() { echo BAD_apl; }; power_apply() { echo BAD_power; }
apply_default() { :; }; apply_fps() { :; }; is_gaming() { return 1; }
apl_level_temp_c() { :; }; valid_mode() { return 0; }; guard_apply() { echo "$1"; }
battery_guard_tick() { :; }; rear_tick() { :; }; publish_tick() { :; }
'''
        tick = 'run_tick() {' + daemon.split('run_tick() {', 1)[1].split('\nbootstrap ||', 1)[0]
        out = shell(script + tick + '\nrun_tick\n', env)
        assert 'BAD_' not in out, out
        connected = '''
connection_read() { USB_CONNECTED=1; WIRELESS_CONNECTED=0; XJ_CONNECTION_CHANGED=1; }
sample() { IS_CHARGING=0; BAT_STATUS='Not charging'; BAT_TEMP=350; BAT_CAP=80; }
screen_on() { return 1; }
apl_tick() { echo CHARGE_apl; }
power_apply() { echo CHARGE_power; }
effective_tier() { echo inf; }
apply_conf_mode() { echo "THERMAL_$1"; }
src_for_mode() { echo "$SB/valid-thermal"; }
'''
        (sb / 'valid-thermal').write_text('x' * 600)
        out = shell(script + tick + connected + '\nrun_tick\n', env)
        assert 'CHARGE_power' in out and 'THERMAL_level_1' not in out, out
        wireless = connected.replace('USB_CONNECTED=1; WIRELESS_CONNECTED=0', 'USB_CONNECTED=0; WIRELESS_CONNECTED=1')
        out = shell(script + tick + wireless + '\nrun_tick\n', env)
        assert 'CHARGE_power' not in out and 'CHARGE_apl' not in out, out
        modified = tick.replace('if [ "$USB_CONNECTED" = 1 ] && [ "$WIRELESS_CONNECTED" != 1 ]; then', 'if true; then')
        out = shell(script + modified + '\nrun_tick\n', env)
        assert 'BAD_power' in out, 'Mutation must be caught by idle assertion'
        apl = (ROOT / 'engine/lib/apl.sh').read_text()
        apl_tick = 'apl_tick() {' + apl.split('apl_tick() {', 1)[1]
        out = shell('''
cfg_get() { echo 1; }
APL_TGT="$SB/state/target"; STATE="$SB/state"
IS_CHARGING=0; USB_CONNECTED=0
apl_prev_uon=0
getprop() { echo BAD_probe; }
''' + apl_tick + '\napl_tick\n', env)
        assert 'BAD_' not in out
        assert 'ensure_thermal_dir\n  hitemp_guard' not in daemon
    print('idle dispatch regression: PASS (config cache, idle task gates, APL early return)')


if __name__ == '__main__':
    main()
