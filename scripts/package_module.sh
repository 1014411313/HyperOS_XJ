#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-3.1.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/dist/HyperOS_XJ_New"
ARCHIVE="$ROOT/dist/热炸了工具箱_v${VERSION}.zip"

python3 "$ROOT/scripts/doctor.py" --profile build
cd "$ROOT/ToolboxApp"
./gradlew :androidApp:assembleDebug --offline --console=plain
python3 "$ROOT/scripts/doctor.py" --profile build

mkdir -p "$MODULE/app"
cp "$ROOT/ToolboxApp/androidApp/build/outputs/apk/debug/androidApp-debug.apk" \
   "$MODULE/app/rezhale_toolbox.apk"

# 根 engine/ 是唯一编辑源；打包前同步到模块树，避免两份漂移。
rm -rf "$MODULE/engine"
cp -a "$ROOT/engine" "$MODULE/engine"

find "$MODULE" -type f -name '*.sh' -exec sh -c 'for file do sh -n "$file" || exit; done' sh {} +
python3 - "$MODULE" "$VERSION" <<'PY'
import stat, sys
from pathlib import Path
root, version = Path(sys.argv[1]), sys.argv[2]
props = dict(line.split('=', 1) for line in (root / 'module.prop').read_text(encoding='utf-8').splitlines() if '=' in line)
assert props.get('version') == version, f"module.prop 版本不符: {props.get('version')}"
apks = list(root.rglob('*.apk'))
assert len(apks) == 1 and apks[0].relative_to(root).as_posix() == 'app/rezhale_toolbox.apk', 'APK 数量或路径不符'
update_binary = root / 'META-INF/com/google/android/update-binary'
assert update_binary.is_file(), '缺少 update-binary'
assert stat.S_IMODE(update_binary.stat().st_mode) == 0o755, 'update-binary 权限不是 0755'
PY

if command -v zip >/dev/null 2>&1; then
    rm -f "$ARCHIVE"
    (cd "$MODULE" && zip -qr "$ARCHIVE" .)
    python3 - "$MODULE" "$ARCHIVE" <<'PY'
import stat, sys, zipfile
from pathlib import Path, PurePosixPath
root, archive = Path(sys.argv[1]), Path(sys.argv[2])
expected = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
with zipfile.ZipFile(archive) as z:
    names = [i.filename for i in z.infolist()]
    assert len(names) == len(set(names)), 'zip 有重名条目'
    assert z.testzip() is None, 'zip CRC 校验失败'
    assert {n for n in names if not n.endswith('/')} == expected, 'zip 内容与模块树不一致'
    for item in z.infolist():
        parts = PurePosixPath(item.filename).parts
        assert not item.filename.startswith('/') and '..' not in parts, item.filename
    assert [n for n in names if n.lower().endswith('.apk')] == ['app/rezhale_toolbox.apk'], 'zip 内 APK 数量不为 1'
    assert stat.S_IMODE(z.getinfo('META-INF/com/google/android/update-binary').external_attr >> 16) == 0o755, 'zip 内 update-binary 权限不是 0755'
PY
    echo "OK: dist/热炸了工具箱_v${VERSION}.zip"
else
    echo "zip not found; use scripts/package_module.ps1 on Windows or install zip" >&2
    exit 1
fi
