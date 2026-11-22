#!/usr/bin/env python3
"""BAA 精确机型目标隔离回归；系统路径映射到临时目录，挂载与服务命令使用桩。"""

import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIBRARIES = (
    ROOT / "engine/lib/battery.sh",
    ROOT / "dist/HyperOS_XJ_New/engine/lib/battery.sh",
)
CHARGER_DIRS = (
    "/odm/etc/charger", "/vendor/etc/charger", "/my_product/etc/charger",
    "/system/etc/charger", "/system_ext/etc/charger", "/product/etc/charger",
)
PAYLOAD = 'BAA_ENC:0123456789abcdef0123456789abcdef'

PRELUDE = r'''
MODDIR="$SANDBOX/module"
BT_STATE="$SANDBOX/state"
LOGDIR="$SANDBOX/log"
XJ_READONLY=1
cfg_get() {
  case "$1" in
    dev_code) printf '%s' "$TEST_DEV" ;;
    baa_fmt) printf enc ;;
    *) printf '%s' "${2:-}" ;;
  esac
}
getprop() {
  case "$1" in
    ro.product.device) printf '%s' "${TEST_DEVICE:-$TEST_DEV}" ;;
    ro.product.vendor.device) printf '%s' "${TEST_VENDOR:-}" ;;
    ro.product.odm.device|ro.product.name) printf '%s' "${TEST_OTHER:-}" ;;
    *) printf unsupported ;;
  esac
}
cfg_set() { echo "cfg_set $*" >> "$CALL_LOG"; }
setprop() { echo "setprop $*" >> "$CALL_LOG"; }
mount() {
  echo "mount $*" >> "$CALL_LOG"
  command cp "$3" "$4"
}
umount() { echo "umount $*" >> "$CALL_LOG"; }
chcon() { echo "chcon $*" >> "$CALL_LOG"; }
stat() {
  if [ "$2" = '%C' ]; then printf '%s' "$BAA_LABEL"; else command stat "$@"; fi
}
grep() {
  case "$*" in
    */proc/mounts) return 0 ;;
    *) command grep "$@" ;;
  esac
}
. "$TEST_LIBRARY"
pss_disable() { echo pss_disable >> "$CALL_LOG"; }
pss_enable() { echo pss_enable >> "$CALL_LOG"; }
'''


def run_case(library, name, files, dev="haotian", missing=False, device=None, other=""):
    with tempfile.TemporaryDirectory(prefix="baa-target-") as raw:
        sandbox = Path(raw)
        mapped = library.read_text()
        for directory in CHARGER_DIRS:
            mapped = mapped.replace(directory, str(sandbox) + directory)
        test_library = sandbox / "battery.sh"
        test_library.write_text(mapped)
        for directory in ("module/battery/baa/modes", "state", "log"):
            (sandbox / directory).mkdir(parents=True)
        source = sandbox / f"module/battery/baa/payload_BAA_config_{dev}.json"
        source.write_text(PAYLOAD)
        mode = sandbox / "module/battery/baa/modes/normal_nocloud.json"
        mode.write_text(PAYLOAD + "-new")
        originals = {}
        for relative in files:
            path = sandbox / relative.lstrip("/")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(PAYLOAD if missing else "stock-original")
            originals[path] = path.read_bytes()
        call_log = sandbox / "calls.log"
        call_log.touch()
        env = dict(os.environ, SANDBOX=str(sandbox), TEST_LIBRARY=str(test_library),
                   TEST_DEV=dev, TEST_DEVICE=device or dev, TEST_OTHER=other, CALL_LOG=str(call_log))

        def shell(code):
            result = subprocess.run(["sh", "-c", PRELUDE + code], env=env,
                                    text=True, capture_output=True)
            assert result.returncode == 0, (name, result.stdout, result.stderr)
            return result.stdout.strip()

        target, code = shell('p=$(baa_tgt); rc=$?; printf "%s\\n%s\\n" "$p" "$rc"').splitlines()
        if not missing:
            expected = next(path for path in files if path.endswith(f"BAA_config_{dev}.json"))
            assert (target, code) == (str(sandbox) + expected, "0"), (name, target, code)
            assert shell('baa_mount; echo $?') == "0", name
            assert shell('baa_state') == "active", name
            assert Path(target).read_text() == mode.read_text(), name
            calls = call_log.read_text().splitlines()
            assert f"umount {target}" in calls, (name, calls)
            assert f"mount -o bind {source} {target}" in calls, (name, calls)
            assert shell('baa_umount; echo $?') == "0", name
            for path, original in originals.items():
                if str(path) != target:
                    assert path.read_bytes() == original, (name, path)
        else:
            assert code == "1", (name, target, code)
            expected_dev = dev if (device or dev) == dev else "unknown"
            assert target == str(sandbox) + f"/odm/etc/charger/BAA_config_{expected_dev}.json", name
            out = shell('s=$(baa_state); rc=$?; printf "%s:%s" "$s" "$rc"')
            assert out == "inactive:2", (name, out)
            for action in ("baa_refresh_src", "baa_mount", "baa_umount",
                           "battery_unlock_on", "battery_boot", "bgu_baa_tick 1 0"):
                call_log.write_text("")
                out = shell('BGU_CFG_UNLOCK=1\n' + action + '\nprintf "%s" "$?"')
                assert out == "2", (name, action, out)
                calls = call_log.read_text().splitlines()
                assert not any(line.startswith(("mount ", "umount ", "chcon ",
                                                "setprop ")) for line in calls), (name, action, calls)
                assert source.read_text() == PAYLOAD, (name, action)
                for path, original in originals.items():
                    assert path.read_bytes() == original, (name, action, path)
            call_log.write_text("")
            assert shell('battery_unlock_off; echo $?') == "2", name
            assert call_log.read_text().splitlines() == ["cfg_set unlock 0", "pss_enable"], name
            assert "BAA target missing:" in (sandbox / "log/battery.log").read_text(), name
        print(f"ok {library.relative_to(ROOT)} {name}")


def main():
    for library in LIBRARIES:
        run_case(library, "exact-target", [
            "/odm/etc/charger/BAA_config_dada.json",
            "/odm/etc/charger/BAA_config_haotian.json",
        ])
        for directory in CHARGER_DIRS[1:]:
            run_case(library, f"cross-directory-{directory.split('/')[1]}", [
                "/odm/etc/charger/BAA_config_dada.json",
                directory + "/BAA_config_haotian.json",
            ])
        run_case(library, "other-model-only", ["/odm/etc/charger/BAA_config_dada.json"],
                 missing=True)
        run_case(library, "no-target", [], missing=True)
        run_case(library, "unknown-device", [
            "/odm/etc/charger/BAA_config_unknown.json",
            "/vendor/etc/charger/BAA_config_haotian.json",
        ], dev="unknown", missing=True)
        run_case(library, "configured-device-conflicts-with-property", [
            "/odm/etc/charger/BAA_config_haotian.json",
            "/odm/etc/charger/BAA_config_dada.json",
        ], dev="haotian", device="dada", missing=True)
        run_case(library, "supported-and-unsupported-property-conflict", [
            "/odm/etc/charger/BAA_config_haotian.json",
        ], dev="", device="haotian", other="unsupported-model", missing=True)
    print("BAA target regression: PASS (20 cases)")


if __name__ == "__main__":
    main()
