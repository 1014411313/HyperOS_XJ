#!/bin/sh
# DTBO engine regression with a strict sandbox.
# Isolation contract:
#   1. the whole run happens inside one `mktemp -d` directory created with mode 700;
#   2. every command that can write files is wrapped and refuses any target outside it;
#   3. only the reference identity.tsv and the v3.12.9 archive are readable outside;
#   4. dd/curl/getprop/blockdev/truncate are never executed for real: dd is emulated
#      with python on ordinary files inside the sandbox and sync is a no-op.
set -f

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
IDENTITY_SRC=$ROOT/dist/HyperOS_XJ_New/battery/dtbo/identity.tsv
ARCHIVE_SRC=$ROOT/references/modules/battery_tune/history/电池解容工具箱_v3.12.9.zip

TMP=$(mktemp -d "${TMPDIR:-/tmp}/xj-dtbo-regression.XXXXXXXX") || { echo "无法创建私有临时目录"; exit 1; }
chmod 700 "$TMP" 2>/dev/null
trap 'command rm -rf -- "$TMP"' EXIT

# Read-only integrity baseline; the engine and manifest must be unchanged at exit.
PROD_SHA_BEFORE=$(command sha256sum "$ROOT/engine/lib/dtbo.sh" "$IDENTITY_SRC" 2>/dev/null | awk '{print $1}' | tr '\n' ' ')

PASS=0
FAIL=0
CURRENT=""

