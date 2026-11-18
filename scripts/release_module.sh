#!/usr/bin/env bash
# 打包并发布 HyperOS_XJ：构建 APK → 生成模块 zip → 更新清单 → 提交打标 → 推送 Gitee/GitHub → 建 release 传资产 → 输出可复制的地址。
#
#   scripts/release_module.sh 3.1.67 --notes "变更说明"      # 完整发布
#   scripts/release_module.sh --local                        # 只打包当前版本，不提交、不推送
#   scripts/release_module.sh 3.1.67 --notes "..." --dry-run # 只打印将要执行的动作
#
# 依赖：git、zip、python3、curl、JDK/SDK（gradlew --offline）。凭据取自 git 凭据助手，脚本不保存也不回显任何密钥。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GITEE_REPO="${GITEE_REPO:-xiaomirezhale/HyperOS_XJ}"
GITHUB_REPO="${GITHUB_REPO:-1014411313/HyperOS_XJ}"
GITEE_URL="https://gitee.com/${GITEE_REPO}.git"
GITHUB_URL="https://github.com/${GITHUB_REPO}.git"
AICODE_DIR="${AICODE_DIR:-$HOME/.aicode}"
MODULE_DIR="$ROOT/dist/HyperOS_XJ_New"
PROP="$MODULE_DIR/module.prop"

usage() {
    sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
}

VERSION=""; NOTES=""; CODE=""; DRY_RUN=0; LOCAL=0
while [ $# -gt 0 ]; do
    case "$1" in
        -n|--dry-run) DRY_RUN=1; shift ;;
        --local)      LOCAL=1; shift ;;
        --notes)      NOTES="${2:-}"; shift 2 ;;
        --code)       CODE="${2:-}"; shift 2 ;;
        -h|--help)    usage; exit 0 ;;
        -*)           echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
        *)            [ -z "$VERSION" ] || { echo "只接受一个版本号" >&2; exit 2; }; VERSION="$1"; shift ;;
    esac
done

prop_get() { grep -m1 "^$1=" "$PROP" | cut -d= -f2-; }
CUR_VER="$(prop_get version)"
CUR_CODE="$(prop_get versionCode)"

if [ "$LOCAL" = 1 ] && [ -z "$VERSION" ]; then
    VERSION="$CUR_VER"
fi
[ -n "$VERSION" ] || { echo "缺少版本号（如 3.1.67）" >&2; usage >&2; exit 2; }
case "$VERSION" in
    [0-9]*.[0-9]*.[0-9]*) ;;
    *) echo "版本号格式应为 X.Y.Z，收到: $VERSION" >&2; exit 2 ;;
esac
[ -n "$CODE" ] || { if [ "$LOCAL" = 1 ]; then CODE="$CUR_CODE"; else CODE=$((CUR_CODE + 1)); fi; }
case "$CODE" in ''|*[!0-9]*) echo "versionCode 必须是整数: $CODE" >&2; exit 2 ;; esac
if [ "$LOCAL" = 1 ]; then
    [ "$CODE" -ge "$CUR_CODE" ] || { echo "versionCode $CODE 小于当前 $CUR_CODE" >&2; exit 2; }
else
    [ "$CODE" -gt "$CUR_CODE" ] || { echo "versionCode $CODE 必须大于当前 $CUR_CODE" >&2; exit 2; }
fi

TAG="v$VERSION"
ASSET="rezhale_toolbox_v$VERSION.zip"
DATE="$(date +%F)"
[ -n "$NOTES" ] || NOTES="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("notes",""))' "$ROOT/latest.json")"

step() { printf '\n=== %s ===\n' "$*"; }

if [ "$DRY_RUN" = 1 ]; then
    step '干跑：将执行以下动作'
    echo "模块版本      : $CUR_VER($CUR_CODE) → $VERSION($CODE)"
    echo "产物          : dist/$ASSET"
    echo "提交与标签    : 更新 latest.json / state/release.json，commit，tag $TAG"
    echo "推送          : $GITEE_URL 与 $GITHUB_URL 的 master 与 $TAG"
    echo "发布          : 两个仓库各建 release 并附加 $ASSET"
    echo "说明          : $NOTES"
    [ "$LOCAL" = 1 ] && echo "（--local 下只做到生成 zip）"
    exit 0
fi

if [ "$LOCAL" != 1 ]; then
git -C "$ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null && {
        echo "标签 $TAG 已存在" >&2; exit 2; }
    git -C "$ROOT" config user.name >/dev/null 2>&1 && git -C "$ROOT" config user.email >/dev/null 2>&1 || {
        echo "未配置 git 身份，先执行：" >&2
        echo "  git -C '$ROOT' config --local user.name '<名字>'" >&2
        echo "  git -C '$ROOT' config --local user.email '<邮箱>'" >&2
        exit 2; }
