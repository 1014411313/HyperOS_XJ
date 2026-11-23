#!/usr/bin/env python3
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def functions_only(source, name):
    match = re.search(rf"^{re.escape(name)}\(\) \{{\n.*?^\}}\n", source, re.M | re.S)
    assert match, name
    return match.group(0)


def run(script, directory, expected=0):
    result = subprocess.run(["sh", "-c", script], cwd=directory, text=True,
                            capture_output=True, timeout=12)
    assert result.returncode == expected, result.stdout + result.stderr
    return result.stdout


def main():
    boot = (ROOT / "engine/boot.sh").read_text()
    daemon = (ROOT / "engine/xjdaemon.sh").read_text()
    uninstall = (ROOT / "dist/HyperOS_XJ_New/uninstall.sh").read_text()
    assert 'pm uninstall' not in uninstall
    assert 'rm -rf "$DATA_DIR"' not in uninstall
    assert 'rm -rf "$MODDIR"' not in uninstall
    assert 'for _d in config battery log cache state backup;' in uninstall
    # 正式版单实例做法：只依据 pidfile/锁 pid + kill -0 + comm=sh，不扫描进程树。
    assert 'process.sh' not in boot and 'process.sh' not in daemon
    assert 'daemon_process_pids' not in daemon and 'daemon_lock_acquire' not in daemon
    assert '_lock_hold' in daemon and 'daemon_alive' in boot and 'kill_stale_daemons' in boot
    passed = 1
    with tempfile.TemporaryDirectory(prefix="xj-lifecycle-") as temporary:
        sandbox = Path(temporary)
        state = sandbox / "state"
        state.mkdir()
        module = sandbox / "module"
        data = sandbox / "data"
        (module / "engine/lib").mkdir(parents=True)
        (module / "battery/dtbo").mkdir(parents=True)
        (module / "engine/xjctl.sh").write_text('printf "%s\\n" "$*" >> "$CALLS"\n[ "$*" != "power restore" ]\n')
        (module / "engine/xjdaemon.sh").write_text("exit 0\n")
        (module / "engine/lib/env.sh").write_text("fixture\n")
        (module / "battery/dtbo/identity.tsv").write_text("fixture\n")
        (data / "backup/thermal-config").mkdir(parents=True)
        (data / "backup/thermal-config/original").write_text("original")
        (data / "battery/state/dtbo_registry").mkdir(parents=True)
        (data / "battery/state/dtbo_registry/record").write_text("trusted")
        (data / "config").mkdir()
        (data / "config/settings.json").write_text('{}')
        fixture = '\n'.join(functions_only(uninstall, name) for name in
                            ["uninstall_keep_recovery", "uninstall_restore", "uninstall_pack_backup"])
        # Only backup destinations are rewritten; all extracted functions end at
        # their own closing brace, so no process scan, device report or entry runs.
        fixture = fixture.replace("/sdcard/Download", str(sandbox / "Download"))
        header = f"set -eu\nMODDIR={shlex.quote(str(module))}\nDATA_DIR={shlex.quote(str(data))}\n_ts=fixture\n"
        header += f"export CALLS={shlex.quote(str(sandbox / 'calls'))}\n_restore_ok=\n_restore_failed=\n_backup_ok=\n_recovery_dir=\n"
        out = run(header + fixture + '''
uninstall_keep_recovery
uninstall_pack_backup
[ "$_backup_ok" = 1 ]
uninstall_restore charge power restore
[ "$_restore_failed" = charge ]
[ -f "$DATA_DIR/config/settings.json" ]
[ -f "$DATA_DIR/battery/state/dtbo_registry/record" ]
printf '%s\n' "$_recovery_dir"
''', sandbox)
        recovery = Path(out.strip())
        assert recovery.is_relative_to(data / "recovery")
        assert (recovery / "engine/lib/env.sh").is_file()
        assert (recovery / "battery/dtbo/identity.tsv").is_file()
        archive = next((sandbox / "Download").glob("rezhale_toolbox_uninstall_fixture.*"))
        if archive.suffix == ".zip":
            import zipfile
            with zipfile.ZipFile(archive) as z:
                assert "backup/thermal-config/original" in z.namelist()
        else:
            import tarfile
            with tarfile.open(archive) as t:
                assert "backup/thermal-config/original" in t.getnames()
        shutil.rmtree(module)
        run(f"export CALLS={shlex.quote(str(sandbox / 'retry'))}\nsh {shlex.quote(str(recovery / 'restore.sh'))}", sandbox, expected=1)
        assert "power restore" in (sandbox / "retry").read_text()
        env_source = (ROOT / "engine/lib/env.sh").read_text()
        env_end = env_source.index('\n# 用户可变数据根')
        env_start = env_source.index('MODDIR=')
        env_fragment = env_source[env_start:env_end].replace(
            '/data/adb/modules/rezhale_toolbox', str(module))
        fallback = subprocess.run(['sh', '-c', env_fragment + '\nprintf "%s" "$MODDIR"',
                                   str(recovery / 'engine/xjctl.sh')], capture_output=True,
                                  text=True, timeout=5, check=True)
        assert fallback.stdout == str(recovery)
        assert (data / "battery/state/dtbo_registry/record").read_text() == "trusted"
        passed += 1
        run(header + fixture + '''
zip() { return 1; }
tar() { return 1; }
uninstall_pack_backup
[ -z "$_backup_ok" ]
[ -f "$DATA_DIR/config/settings.json" ]
[ -f "$DATA_DIR/backup/thermal-config/original" ]
''', sandbox)
        passed += 1
    print(f"生命周期隔离回归：{passed} 组通过；未执行 daemon 入口或访问设备节点")


if __name__ == "__main__":
    main()