ok() { PASS=$((PASS + 1)); printf 'PASS %s\n' "$CURRENT"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL %s: %s\n' "$CURRENT" "$1"; }
run() {
  CURRENT="$1"
  shift
  if "$@" >"$TMP/out" 2>&1; then
    if grep -q '^DENY ' "$TMP/out"; then bad "isolation guard denied: $(tr '\n' ' ' <"$TMP/out")"; else ok; fi
  else
    bad "rc=$? output=$(tr '\n' ' ' <"$TMP/out")"
  fi
}
expect_fail() {
  CURRENT="$1"
  shift
  if "$@" >"$TMP/out" 2>&1; then
    bad "unexpected success output=$(tr '\n' ' ' <"$TMP/out")"
  else
    _rc=$?
    if [ "$_rc" = 99 ] || grep -q '^DENY ' "$TMP/out"; then
      bad "isolation guard denied: $(tr '\n' ' ' <"$TMP/out")"
    else
      ok
    fi
  fi
}
need_tmp() {
  in_tmp "$1" && return 0
  bad "path escaped the private temp directory: $1"
  printf 'DTBO regression: %s passed, %s failed\n' "$PASS" "$FAIL"
  exit 1
}
contains() { grep -F "$1" "$TMP/out" >/dev/null 2>&1; }

# --- sandbox boundary -------------------------------------------------------
DENY_LOG="$TMP/.deny.log"
: >"$DENY_LOG"
in_tmp() {
  case "$1" in
    "$TMP"|"$TMP"/*) ;;
    *) return 1 ;;
  esac
  case "/$1/" in
    */../*) return 1 ;;
  esac
  return 0
}
deny() {
  printf 'DENY %s %s\n' "$1" "$2" >>"$DENY_LOG" 2>/dev/null
  printf 'DENY %s %s\n' "$1" "$2" >&2
  return 99
}
guard_read() {
  [ -n "${1:-}" ] || return 0
  in_tmp "$1" && return 0
  case "$1" in
    "$IDENTITY_SRC"|"$ARCHIVE_SRC") return 0 ;;
  esac
  deny read "$1"
}
guard_write() {
  [ -n "${1:-}" ] || return 0
  in_tmp "$1" || deny write "$1"
}

cp() {
  __gr_last=""
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    __gr_last=$__gr_a
  done
  [ -n "$__gr_last" ] || { echo "cp: 缺少目标" >&2; return 2; }
  guard_write "$__gr_last" || return 99
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    [ "$__gr_a" = "$__gr_last" ] && continue
    guard_read "$__gr_a" || return 99
  done
  command cp "$@"
}
mv() {
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    guard_write "$__gr_a" || return 99
  done
  command mv "$@"
}
rm() {
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    guard_write "$__gr_a" || return 99
  done
  command rm "$@"
}
rmdir() {
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    guard_write "$__gr_a" || return 99
  done
  command rmdir "$@"
}
mkdir() {
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    guard_write "$__gr_a" || return 99
  done
  command mkdir "$@"
}
touch() {
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    guard_write "$__gr_a" || return 99
  done
  command touch "$@"
}
chmod() {
  __gr_mode=0
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    if [ "$__gr_mode" = 0 ]; then __gr_mode=1; continue; fi
    guard_write "$__gr_a" || return 99
  done
  command chmod "$@"
}
stat() {
  __gr_fmt=0
  for __gr_a in "$@"; do
    if [ "$__gr_fmt" = 1 ]; then __gr_fmt=0; continue; fi
    case "$__gr_a" in
      -c|--format) __gr_fmt=1; continue ;;
      -*) continue ;;
    esac
    guard_read "$__gr_a" || return 99
  done
  command stat "$@"
}
sha256sum() {
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    guard_read "$__gr_a" || return 99
  done
  command sha256sum "$@"
}
cat() {
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    guard_read "$__gr_a" || return 99
  done
  command cat "$@"
}
sed() {
  case " $* " in
    *" -i "*|*" -i"*) deny sed-inplace "$*"; return 99 ;;
  esac
  for __gr_a in "$@"; do
    case "$__gr_a" in -*) continue ;; esac
    if [ -f "$__gr_a" ]; then guard_read "$__gr_a" || return 99; fi
  done
  command sed "$@"
}
ln() { deny ln "$*"; }
tee() { deny tee "$*"; }
find() {
  for __fd_a in "$@"; do
    case "$__fd_a" in
      -delete|-exec|-execdir|-ok|-okdir|-fprint|-fprintf|-fls) deny find "$*"; return 99 ;;
      /*) guard_read "$__fd_a" || return 99 ;;
    esac
  done
  command find "$@"
}
install() { deny install "$*"; }
truncate() { deny truncate "$*"; }
dd() { deny dd "$*"; }
curl() { deny curl "$*"; }
getprop() { deny getprop "$*"; }
blockdev() { deny blockdev "$*"; }
# dd/curl/getprop/blockdev/truncate are never executed for real. od only reads
# /dev/urandom to keep token nonces distinct; it cannot write.
sync() { :; }

# python3 is the only helper that performs real file I/O in this sandbox.
python3() {
  for __py_a in "$@"; do
    case "$__py_a" in
      /*) guard_read "$__py_a" || return 99 ;;
    esac
  done
  command python3 "$@"
}

# --- fixtures ---------------------------------------------------------------
MODEL=annibale
MODDIR="$TMP/module"
BT_STATE="$TMP/state"
STATE="$TMP/state"
DTBO_DIR="$MODDIR/battery/dtbo"
DTBO_IDENTITY_FILE="$DTBO_DIR/identity.tsv"
DTBO_BACKUP_PRIMARY="$TMP/primary"
DTBO_BACKUP_FALLBACK="$TMP/fallback"
DTBO_RUNTIME_DIR="$TMP/runtime"
DTBO_LOCK_DIR="$TMP/lock"
DTBO_EDIRS_FILE="$STATE/extra_dirs.txt"
mkdir -p "$DTBO_DIR" "$STATE" "$DTBO_RUNTIME_DIR"
cp "$IDENTITY_SRC" "$DTBO_IDENTITY_FILE"

IMG="$DTBO_DIR/annibale.img"
guard_write "$IMG"
printf 'NEW-DTBO-IMAGE-1234567890' >"$IMG"
IMAGE_SIZE=$(stat -c %s "$IMG")
IMAGE_SHA=$(sha256sum "$IMG" | awk '{print $1}')

# Replace only the current model's identity row with the deterministic fixture.
guard_write "$DTBO_IDENTITY_FILE"
python3 - "$DTBO_IDENTITY_FILE" "$MODEL" "$IMAGE_SIZE" "$IMAGE_SHA" <<'PY'
import sys
path, model, size, sha = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
lines = open(path, encoding='utf-8').read().splitlines()
out = []
for line in lines:
    if line.startswith(model + '|'):
        fields = line.split('|')
        fields[2] = size
        fields[3] = sha
        line = '|'.join(fields)
    out.append(line)
open(path, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
PY

DEV="$TMP/dtbo_a"
guard_write "$DEV"
printf 'ORIGINAL-DTBO-CONTENT-1234567890' >"$DEV"
DEV_SIZE=$(stat -c %s "$DEV")

# --- production engine, then simulation boundary ----------------------------
# shellcheck disable=SC1090
. "$ROOT/engine/lib/dtbo.sh"

TEST_SUFFIX=_a
TEST_SLOT=a
TEST_OS=14
TEST_NOW=1700000000
TEST_DEV_CODE=""
TEST_SIZE_UNKNOWN=0
TEST_PROP_DEVICE=annibale
TEST_PROP_ODM_DEVICE=""
TEST_PROP_NAME=""
TEST_PROP_MARKETNAME=""
TEST_DTBO_BASE=mock://dtbo

dtbo_need_root() { return 0; }
dtbo_getprop() {
  case "$1" in
    ro.boot.slot_suffix) printf '%s' "${TEST_SUFFIX-}" ;;
    ro.boot.slot) printf '%s' "${TEST_SLOT-}" ;;
    ro.build.version.release) printf '%s' "${TEST_OS:-14}" ;;
    ro.product.device) printf '%s' "${TEST_PROP_DEVICE:-}" ;;
    ro.product.odm.device) printf '%s' "${TEST_PROP_ODM_DEVICE:-}" ;;
    ro.product.name) printf '%s' "${TEST_PROP_NAME:-}" ;;
    ro.product.marketname) printf '%s' "${TEST_PROP_MARKETNAME:-}" ;;
    *) return 1 ;;
  esac
}
cfg_get() {
  case "$1" in
    dev_code) printf '%s' "${TEST_DEV_CODE:-}" ;;
    dtbo_base) printf '%s' "${TEST_DTBO_BASE:-mock://dtbo}" ;;
    *) printf '%s' "${2:-}" ;;
  esac
}
dtbo_dev() { printf '%s' "$DEV"; }
dtbo_size() {
  [ "${TEST_SIZE_UNKNOWN:-0}" = 1 ] && return 1
  printf '%s' "$DEV_SIZE"
}
dtbo_now() { printf '%s' "$TEST_NOW"; }

FAIL_DD=0
FAIL_PAD=0
CORRUPT_READBACK=0
SHORT_READ=0
DD_WRITE_COUNT=0
CORRUPT_MARKER="$TMP/corrupt.once"

# dd is emulated on ordinary files; any device/file target outside the sandbox
# is refused before a single byte is written.
dtbo_dd() {
  __dd_if=""; __dd_of=""
  for __dd_a in "$@"; do
    case "$__dd_a" in
      if=*) __dd_if=${__dd_a#if=} ;;
      of=*) __dd_of=${__dd_a#of=} ;;
    esac
  done
  [ -n "$__dd_if" ] && [ -n "$__dd_of" ] || { echo "dtbo_dd: 仅支持 if=/of=" >&2; return 99; }
  guard_read "$__dd_if" || return 99
  guard_write "$__dd_of" || return 99
  case "$__dd_if" in
    "$DTBO_RUNTIME_DIR"/write.*) [ "${FAIL_DD:-0}" = 1 ] && return 1 ;;
  esac
  [ "$__dd_of" = "$DEV" ] && DD_WRITE_COUNT=$((DD_WRITE_COUNT + 1))
  __dd_limit=0
  __dd_corrupt=0
  case "$__dd_of" in
    *readback*)
      if [ "${CORRUPT_READBACK:-0}" = 1 ] && [ ! -e "$CORRUPT_MARKER" ]; then
        __dd_corrupt=1
      fi
      ;;
    */current.*) [ "${SHORT_READ:-0}" = 1 ] && __dd_limit=16 ;;
  esac
  if [ "$__dd_corrupt" = 1 ]; then
    : >"$CORRUPT_MARKER"
    python3 - "$__dd_of" "$DEV_SIZE" <<'PY'
import sys
with open(sys.argv[1], 'wb') as f:
    f.write(b'\0' * int(sys.argv[2]))
PY
    return $?
  fi
  python3 - "$__dd_if" "$__dd_of" "$__dd_limit" <<'PY'
import sys
src, dst, limit = sys.argv[1], sys.argv[2], int(sys.argv[3])
with open(src, 'rb') as f:
    data = f.read()
if limit:
    data = data[:limit]
with open(dst, 'wb') as f:
    f.write(data)
PY
}
dtbo_pad_file() {
  guard_write "$1" || return 99
  [ "${FAIL_PAD:-0}" = 1 ] && return 1
  python3 - "$1" "$2" <<'PY'
import sys
with open(sys.argv[1], 'r+b') as f:
    f.truncate(int(sys.argv[2]))
PY
}
dtbo_curl() {
  __cu_out=""; __cu_url=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -o) __cu_out=$2; shift 2 ;;
      --connect-timeout|--max-time) shift 2 ;;
      -*) shift ;;
      *) __cu_url=$1; shift ;;
    esac
  done
  [ -n "$__cu_out" ] || { echo "dtbo_curl: 缺少 -o" >&2; return 99; }
  guard_write "$__cu_out" || return 99
  case "$__cu_url" in
    "$TEST_DTBO_BASE"/dtbo/index.json)
      printf '{"images":{"annibale":{"file":"annibale.img","size":%s,"sha256":"%s"}}}\n' "$IMAGE_SIZE" "$IMAGE_SHA" >"$__cu_out"
      ;;
    "$TEST_DTBO_BASE"/dtbo/*.img)
      python3 - "$IMG" "$__cu_out" <<'PY'
import sys
with open(sys.argv[1], 'rb') as f:
    data = f.read()
with open(sys.argv[2], 'wb') as f:
    f.write(data)
PY
      ;;
    *) return 1 ;;
  esac
}

check_production_identity() {
  python3 - "$IDENTITY_SRC" "$ARCHIVE_SRC" <<'PY'
import hashlib
import pathlib
import sys
import zipfile

# Only the reference manifest and the archived ZIP are read.
manifest = pathlib.Path(sys.argv[1])
archive = pathlib.Path(sys.argv[2])
errors = []
rows = {}

try:
    lines = manifest.read_text().splitlines()
except OSError as exc:
    print(f"identity manifest read failed: {exc}")
    raise SystemExit(1)

for number, line in enumerate(lines, 1):
    if not line or line.startswith("#"):
        continue
    fields = line.split("|")
    if len(fields) != 6:
        errors.append(f"manifest line {number}: expected 6 fields")
        continue
    code, filename, size, digest, _os_range, _provenance = fields
    if code in rows or filename in {row[1] for row in rows.values()}:
        errors.append(f"manifest line {number}: duplicate code or file")
    rows[code] = fields
    if not size.isdigit() or int(size) <= 0:
        errors.append(f"{code}: invalid size {size!r}")
    if len(digest) != 64 or any(ch not in "0123456789abcdefABCDEF" for ch in digest):
        errors.append(f"{code}: invalid SHA256")

try:
    with zipfile.ZipFile(archive) as zf:
        archive_names = sorted(
            pathlib.PurePosixPath(name).stem
            for name in zf.namelist()
            if name.startswith("dtbo/") and name.endswith(".img")
        )
        if len(rows) != 15 or archive_names != sorted(rows):
            errors.append(f"identity/archive model set differs: manifest={sorted(rows)}, archive={archive_names}")
        for code, fields in sorted(rows.items()):
            _, filename, size, digest, _, _ = fields
            try:
                archived_bytes = zf.read(f"dtbo/{filename}")
            except KeyError as exc:
                errors.append(f"{code}: missing archive image: {exc}")
                continue
            actual = hashlib.sha256(archived_bytes).hexdigest()
            if len(archived_bytes) != int(size) or actual != digest:
                errors.append(f"{code}: manifest mismatch archive size={len(archived_bytes)} sha={actual}")
except (OSError, zipfile.BadZipFile) as exc:
    errors.append(f"reference archive read failed: {exc}")

if errors:
    print("\\n".join(errors))
    raise SystemExit(1)
print("15 manifest entries match the v3.12.9 archive bytes")
PY
}

CURRENT="production identity manifest matches v3.12.9 archive"
if check_production_identity >"$TMP/identity-check.out" 2>&1; then
  ok
else
  bad "$(tr '\n' ' ' <"$TMP/identity-check.out")"
fi

# Slot, size, model, identity and source gates.
TEST_SUFFIX=""; TEST_SLOT=""; expect_fail "reject unknown slot" dtbo_preflight builtin
TEST_SUFFIX=_a; TEST_SLOT=b; expect_fail "reject contradictory slot" dtbo_preflight builtin
TEST_SUFFIX=_a; TEST_SLOT=""; expect_fail "reject missing slot property" dtbo_preflight builtin

# The production dtbo_model is kept intact; only getprop and cfg_get are stubbed.
TEST_SUFFIX=_a; TEST_SLOT=a; TEST_PROP_DEVICE=annibale; TEST_DEV_CODE=""
CURRENT="model resolved from real getprop props"
_model=$(dtbo_model)
if [ "$_model" = annibale ]; then ok; else bad "got=$_model"; fi
CURRENT="preflight resolves model via real getprop"
if dtbo_preflight builtin --ack-os-unknown >"$TMP/out" 2>&1 && grep -q '^model=annibale$' "$TMP/out"; then
  ok
else
  bad "output=$(tr '\n' ' ' <"$TMP/out")"
fi
TEST_DEV_CODE=warsaw
CURRENT="reject model when configured dev_code conflicts"
_model=$(dtbo_model)
if [ "$_model" = unknown ]; then ok; else bad "got=$_model"; fi
TEST_DEV_CODE=""
TEST_PROP_DEVICE=annibale; TEST_PROP_ODM_DEVICE=bixi
CURRENT="reject model when device properties conflict"
_model=$(dtbo_model)
if [ "$_model" = unknown ]; then ok; else bad "got=$_model"; fi
TEST_PROP_ODM_DEVICE=vendor_new
CURRENT="reject unknown real identity attribute alongside trusted device"
_model=$(dtbo_model)
if [ "$_model" = unknown ]; then ok; else bad "got=$_model"; fi
TEST_PROP_ODM_DEVICE=""
TEST_PROP_NAME=vendor_product_name
CURRENT="ignore non-model product name when trusted device exists"
_model=$(dtbo_model)
if [ "$_model" = annibale ]; then ok; else bad "got=$_model"; fi
TEST_PROP_NAME=warsaw
CURRENT="reject product name that aliases a different supported model"
_model=$(dtbo_model)
if [ "$_model" = unknown ]; then ok; else bad "got=$_model"; fi
TEST_PROP_NAME=""
TEST_PROP_DEVICE=""
TEST_PROP_NAME=vendor_new
CURRENT="reject unknown product name when no device property exists"
_model=$(dtbo_model)
if [ "$_model" = unknown ]; then ok; else bad "got=$_model"; fi
TEST_PROP_NAME=annibale
CURRENT="accept trusted product-name alias when device properties absent"
_model=$(dtbo_model)
if [ "$_model" = annibale ]; then ok; else bad "got=$_model"; fi
TEST_PROP_NAME=""
CURRENT="reject unknown model when no device property"
_model=$(dtbo_model)
if [ "$_model" = unknown ]; then ok; else bad "got=$_model"; fi
TEST_PROP_DEVICE=annibale

TEST_SIZE_UNKNOWN=1; expect_fail "reject unknown partition size" dtbo_preflight builtin
TEST_SIZE_UNKNOWN=0
TEST_PROP_DEVICE=unknown; expect_fail "reject unknown model" dtbo_preflight builtin
TEST_PROP_DEVICE=annibale
cp "$IMG" "$TMP/wrong.img"; printf 'WRONG' >>"$TMP/wrong.img"
expect_fail "reject untrusted selected image" dtbo_preflight "$TMP/wrong.img"

python3 - "$DTBO_IDENTITY_FILE" <<'PY'
import sys
path = sys.argv[1]
lines = open(path, encoding='utf-8').read().splitlines()
out = []
for line in lines:
    if line.startswith('annibale|'):
        fields = line.split('|')
        fields[4] = 'incompatible:K5'
        line = '|'.join(fields)
    out.append(line)
open(path, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
PY
expect_fail "reject explicitly incompatible OS" dtbo_preflight builtin

python3 - "$DTBO_IDENTITY_FILE" <<'PY'
import sys
path = sys.argv[1]
lines = open(path, encoding='utf-8').read().splitlines()
out = []
for line in lines:
    if line.startswith('annibale|'):
        fields = line.split('|')
        fields[4] = 'unknown'
        line = '|'.join(fields)
    out.append(line)
open(path, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
PY

# Unknown OS must provide an independent ack and then produce a token.
TEST_OS=14
expect_fail "unknown OS requires ack" dtbo_preflight builtin
run "unknown OS acknowledged preflight" dtbo_preflight builtin --ack-os-unknown
contains 'os_state=unknown' || bad "ack preflight missing unknown state"
TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
[ -n "$TOKEN" ] || bad "ack preflight did not return token"
SHORT_READ=1; expect_fail "reject short current partition read" dtbo_preflight builtin --ack-os-unknown; SHORT_READ=0

# The selected object is fixed; direct flash and changed partition are rejected.
expect_fail "direct flash without token" dtbo_flash builtin
guard_write "$DEV"
printf 'CHANGED-CONTENT' >"$DEV"
expect_fail "reject changed target after confirmation" dtbo_flash builtin --confirm "$TOKEN"
guard_write "$DEV"
printf 'ORIGINAL-DTBO-CONTENT-1234567890' >"$DEV"
run "fresh acknowledged preflight" dtbo_preflight builtin --ack-os-unknown
TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
run "flash with verified backup and readback" dtbo_flash builtin --confirm "$TOKEN" --ack-os-unknown
contains 'flash=success' || bad "flash did not report success"
[ "$(stat -c %s "$DEV")" = "$DEV_SIZE" ] || bad "flash changed exact partition size"

# Backup dedup requires the actual path plus slot, size, hash and model.
run "explicit backup" dtbo_backup "$TMP/explicit"
BACKUP=$(sed -n 's/^backup=//p' "$TMP/out")
need_tmp "$BACKUP"
[ -f "$BACKUP" ] || bad "backup file missing"
BACKUP_SHA=$(sha256sum "$BACKUP" | awk '{print $1}')
run "deduplicated backup" dtbo_backup "$TMP/explicit"
[ "$(find "$TMP/explicit" -name '*.img' | wc -l | tr -d ' ')" = 1 ] || bad "backup dedup created duplicate"

CURRENT="backup_find requires matching model"
if dtbo_backup_find "$TMP/explicit" "$DEV" a "$DEV_SIZE" "$BACKUP_SHA" bixi >"$TMP/out" 2>&1; then
  bad "backup matched a different model"
else
  ok
fi
CURRENT="backup_find matches same model"
if [ "$(dtbo_backup_find "$TMP/explicit" "$DEV" a "$DEV_SIZE" "$BACKUP_SHA" annibale 2>/dev/null)" = "$BACKUP" ]; then
  ok
else
  CURRENT="same-model backup lookup output"
  dtbo_backup_find "$TMP/explicit" "$DEV" a "$DEV_SIZE" "$BACKUP_SHA" annibale >"$TMP/backup-find.out" 2>&1 || true
  bad "same-model backup was not found path=$BACKUP size=$DEV_SIZE sha=$BACKUP_SHA output=$(tr '\n' ' ' <"$TMP/backup-find.out") manifest=$(tr '\n' ';' <"$TMP/explicit/manifest.txt")"
fi
TEST_PROP_DEVICE=bixi
run "backup for different model is not deduplicated" dtbo_backup "$TMP/explicit"
[ "$(find "$TMP/explicit" -name '*.img' | wc -l | tr -d ' ')" = 2 ] || bad "different-model backup was deduplicated"
TEST_PROP_DEVICE=annibale

# Restore is explicit and needs a restore preflight token.
run "restore preflight" dtbo_preflight --restore "$BACKUP"
RESTORE_TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
run "restore verified backup" dtbo_restore "$BACKUP" --confirm "$RESTORE_TOKEN"
contains 'restore=success' || bad "restore did not report success"
printf 'corrupt' >"$BACKUP"
expect_fail "reject damaged backup" dtbo_preflight --restore "$BACKUP"
expect_fail "restore rejects guessed path" dtbo_preflight --restore "$TMP/does-not-exist.img"

# Source snapshot and failure paths.
expect_fail "download requires OS ack" dtbo_download
run "download verifies index and image" dtbo_download --ack-os-unknown
[ "$(stat -c %s "$DTBO_DIR/local.img")" = "$IMAGE_SIZE" ] || bad "download size mismatch"
[ "$(sha256sum "$DTBO_DIR/local.img" | awk '{print $1}')" = "$IMAGE_SHA" ] || bad "download SHA mismatch"
run "preflight before write failure" dtbo_preflight builtin --ack-os-unknown
TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
FAIL_DD=1; expect_fail "write failure is reported" dtbo_flash builtin --confirm "$TOKEN" --ack-os-unknown; FAIL_DD=0
run "preflight before verify failure" dtbo_preflight builtin --ack-os-unknown
TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
CORRUPT_READBACK=1; rm -f "$CORRUPT_MARKER"
expect_fail "rollback succeeds after readback mismatch" dtbo_flash builtin --confirm "$TOKEN" --ack-os-unknown
contains '自动回滚成功' || bad "rollback success was not reported"
CORRUPT_READBACK=0

# Padding and lock handling are independently exercised.
run "preflight for padding" dtbo_preflight builtin --ack-os-unknown
TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
FAIL_PAD=1; expect_fail "padding failure aborts before write" dtbo_flash builtin --confirm "$TOKEN" --ack-os-unknown; FAIL_PAD=0
run "preflight after padding failure" dtbo_preflight builtin --ack-os-unknown
TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
run "padding exact size" dtbo_flash builtin --confirm "$TOKEN" --ack-os-unknown
mkdir -p "$DTBO_LOCK_DIR"; printf 999999 >"$DTBO_LOCK_DIR/pid"
run "stale lock reclaimed" dtbo_lock_acquire
# A live lock must block another operation.
mkdir -p "$DTBO_LOCK_DIR"; printf '%s' "$$" >"$DTBO_LOCK_DIR/pid"
expect_fail "live lock conflict" dtbo_preflight builtin --ack-os-unknown
rm -rf "$DTBO_LOCK_DIR"

# Rollback must rebind identity and refuse if slot changes before its own write.
STALE_SHA=$(sha256sum "$DEV" | awk '{print $1}')
DD_WRITE_COUNT=0
TEST_SUFFIX=_b; TEST_SLOT=b
CURRENT="rollback guard rejects changed slot with zero device writes"
if dtbo_rollback "$DEV" "$DEV_SIZE" "$BACKUP" "$BACKUP_SHA" a annibale >"$TMP/out" 2>&1; then
  bad "rollback accepted changed slot"
else
  [ "$DD_WRITE_COUNT" = 0 ] && ok || bad "rollback device writes=$DD_WRITE_COUNT"
fi
TEST_SUFFIX=_a; TEST_SLOT=a

# A rollback failure must surface as a severe error. This overrides dtbo_rollback,
# so it runs after every flash case that needs the production rollback path.
run "preflight before rollback failure" dtbo_preflight builtin --ack-os-unknown
TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
dtbo_rollback() { echo "严重错误: simulated rollback failure"; return 1; }
CORRUPT_READBACK=1; rm -f "$CORRUPT_MARKER"
expect_fail "rollback failure is severe" dtbo_flash builtin --confirm "$TOKEN" --ack-os-unknown
contains '严重错误' || bad "rollback failure did not report severe error"
CORRUPT_READBACK=0

# Metadata field control characters.
CURRENT="metadata field accepts plain code"
if dtbo_safe_field annibale >"$TMP/out" 2>&1; then ok; else bad "plain code rejected"; fi
CURRENT="metadata field rejects TAB"
if dtbo_safe_field "$(printf 'annibale\tx')" >"$TMP/out" 2>&1; then bad "TAB accepted"; else ok; fi
CURRENT="metadata field rejects CR"
if dtbo_safe_field "$(printf 'annibale\rx')" >"$TMP/out" 2>&1; then bad "CR accepted"; else ok; fi
CURRENT="metadata field rejects LF"
if dtbo_safe_field "$(printf 'annibale\nx')" >"$TMP/out" 2>&1; then bad "LF accepted"; else ok; fi

# Token freshness (TTL 300) reads created= from the second meta line.
TEST_NOW=1700000000
run "preflight for token expiry" dtbo_preflight builtin --ack-os-unknown
TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
TOKEN_META="$DTBO_RUNTIME_DIR/$TOKEN.meta"
TOKEN_SNAP="$DTBO_RUNTIME_DIR/$TOKEN.img"
need_tmp "$TOKEN_META"
TEST_NOW=$((TEST_NOW + 300))
CURRENT="token fresh at TTL boundary"
if dtbo_token_fresh "$TOKEN_META"; then ok; else bad "rejected exactly at TTL"; fi
TEST_NOW=$((TEST_NOW + 1))
CURRENT="expired token rejected"
if dtbo_token_fresh "$TOKEN_META"; then bad "expired token accepted"; else ok; fi
expect_fail "expired token cannot flash" dtbo_flash builtin --confirm "$TOKEN" --ack-os-unknown

# Consuming a token renames meta and snapshot to .used and cannot repeat.
TEST_NOW=1700000000
run "preflight for token consume" dtbo_preflight builtin --ack-os-unknown
TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
TOKEN_META="$DTBO_RUNTIME_DIR/$TOKEN.meta"
TOKEN_SNAP="$DTBO_RUNTIME_DIR/$TOKEN.img"
need_tmp "$TOKEN_META"
CURRENT="token consume renames meta and snapshot to .used"
if dtbo_consume_token "$TOKEN_META" "$TOKEN_SNAP" && [ -f "$TOKEN_META.used" ] && [ -f "$TOKEN_SNAP.used" ]; then
  ok
else
  bad "meta/snapshot were not renamed to .used"
fi
CURRENT="consumed token cannot be consumed twice"
if dtbo_consume_token "$TOKEN_META" "$TOKEN_SNAP"; then bad "second consume accepted"; else ok; fi
expect_fail "replay of consumed token rejected" dtbo_flash builtin --confirm "$TOKEN" --ack-os-unknown

# A CRLF created= line must not be treated as a fresh token.
run "preflight for CRLF meta" dtbo_preflight builtin --ack-os-unknown
TOKEN=$(sed -n 's/^snapshot_token=//p' "$TMP/out")
TOKEN_META="$DTBO_RUNTIME_DIR/$TOKEN.meta"
need_tmp "$TOKEN_META"
python3 - "$TOKEN_META" <<'PY'
import sys
path = sys.argv[1]
with open(path, 'rb') as f:
    data = f.read()
with open(path, 'wb') as f:
    f.write(data.replace(b'\n', b'\r\n'))
PY
CURRENT="CRLF created line is rejected"
if dtbo_token_fresh "$TOKEN_META"; then bad "CRLF created value accepted"; else ok; fi

# Write guard: any target or slot change must refuse before touching the device.
guard_write "$TMP/guard.img"
printf 'GUARD-IMAGE' >"$TMP/guard.img"
GUARD_SHA=$(sha256sum "$TMP/guard.img" | awk '{print $1}')
STALE_SHA=0000000000000000000000000000000000000000000000000000000000000000
_old_sha=$(sha256sum "$DEV" | awk '{print $1}')
guard_write "$DEV"
printf 'TAMPERED-DTBO-CONTENT-0987654321' >"$DEV"
DD_WRITE_COUNT=0
CURRENT="write guard rejects changed target with zero device writes"
if dtbo_write_and_verify "$DEV" "$DEV_SIZE" "$TMP/guard.img" "$GUARD_SHA" "$BACKUP" a annibale "$_old_sha" >"$TMP/out" 2>&1; then
  bad "write guard accepted a changed target"
else
  if [ "$DD_WRITE_COUNT" = 0 ]; then ok; else bad "device writes=$DD_WRITE_COUNT"; fi
fi
TEST_SUFFIX=_b; TEST_SLOT=b
DD_WRITE_COUNT=0
CURRENT="write guard rejects changed slot with zero device writes"
if dtbo_write_and_verify "$DEV" "$DEV_SIZE" "$TMP/guard.img" "$GUARD_SHA" "$BACKUP" a annibale "$STALE_SHA" >"$TMP/out" 2>&1; then
  bad "write guard accepted a changed slot"
else
  if [ "$DD_WRITE_COUNT" = 0 ]; then ok; else bad "device writes=$DD_WRITE_COUNT"; fi
fi
TEST_SUFFIX=_a; TEST_SLOT=a

# The boundary itself must refuse an outside write without creating anything.
CURRENT="guard refuses writes outside the private temp directory"
OUTSIDE="$TMP.outside"
if dtbo_dd if="$DEV" of="$OUTSIDE" bs=1M >"$TMP/out" 2>&1; then
  bad "dd accepted an outside write target"
else
  if [ -e "$OUTSIDE" ]; then bad "outside file was created"; command rm -f -- "$OUTSIDE"; else ok; fi
fi

# Ensure production script has no test bypass and shell syntax remains valid.
CURRENT="production has no regular-file test bypass"
if grep -q 'DTBO_TEST' "$ROOT/engine/lib/dtbo.sh"; then bad "test bypass leaked into production"; else ok; fi
CURRENT="production shell syntax"
if sh -n "$ROOT/engine/lib/dtbo.sh"; then ok; else bad "sh -n failed"; fi

CURRENT="production engine and manifest were not modified"
PROD_SHA_AFTER=$(command sha256sum "$ROOT/engine/lib/dtbo.sh" "$IDENTITY_SRC" 2>/dev/null | awk '{print $1}' | tr '\n' ' ')
if [ "$PROD_SHA_BEFORE" = "$PROD_SHA_AFTER" ]; then ok; else bad "production files changed during the run"; fi

CURRENT="all engine path variables point inside the private temp directory"
_escaped=""
for _p in "$DTBO_DIR" "$DTBO_BACKUP_PRIMARY" "$DTBO_BACKUP_FALLBACK" "$DTBO_IDENTITY_FILE" "$DTBO_RUNTIME_DIR" "$DTBO_LOCK_DIR" "$DTBO_EDIRS_FILE"; do
  in_tmp "$_p" || _escaped="$_escaped $_p"
done
if [ -z "$_escaped" ]; then ok; else bad "outside temp dir:$_escaped"; fi

CURRENT="all file operations stayed inside private temp directory"
_denies=$(wc -l <"$DENY_LOG" 2>/dev/null | tr -d ' ')
if [ "${_denies:-0}" = 1 ] && grep -q "$OUTSIDE" "$DENY_LOG"; then
  ok
else
  bad "unexpected denials: $(tr '\n' ' ' <"$DENY_LOG")"
fi

printf 'DTBO regression: %s passed, %s failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