fi

step "1/7 构建 App ($VERSION/$CODE)"
if [ "$LOCAL" != 1 ]; then
    python3 - "$PROP" "$VERSION" "$CODE" <<'PY'
import re, sys
path, ver, code = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding='utf-8').read()
text = re.sub(r'^version=.*$', f'version={ver}', text, count=1, flags=re.M)
text = re.sub(r'^versionCode=.*$', f'versionCode={code}', text, count=1, flags=re.M)
open(path, 'w', encoding='utf-8').write(text)
PY
fi
( cd "$ROOT/ToolboxApp" && ./gradlew :androidApp:assembleDebug --offline --console=plain )

step "2/7 同步模块树"
mkdir -p "$MODULE_DIR/app"
cp "$ROOT/ToolboxApp/androidApp/build/outputs/apk/debug/androidApp-debug.apk" "$MODULE_DIR/app/rezhale_toolbox.apk"
rm -rf "$MODULE_DIR/engine"
cp -a "$ROOT/engine" "$MODULE_DIR/engine"
find "$MODULE_DIR" -type f -name '*.sh' -print | while IFS= read -r f; do sh -n "$f" || exit 1; done

step "3/7 生成 dist/$ASSET"
rm -f "$ROOT/dist/$ASSET"
( cd "$MODULE_DIR" && zip -qr "$ROOT/dist/$ASSET" . )
python3 - "$MODULE_DIR" "$ROOT/dist/$ASSET" "$VERSION" "$CODE" <<'PY'
import hashlib, stat, sys, zipfile
from pathlib import Path, PurePosixPath
tree, archive, ver, code = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4]
expected = {p.relative_to(tree).as_posix() for p in tree.rglob('*') if p.is_file()}
with zipfile.ZipFile(archive) as z:
    names = [i.filename for i in z.infolist()]
    assert len(names) == len(set(names)), 'zip 有重名条目'
    assert z.testzip() is None, 'zip CRC 校验失败'
    assert {n for n in names if not n.endswith('/')} == expected, 'zip 内容与模块树不一致'
    for i in z.infolist():
        parts = PurePosixPath(i.filename).parts
        assert not i.filename.startswith('/') and '..' not in parts, i.filename
        assert not stat.S_ISLNK(i.external_attr >> 16), f'符号链接 {i.filename}'
    assert [n for n in names if n.lower().endswith('.apk')] == ['app/rezhale_toolbox.apk'], 'APK 数量不为 1'
    props = dict(l.split('=', 1) for l in z.read('module.prop').decode().splitlines() if '=' in l)
    assert (props['version'], props['versionCode']) == (ver, code), f"包内版本 {props['version']}/{props['versionCode']}"
    assert stat.S_IMODE(z.getinfo('META-INF/com/google/android/update-binary').external_attr >> 16) == 0o755
    apk = z.read('app/rezhale_toolbox.apk')
    assert hashlib.sha256(apk).digest() == hashlib.sha256((tree / 'app/rezhale_toolbox.apk').read_bytes()).digest()
print(f'包校验通过：{len(expected)} 个文件，APK sha256 {hashlib.sha256(apk).hexdigest()[:12]}')
PY
if [ "$LOCAL" = 1 ]; then
    step "完成（--local）"
    echo "产物: $ROOT/dist/$ASSET"
    exit 0
fi

step "4/7 更新 latest.json 与 state/release.json"
python3 - "$ROOT/latest.json" "$VERSION" "$CODE" "$DATE" "$NOTES" "$GITEE_REPO" "$GITHUB_REPO" "$TAG" "$ASSET" <<'PY'
import json, sys
path, ver, code, date, notes, grepo, hrepo, tag, asset = sys.argv[1:]
d = json.load(open(path, encoding='utf-8'))
d.update({
    'version': ver, 'versionCode': int(code), 'date': date, 'notes': notes,
    'download': f'https://github.com/{hrepo}/releases/download/{tag}/{asset}',
    'download_gitee': f'https://gitee.com/{grepo}/releases/download/{tag}/{asset}',
    'release': f'https://github.com/{hrepo}/releases/tag/{tag}',
    'release_gitee': f'https://gitee.com/{grepo}/releases/tag/{tag}',
})
d.setdefault('url', '')
open(path, 'w', encoding='utf-8').write(json.dumps(d, ensure_ascii=False, indent=2) + '\n')
PY
python3 - "$ROOT/state/release.json" "$VERSION" "$CODE" "$DATE" "$NOTES" <<'PY'
import json, sys
path, ver, code, date, notes = sys.argv[1:]
d = json.load(open(path, encoding='utf-8'))
changes = [{'id': f'{ver}-{i}', 'title': l.lstrip('- *').strip(), 'status': '已发布'}
           for i, l in enumerate(notes.splitlines(), 1) if l.strip()]
