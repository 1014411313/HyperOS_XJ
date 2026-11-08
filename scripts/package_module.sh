#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-3.1.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT/ToolboxApp"
./gradlew :androidApp:assembleDebug --offline --console=plain

mkdir -p "$ROOT/dist/HyperOS_XJ_New/app"
cp "$ROOT/ToolboxApp/androidApp/build/outputs/apk/debug/androidApp-debug.apk" \
   "$ROOT/dist/HyperOS_XJ_New/app/HyperOS_XJ.apk"

# 根 engine/ 是唯一编辑源；打包前同步到模块树，避免两份漂移。
rm -rf "$ROOT/dist/HyperOS_XJ_New/engine"
cp -a "$ROOT/engine" "$ROOT/dist/HyperOS_XJ_New/engine"

cd "$ROOT/dist/HyperOS_XJ_New"
find . -name '*.sh' -type f -exec sh -n {} \;

if command -v zip >/dev/null 2>&1; then
    rm -f "../HyperOS_XJ_新模块_v${VERSION}.zip"
    zip -r "../HyperOS_XJ_新模块_v${VERSION}.zip" .
    echo "OK: dist/HyperOS_XJ_新模块_v${VERSION}.zip"
else
    echo "zip not found; use scripts/package_module.ps1 on Windows or install zip" >&2
    exit 1
fi