# 需要精确 bug id 时，事后手动改写该条目的 id
d['history'].insert(0, {'version': f'{ver}（{code}，已发布）', 'date': date, 'changes': changes})
open(path, 'w', encoding='utf-8').write(json.dumps(d, ensure_ascii=False, indent=2) + '\n')
PY
git -C "$ROOT" add -A latest.json state/release.json dist/HyperOS_XJ_New engine

step "5/7 提交并打标签 $TAG"
if git -C "$ROOT" diff --cached --quiet; then
    echo "没有需要提交的改动，沿用当前提交打标签"
else
    git -C "$ROOT" commit -q -m "release: $TAG" -m "$NOTES"
fi
git -C "$ROOT" tag -a "$TAG" -m "$TAG"
git -C "$ROOT" log --oneline -1

step "6/7 推送 master 与 $TAG"
git -C "$ROOT" push "$GITEE_URL" master
git -C "$ROOT" push "$GITEE_URL" "$TAG"
git -C "$ROOT" push "$GITHUB_URL" master
git -C "$ROOT" push "$GITHUB_URL" "$TAG"

step "7/7 创建 release 并上传 $ASSET"
cred_for() {
    printf 'protocol=https\nhost=%s\n\n' "$1" \
        | "$AICODE_DIR/git-credential-aicode" get 2>/dev/null \
        | sed -n 's/^password=//p'
}
ZIP="$ROOT/dist/$ASSET"
python3 -c 'import json,sys;json.dump({"tag_name":sys.argv[1],"name":sys.argv[1],"body":sys.argv[2],"target_commitish":"master","prerelease":False},open("/tmp/rel-body.json","w"),ensure_ascii=False)' "$TAG" "$NOTES"

GT="$(cred_for gitee.com)"
[ -n "$GT" ] || { echo "取不到 Gitee 凭据，无法创建 release" >&2; exit 1; }
curl -sS -o /tmp/rel-gitee.json -w 'gitee release http=%{http_code}\n' -X POST \
    -H "Authorization: token $GT" -H 'Content-Type: application/json' \
    --data-binary @/tmp/rel-body.json \
    "https://gitee.com/api/v5/repos/$GITEE_REPO/releases"
GID="$(python3 -c 'import json;print(json.load(open("/tmp/rel-gitee.json")).get("id",""))')"
[ -n "$GID" ] || { echo "Gitee release 创建失败，见 /tmp/rel-gitee.json" >&2; exit 1; }
curl -sS -o /tmp/rel-gitee-asset.json -w 'gitee asset   http=%{http_code}\n' -X POST \
    -H "Authorization: token $GT" -F "file=@$ZIP" \
    "https://gitee.com/api/v5/repos/$GITEE_REPO/releases/$GID/attach_files"

GH="$(cred_for github.com)"
if [ -n "$GH" ]; then
    curl -sS -o /tmp/rel-github.json -w 'github release http=%{http_code}\n' -X POST \
        -H "Authorization: Bearer $GH" -H 'Accept: application/vnd.github+json' \
        -H 'Content-Type: application/json' --data-binary @/tmp/rel-body.json \
        "https://api.github.com/repos/$GITHUB_REPO/releases"
    HGID="$(python3 -c 'import json;print(json.load(open("/tmp/rel-github.json")).get("id",""))')"
    if [ -n "$HGID" ]; then
        curl -sS -o /tmp/rel-github-asset.json -w 'github asset   http=%{http_code}\n' -X POST \
            -H "Authorization: Bearer $GH" -H 'Accept: application/vnd.github+json' \
            -H 'Content-Type: application/zip' --data-binary "@$ZIP" \
            "https://uploads.github.com/repos/$GITHUB_REPO/releases/$HGID/assets?name=$ASSET"
    else
        echo "GitHub release 创建失败，见 /tmp/rel-github.json（Gitee 已发布）" >&2
    fi
else
    echo "未取到 GitHub 凭据，跳过 GitHub 发布" >&2
fi

cat <<EOF

========== 可复制地址 ==========
发行页（含历史安装包）
  Gitee : https://gitee.com/$GITEE_REPO/releases
  GitHub: https://github.com/$GITHUB_REPO/releases

$TAG 发行页
  Gitee : https://gitee.com/$GITEE_REPO/releases/tag/$TAG
  GitHub: https://github.com/$GITHUB_REPO/releases/tag/$TAG

$TAG 安装包直链
  Gitee : https://gitee.com/$GITEE_REPO/releases/download/$TAG/$ASSET
  GitHub: https://github.com/$GITHUB_REPO/releases/download/$TAG/$ASSET

模块内 App（可直接点击安装）
  $MODULE_DIR/app/rezhale_toolbox.apk
EOF
