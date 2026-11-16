#!/bin/sh
# scripts/selfcheck_regression.sh — engine/lib/selfcheck.sh 的独立回归。
#
# 每个用例在独立 mktemp 目录里构造假模块/数据/日志/PID1 mountinfo 样本，
# source 真实 selfcheck.sh 后直接调用 sc_item_N，只替换外部命令与子命令封装
# （sc_call_status / sc_call_battery_status / pm / dumpsys），
# 不读写 /data、/storage、/sys，也不启动真实 daemon 或 xjctl。
#
# 用法: sh scripts/selfcheck_regression.sh   # 全绿退出 0，有用例失败退出 1

REPO=$(cd "$(dirname "$0")/.." && pwd)
LIB="$REPO/engine/lib/selfcheck.sh"
[ -f "$LIB" ] || { echo "找不到 $LIB"; exit 1; }

PASS_N=0
FAIL_N=0
FAILED=""
CASE=""
T=""

ok() { PASS_N=$((PASS_N + 1)); }
bad() { FAIL_N=$((FAIL_N + 1)); FAILED="$FAILED
  [$CASE] $*"; }

# expect 期望标签 期望子串 实际输出
expect() {
  _tag=$1; _sub=$2; _out=$3
  case "$_out" in
    *"[$_tag]"*"$_sub"*) ok ;;
    *) bad "期望 [$_tag] 且含「$_sub」，实际: $(printf '%s' "$_out" | tr '\n' '|')" ;;
  esac
}

# expect_has 期望子串 实际输出（不关心标签）
expect_has() {
  _sub=$1; _out=$2
  case "$_out" in
    *"$_sub"*) ok ;;
    *) bad "期望含「$_sub」，实际: $(printf '%s' "$_out" | tr '\n' '|')" ;;
  esac
}

setup() {
  CASE=$1
  T=$(mktemp -d "${TMPDIR:-/tmp}/xjsc.XXXXXX") || { echo "mktemp 失败"; exit 1; }
  mkdir -p "$T/engine" "$T/mod/battery/baa" "$T/odm" "$T/data/log" \
           "$T/data/state" "$T/data/battery" "$T/bin" "$T/card"
  printf 'id=rezhale_toolbox\nversion=9.9.9\nversionCode=999\n' > "$T/mod/module.prop"
  printf 'rearscreen=0\n' > "$T/data/battery/settings.conf"
  : > "$T/pid1.mountinfo"
  printf '%s' '{}' > "$T/status.out"
  printf '%s\n' 'unlock=1' 'baa=inactive' > "$T/battery.out"

  MODDIR="$T/mod"
  LOGDIR="$T/data/log"
  STATE="$T/data/state"
  BT_CONF="$T/data/battery/settings.conf"
  DATA_DIR="$T/data"
  SUPPORTED_DEVS="annibale bixi byta haotian"
  BAA_LABEL="u:object_r:batteryantiaging_cfg_file:s0"
  SC_DIR="$T/engine"
  DIR="$T/engine"
  SC_PID1_MOUNTINFO="$T/pid1.mountinfo"
  SC_PID1_ROOT=""
  SC_REAR_CARD="$T/card/rear.conf"
  SC_REAR_TMP="$T/card/tmp_rear.conf"
  SC_SUB_OK=""
  PATH="$T/bin:$PATH"
  # 模拟 Android 真实布局：源在 /data 独立挂载下的 /data/adb/...
  SRC="$T/data/adb/rezhale_toolbox/battery/baa/payload_BAA_config_haotian.json"
  TGT="$T/odm/BAA_config_haotian.json"
  DATA_MP="$T/data"
  ADB_MP="$T/data/adb"

  # 库依赖的最小替身（只读实现，不落盘）
  mod_version() { printf '9.9.9'; }
  cfg_get() {
    _v=$(grep -a "^$1=" "$BT_CONF" 2>/dev/null | tail -n 1 | cut -d= -f2-)
    [ -n "$_v" ] && printf '%s' "$_v" || printf '%s' "${2:-}"
  }
  dev_code() { printf 'haotian'; }
  baa_src() { printf '%s' "$SRC"; }
  baa_tgt() { printf '%s' "$TGT"; }

  # shellcheck disable=SC1090
  . "$LIB"

  # source 之后再覆盖子命令封装：真机走 xjctl，回归走样本
  sc_call_status() { printf '%s' "$STATUS_OUT"; return "${STATUS_RC:-0}"; }
  sc_call_battery_status() { printf '%s\n' "$BATTERY_OUT"; return "${BATTERY_RC:-0}"; }
  STATUS_OUT=$(cat "$T/status.out" 2>/dev/null)
  STATUS_RC=0
  BATTERY_OUT=$(cat "$T/battery.out" 2>/dev/null)
  BATTERY_RC=0
  SC_PASS=0; SC_FAIL=0; SC_HUMAN=0; SC_SKIP=0
  SC_STATUS=""; SC_STATUS_RC=0; SC_SUB_OK=""
}

teardown() {
  [ -n "$T" ] && rm -rf "$T"
  T=""
}

# ---------- 第 9 项：PID1 解容 ----------
# 样本按 Android 真实布局构造：/ 为根挂载，/data 独立挂载（root=/，dev=8:2），
# 源在 /data/adb/... 下，所以 bind 后 mountinfo 的 root 是 /adb/... 而不是绝对路径。
mk_files() {  # $1=1 目标与源同 inode（bind 生效）；否则目标为独立原厂文件
  mkdir -p "$(dirname "$SRC")" "$(dirname "$TGT")"
  printf 'BAA_ENC:%s' '0123456789abcdef0123456789abcdef' > "$SRC"
  if [ "$1" = "1" ]; then
    rm -f "$TGT"; ln "$SRC" "$TGT"
  else
    printf 'BAA_ENC:stock' > "$TGT"
  fi
}
mi_base() {  # $1=/data 挂载行的 fs root（默认 /）
  printf '1 0 0:1 / / rw - rootfs rootfs rw\n'
  printf '50 1 8:2 %s %s rw - f2fs /dev/block/dm-5 rw\n' "${1:-/}" "$DATA_MP"
}
mi_target() {  # $1=dev $2=root $3=mount point（默认 $TGT）
  printf '60 1 %s %s %s rw - f2fs /dev/block/dm-5 rw\n' "$1" "$2" "${3:-$TGT}"
}
unlock_on() {
  printf '%s\n' 'unlock=1' 'baa=active' > "$T/battery.out"
  BATTERY_OUT=$(cat "$T/battery.out")
}
unlock_off() {
  printf '%s\n' 'unlock=0' 'baa=inactive' > "$T/battery.out"
  BATTERY_OUT=$(cat "$T/battery.out")
}

setup "9 正常：/data 独立挂载(root=/)，目标为挂载点且来源指向模块源"
mk_files 1; unlock_on
{ mi_base; mi_target 8:2 /adb/rezhale_toolbox/battery/baa/payload_BAA_config_haotian.json; } > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect PASS "PID1 mountinfo 确认" "$out"
expect PASS "unlock=1" "$out"
teardown

setup "9 正常：/data 挂载 root 非 /（/sub），root 拼接正确"
mk_files 1; unlock_on
{ mi_base /sub; mi_target 8:2 /sub/adb/rezhale_toolbox/battery/baa/payload_BAA_config_haotian.json; } > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect PASS "PID1 mountinfo 确认" "$out"
teardown

setup "9 正常：/data/adb 是子 mount，按子挂载的 dev/root 判定"
mk_files 1; unlock_on
{
  mi_base
  printf '55 1 8:3 / %s rw - f2fs /dev/block/dm-6 rw\n' "$ADB_MP"
  mi_target 8:3 /rezhale_toolbox/battery/baa/payload_BAA_config_haotian.json
} > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect PASS "PID1 mountinfo 确认" "$out"
teardown

setup "9 正常：目标路径是 symlink，按解析后的真实路径匹配"
mk_files 1; unlock_on
REAL="$T/odm/real_BAA_config.json"
rm -f "$TGT"; ln "$SRC" "$REAL"; ln -s "$REAL" "$TGT"
{ mi_base; mi_target 8:2 /adb/rezhale_toolbox/battery/baa/payload_BAA_config_haotian.json "$REAL"; } > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect PASS "PID1 mountinfo 确认" "$out"
teardown

setup "9 异常：unlock=1 但 PID1 中目标不是挂载点"
mk_files 0; unlock_on
mi_base > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect FAIL "不是挂载点" "$out"
expect FAIL "不等于已 bind 挂载" "$out"
teardown

setup "9 正常：unlock=0 且目标不是挂载点"
mk_files 0; unlock_off
mi_base > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect PASS "不是挂载点" "$out"
teardown

setup "9 异常：unlock=0 但 PID1 仍挂载，必须指出"
mk_files 1; unlock_off
{ mi_base; mi_target 8:2 /adb/rezhale_toolbox/battery/baa/payload_BAA_config_haotian.json; } > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect FAIL "仍挂载" "$out"
expect FAIL "battery unlock off" "$out"
teardown

setup "9 异常：真正其它源（dev/root 不同且 inode 不同）"
mk_files 0; unlock_on
{
  mi_base
  printf '60 1 8:9 /other/BAA_config_haotian.json %s rw - f2fs /dev/block/dm-9 rw\n' "$TGT"
} > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect FAIL "挂载来源不是预期载荷" "$out"
teardown

setup "9 异常：结构不符但 inode 相同，不得假 PASS"
mk_files 1; unlock_on
{
  mi_base
  printf '60 1 8:2 /wrong/path %s rw - f2fs /dev/block/dm-5 rw\n' "$TGT"
} > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect FAIL "无法判定来源" "$out"
teardown

setup "9 异常：PID1 视角读不到源/目标（无权限），不得假 PASS"
mk_files 1; unlock_on
SC_PID1_ROOT="$T/noperm"
{ mi_base; mi_target 8:2 /adb/rezhale_toolbox/battery/baa/payload_BAA_config_haotian.json; } > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect FAIL "无法确认 bind 来源" "$out"
teardown

setup "9 异常：battery status 退出码非 0"
mk_files 0; unlock_on
mi_base > "$SC_PID1_MOUNTINFO"
BATTERY_RC=2
out=$(sc_item_9)
expect FAIL "退出码 2" "$out"
teardown

setup "9 异常：unlock 非法值"
mk_files 0
mi_base > "$SC_PID1_MOUNTINFO"
printf '%s\n' 'unlock=yes' 'baa=active' > "$T/battery.out"
BATTERY_OUT=$(cat "$T/battery.out")
out=$(sc_item_9)
expect FAIL "unlock=yes 非法" "$out"
teardown

setup "9 异常：PID1 mountinfo 不可读"
mk_files 1
SC_PID1_MOUNTINFO="$T/nonexistent.mountinfo"
out=$(sc_item_9)
expect FAIL "读不到" "$out"
expect FAIL "无法判定" "$out"
teardown

setup "9 SKIP：dev_code=unknown 且无载荷与目标节点"
mk_files 0
rm -f "$SRC" "$TGT"
dev_code() { printf 'unknown'; }
mi_base > "$SC_PID1_MOUNTINFO"
out=$(sc_item_9)
expect SKIP "解容不适用" "$out"
expect SKIP "unlock=" "$out"
teardown

setup "9 异常：dev_code 为空（与 unknown 区分）"
mk_files 0
dev_code() { printf ''; }
out=$(sc_item_9)
expect FAIL "dev_code 为空" "$out"
teardown

setup "9 异常：源文件缺失但挂载行指向它（无法确认载荷）"
mk_files 1; unlock_on
{ mi_base; mi_target 8:2 /adb/rezhale_toolbox/battery/baa/payload_BAA_config_haotian.json; } > "$SC_PID1_MOUNTINFO"
rm -f "$SRC"
out=$(sc_item_9)
expect FAIL "无法在 PID1 视角读取" "$out"
teardown

# ---------- 第 4 项：daemon 身份 ----------
setup "4 正常：cmdline 精确含本模块 xjdaemon.sh"
printf 'sleep 20\n' > "$T/engine/xjdaemon.sh"
sh "$T/engine/xjdaemon.sh" &
DPID=$!
STATUS_RC=0
STATUS_OUT="{\"module_version\":\"9.9.9\",\"device_code\":\"haotian\",\"daemon_pid\":\"$DPID\"}"
SC_STATUS="$STATUS_OUT"
out=$(sc_item_4)
expect PASS "cmdline 含" "$out"
kill "$DPID" 2>/dev/null
wait "$DPID" 2>/dev/null
teardown

setup "4 异常：PID 存在但 cmdline 不是 xjdaemon（PID 复用）"
STATUS_RC=0
STATUS_OUT="{\"module_version\":\"9.9.9\",\"device_code\":\"haotian\",\"daemon_pid\":\"$$\"}"
SC_STATUS="$STATUS_OUT"
out=$(sc_item_4)
expect FAIL "cmdline 不含本模块 xjdaemon.sh" "$out"
teardown

setup "4 异常：pid 残留（/proc 不存在）"
STATUS_RC=0
STATUS_OUT="{\"module_version\":\"9.9.9\",\"device_code\":\"haotian\",\"daemon_pid\":\"999999\"}"
SC_STATUS="$STATUS_OUT"
out=$(sc_item_4)
expect FAIL "pid 残留" "$out"
teardown

setup "4 异常：status 退出码非 0"
SC_STATUS_RC=124
out=$(sc_item_4)
expect FAIL "退出码 124" "$out"
teardown

setup "4 异常：daemon_pid 非数字"
STATUS_RC=0
STATUS_OUT="{\"module_version\":\"9.9.9\",\"device_code\":\"haotian\",\"daemon_pid\":\"\"}"
SC_STATUS="$STATUS_OUT"
out=$(sc_item_4)
expect FAIL "为空或非数字" "$out"
teardown

# ---------- 第 5 项：采样日志 ----------
now_ts() { date '+%F %T'; }
old_ts() { date -d '@'"$(( $(date +%s) - 3600 ))" '+%F %T' 2>/dev/null; }
future_ts() { date -d '@'"$(( $(date +%s) + 3600 ))" '+%F %T' 2>/dev/null; }
HDR='# soc cur_uA volt_uV temp_0.1C power_uW charging wired_chg_curr charger_v_uV usb_cur_uA charger_pmax mode applied power_sig'
ROW='55 -1200000 4300000 320 -5160000 0 - - - - default inf inf|1800|0|'

setup "5 正常：14 列表头 + 当前段 + 13 列数据行"
{
  printf '%s\n' "$HDR"
  printf '# %s\n' "$(now_ts)"
  printf '%s\n' "$ROW"
} > "$LOGDIR/diag_samples.log"
out=$(sc_item_5)
expect PASS "行合法数据" "$out"
teardown

setup "5 正常：截尾丢表头，仍按 13 列数据行判定"
{
  printf '# %s\n' "$(now_ts)"
  printf '%s\n' "$ROW"
} > "$LOGDIR/diag_samples.log"
out=$(sc_item_5)
expect PASS "表头已被截尾丢弃" "$out"
teardown

setup "5 异常：表头 13 列"
{
  printf '# soc cur_uA volt_uV temp_0.1C power_uW charging wired_chg_curr charger_v_uV usb_cur_uA charger_pmax mode applied\n'
  printf '# %s\n' "$(now_ts)"
  printf '%s\n' "$ROW"
} > "$LOGDIR/diag_samples.log"
out=$(sc_item_5)
expect FAIL "表头 13 列" "$out"
teardown

setup "5 异常：最新段只有段头，无合法数据行"
{
  printf '%s\n' "$HDR"
  printf '# %s\n' "$(now_ts)"
  printf '55 -1200000 4300000 320 -5160000 0 - - - - default inf\n'
} > "$LOGDIR/diag_samples.log"
out=$(sc_item_5)
expect FAIL "没有 13 列的合法数据行" "$out"
teardown

setup "5 异常：数据行字段非法（soc 非数字）"
{
  printf '%s\n' "$HDR"
  printf '# %s\n' "$(now_ts)"
  printf 'abc -1200000 4300000 320 -5160000 0 - - - - default inf x\n'
} > "$LOGDIR/diag_samples.log"
out=$(sc_item_5)
expect FAIL "没有 13 列的合法数据行" "$out"
teardown

setup "5 异常：段头是未来时间"
{
  printf '%s\n' "$HDR"
  printf '# %s\n' "$(future_ts)"
  printf '%s\n' "$ROW"
} > "$LOGDIR/diag_samples.log"
out=$(sc_item_5)
expect FAIL "晚于当前时间" "$out"
teardown

setup "5 异常：段头超过 5 分钟"
{
  printf '%s\n' "$HDR"
  printf '# %s\n' "$(old_ts)"
  printf '%s\n' "$ROW"
} > "$LOGDIR/diag_samples.log"
out=$(sc_item_5)
expect FAIL "已超过 5 分钟" "$out"
teardown

setup "5 异常：无段头 / 空文件"
: > "$LOGDIR/diag_samples.log"
out=$(sc_item_5)
expect FAIL "无" "$out"
teardown

# ---------- 第 7 项：留存 ----------
setup "7 正常：1 小时前执行"
printf '%s' "$(( $(date +%s) - 3600 ))" > "$STATE/retention.ts"
out=$(sc_item_7)
expect PASS "86400s" "$out"
expect PASS "3600s" "$out"
expect PASS "不代表清理全部成功" "$out"
teardown

setup "7 异常：未来时间戳"
printf '%s' "$(( $(date +%s) + 7200 ))" > "$STATE/retention.ts"
out=$(sc_item_7)
expect FAIL "未来时间戳" "$out"
teardown

setup "7 异常：超过 90000s"
printf '%s' "$(( $(date +%s) - 100000 ))" > "$STATE/retention.ts"
out=$(sc_item_7)
expect FAIL "上限 90000s" "$out"
teardown

setup "7 异常：文件缺失"
out=$(sc_item_7)
expect FAIL "清理从未执行" "$out"
teardown

# ---------- 第 8 项：status JSON ----------
run_json() {
  SC_STATUS="$1"
  SC_STATUS_RC="${2:-0}"
  sc_item_8
}

setup "8 正常：完整 flat JSON"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian","daemon_pid":"1234","kernel":"6.1.0"}')
expect PASS "完整 flat JSON" "$out"
teardown

setup "8 正常：值含全部合法转义"
out=$(run_json '{"module_version":"9.9.9","device_code":"hao\/tian","daemon_pid":"1","note":"a\"b\\c\td\n\u4e2d\u6587"}')
expect PASS "完整 flat JSON" "$out"
teardown

setup "8 正常：转义键 \\u005f 规范化为 module_version"
out=$(run_json '{"module\u005fversion":"9.9.9","device_code":"haotian","daemon_pid":"1"}')
expect PASS "完整 flat JSON" "$out"
teardown

setup "8 异常：\\u0062 与 ab 应判重复键"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian","daemon_pid":"1","a\u0062":"x","ab":"y"}')
expect FAIL "重复键" "$out"
teardown

setup "8 正常：不同 unicode 键不应误判重复"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian","daemon_pid":"1","\u4e2d":"x","\u6587":"y"}')
expect PASS "完整 flat JSON" "$out"
teardown

setup "8 异常：代理对与原始 emoji 应判重复键"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian","daemon_pid":"1","\ud83d\ude00":"x","😀":"y"}')
expect FAIL "重复键" "$out"
teardown

setup "8 正常：孤立代理保留字面，两个不同代理不误重"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian","daemon_pid":"1","\ud800":"x","\ud801":"y"}')
expect PASS "完整 flat JSON" "$out"
teardown

setup "8 异常：status 退出码非 0"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian","daemon_pid":"1"}' 124)
expect FAIL "退出码 124" "$out"
teardown

setup "8 异常：缺少必需键"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian"}')
expect FAIL "缺少必需键" "$out"
expect FAIL "daemon_pid" "$out"
teardown

setup "8 异常：尾随逗号"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian","daemon_pid":"1",}')
expect FAIL "尾随逗号" "$out"
teardown

setup "8 异常：非法截断"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian","daemon_pid":"1"')
expect FAIL "首字符必须是" "$out"
teardown

setup "8 异常：两个对象拼接"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian","daemon_pid":"1"}{"a":"b"}')
expect FAIL "缺逗号" "$out"
teardown

setup "8 异常：值不是字符串"
out=$(run_json '{"module_version":9.9,"device_code":"haotian","daemon_pid":"1"}')
expect FAIL "值解析失败" "$out"
teardown

setup "8 异常：重复键"
out=$(run_json '{"module_version":"9.9.9","module_version":"9.9.9","device_code":"haotian","daemon_pid":"1"}')
expect FAIL "重复键" "$out"
teardown

setup "8 异常：非法转义与未闭合字符串"
out=$(run_json '{"module_version":"9.9.9","device_code":"haotian","daemon_pid":"1","x":"\q"}')
expect FAIL "非法转义" "$out"
teardown

setup "8 异常：对象中间缺逗号"
out=$(run_json '{"module_version":"9.9.9" "device_code":"haotian","daemon_pid":"1"}')
expect FAIL "缺逗号" "$out"
teardown

setup "8 异常：空输出"
out=$(run_json '')
expect FAIL "无输出" "$out"
teardown

# ---------- 第 11/12/13/14 项：背屏 ----------
mk_rear() {
  printf 'enabled=%s\nkeep_on=%s\naccent=#38BDF8\nfont_scale=1.0\nfields=%s\n' "$1" "$2" "$3" > "$SC_REAR_CARD"
  printf 'enabled=%s\nkeep_on=%s\naccent=#38BDF8\nfont_scale=1.0\nfields=%s\n' "$1" "$2" "$3" > "$SC_REAR_TMP"
}

setup "11 SKIP：无背屏组件"
SC_SUB_OK=0
out=$(sc_item_11)
expect SKIP "不适用" "$out"
teardown

setup "11 正常：关闭且无 enabled=1 残留"
SC_SUB_OK=1
printf 'rearscreen=0\n' > "$BT_CONF"
printf 'enabled=0\nkeep_on=0\nfields=\n' > "$SC_REAR_CARD"
out=$(sc_item_11)
expect PASS "无 enabled=1 残留" "$out"
teardown

setup "11 异常：关闭但主副本仍 enabled=1"
SC_SUB_OK=1
printf 'rearscreen=0\n' > "$BT_CONF"
printf 'enabled=1\nkeep_on=0\nfields=temp\n' > "$SC_REAR_CARD"
out=$(sc_item_11)
expect FAIL "仍有 enabled=1 残留" "$out"
teardown

setup "11 正常：启用且两份关键键一致"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
mk_rear 1 1 'temp,soc,volt,cur'
out=$(sc_item_11)
expect PASS "两份 enabled=1" "$out"
teardown

setup "11 异常：启用但两份都是 enabled=0"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
mk_rear 0 1 'temp,soc'
out=$(sc_item_11)
expect FAIL "应为 1" "$out"
teardown

setup "11 异常：启用但 keep_on 为空"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
mk_rear 1 '' 'temp,soc'
out=$(sc_item_11)
expect FAIL "keep_on=空" "$out"
teardown

setup "11 异常：启用但 fields 为空"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
mk_rear 1 1 ''
out=$(sc_item_11)
expect FAIL "fields 为空" "$out"
teardown

setup "11 异常：启用但两副本 fields 不一致"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
mk_rear 1 1 'temp,soc'
printf 'enabled=1\nkeep_on=1\naccent=#38BDF8\nfont_scale=1.0\nfields=temp\n' > "$SC_REAR_TMP"
out=$(sc_item_11)
expect FAIL "两副本不一致" "$out"
teardown

setup "11 异常：启用但两份配置都缺失"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
out=$(sc_item_11)
expect FAIL "配置丢失" "$out"
teardown

setup "12 SKIP：无背屏组件"
SC_SUB_OK=0
out=$(sc_item_12)
expect SKIP "不适用" "$out"
teardown

setup "12 正常：启用且最近轨迹是 ready"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
printf '%s lsposed overlay disabled\n%s lsposed overlay ready\n' \
  '2026-11-16 10:00:00' '2026-11-16 10:05:00' > "$LOGDIR/rearscreen.log"
out=$(sc_item_12)
expect PASS "最近轨迹:" "$out"
teardown

setup "12 异常：启用但最近轨迹是关闭（历史 ready 不算）"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
printf '%s lsposed overlay ready\n%s lsposed overlay disabled\n' \
  '2026-11-16 10:00:00' '2026-11-16 10:05:00' > "$LOGDIR/rearscreen.log"
out=$(sc_item_12)
expect FAIL "不是启用成功" "$out"
teardown

setup "12 正常：关闭且最近轨迹是关闭"
SC_SUB_OK=1
printf 'rearscreen=0\n' > "$BT_CONF"
printf '%s lsposed overlay ready\n%s lsposed overlay disabled\n' \
  '2026-11-16 10:00:00' '2026-11-16 10:05:00' > "$LOGDIR/rearscreen.log"
out=$(sc_item_12)
expect PASS "最近轨迹为关闭" "$out"
teardown

setup "12 正常：关闭但历史只有 ready，不沿用旧结论"
SC_SUB_OK=1
printf 'rearscreen=0\n' > "$BT_CONF"
printf '%s lsposed overlay ready\n' '2026-11-16 10:00:00' > "$LOGDIR/rearscreen.log"
out=$(sc_item_12)
expect PASS "关闭状态不要求 ready" "$out"
teardown

setup "12 正常：关闭且无轨迹"
SC_SUB_OK=1
printf 'rearscreen=0\n' > "$BT_CONF"
out=$(sc_item_12)
expect PASS "无背屏动作轨迹" "$out"
teardown

mk_dumpsys() {
  cat > "$T/bin/dumpsys" <<'EOS'
#!/bin/sh
case "${DUMPSYS_MODE:-running}" in
  running)
    cat <<'EOF'
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  User 0 active services:
  * ServiceRecord{abc123 u0 com.hyperos.xj/.rear.RearService}
    intent={cmp=com.hyperos.xj/.rear.RearService}
    packageName=com.hyperos.xj
    processName=com.hyperos.xj
    app=ProcessRecord{def456 1234:com.hyperos.xj/u0a123}
    isForeground=true
EOF
    ;;
  stopped)
    cat <<'EOF'
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  * ServiceRecord{abc123 u0 com.hyperos.xj/.rear.RearService}
    intent={cmp=com.hyperos.xj/.rear.RearService}
    app=null
    isForeground=false
EOF
    ;;
  multi)
    # 旧记录（已停 app=null）+ 新记录（运行中）：不得拿旧运行记录误 PASS
    cat <<'EOF'
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  * ServiceRecord{old111 u0 com.hyperos.xj/.rear.RearService}
    app=null
  * ServiceRecord{new222 u0 com.hyperos.xj/.rear.RearService}
    app=ProcessRecord{def456 1234:com.hyperos.xj/u0a123}
EOF
    ;;
  others)
    cat <<'EOF'
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  * ServiceRecord{zzz u0 com.other.app/.SomeService}
    app=ProcessRecord{aaa 999:com.other.app/u0a9}
EOF
    ;;
  empty) : ;;
  fail) echo "dumpsys: permission denied" >&2; exit 1 ;;
esac
EOS
  chmod +x "$T/bin/dumpsys"
}

setup "13 SKIP：关闭时不要求服务"
SC_SUB_OK=1
printf 'rearscreen=0\n' > "$BT_CONF"
out=$(sc_item_13)
expect SKIP "不要求 RearService" "$out"
teardown

setup "13 正常：启用且服务运行中"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
mk_dumpsys; export DUMPSYS_MODE=running
out=$(sc_item_13)
expect PASS "运行中的" "$out"
teardown

setup "13 HUMAN：启用但服务 app=null（FGS 未起来，overlay 可能仍正常）"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
mk_dumpsys; export DUMPSYS_MODE=stopped
out=$(sc_item_13)
expect HUMAN "未见运行中的 RearService" "$out"
expect HUMAN "不依赖 FGS" "$out"
teardown

setup "13 HUMAN：多条 record 中一条 app=null，不得误 PASS"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
mk_dumpsys; export DUMPSYS_MODE=multi
out=$(sc_item_13)
expect HUMAN "未见运行中的 RearService" "$out"
teardown

setup "13 HUMAN：启用但 dumpsys 只有其它服务"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
mk_dumpsys; export DUMPSYS_MODE=others
out=$(sc_item_13)
expect HUMAN "未见运行中的 RearService" "$out"
teardown

setup "13 异常：启用但 dumpsys 不可用（权限错误）"
SC_SUB_OK=1
printf 'rearscreen=1\n' > "$BT_CONF"
mk_dumpsys; export DUMPSYS_MODE=fail
out=$(sc_item_13)
expect FAIL "无法判定" "$out"
teardown

setup "14 SKIP：无背屏组件"
SC_SUB_OK=0
out=$(sc_item_14)
expect SKIP "不适用" "$out"
teardown

setup "14 正常：有背屏组件"
SC_SUB_OK=1
out=$(sc_item_14)
expect PASS "同一判据" "$out"
teardown

# ---------- 第 10 项：PID1 视角的不支持机型 ----------
setup "10 正常：unknown 且 PID1 无 BAA 挂载"
SC_SUB_OK=0
dev_code() { printf 'unknown'; }
out=$(sc_item_10)
expect PASS "PID1 无 BAA 挂载" "$out"
teardown

setup "10 异常：unknown 但 PID1 有 BAA 挂载"
dev_code() { printf 'unknown'; }
printf '100 1 8:1 /x /odm/etc/charger/BAA_config_unknown.json rw - ext4 /dev/block/sda1 rw\n' > "$SC_PID1_MOUNTINFO"
out=$(sc_item_10)
expect FAIL "不受支持却有 BAA 挂载" "$out"
teardown

setup "10 异常：dev_code 为空"
dev_code() { printf ''; }
out=$(sc_item_10)
expect FAIL "dev_code 为空" "$out"
teardown

setup "10 异常：PID1 mountinfo 不可读"
dev_code() { printf 'unknown'; }
SC_PID1_MOUNTINFO="$T/nonexistent.mountinfo"
out=$(sc_item_10)
expect FAIL "无法判定" "$out"
teardown

# ---------- 只读：跑完不落盘 ----------
setup "只读：第 9/11/12 项运行后数据目录无新增文件"
SC_SUB_OK=1
mk_files 1; unlock_on
{ mi_base; mi_target 8:2 /adb/rezhale_toolbox/battery/baa/payload_BAA_config_haotian.json; } > "$SC_PID1_MOUNTINFO"
printf 'rearscreen=1\n' > "$BT_CONF"
mk_rear 1 1 'temp,soc'
printf '%s lsposed overlay ready\n' '2026-11-16 10:05:00' > "$LOGDIR/rearscreen.log"
before=$(find "$DATA_DIR" -type f 2>/dev/null | sort)
sc_item_9 >/dev/null; sc_item_11 >/dev/null; sc_item_12 >/dev/null
after=$(find "$DATA_DIR" -type f 2>/dev/null | sort)
if [ "$before" = "$after" ]; then ok; else bad "只读运行后数据目录发生变化: $(printf '%s' "$after" | tr '\n' '|')"; fi
teardown

# ---------- 集成：真实 battery.sh 下路径随机型变化 ----------
# 用真实 env.sh/battery.sh/selfcheck.sh（只读模式），只把 MODDIR 指向临时模块树，
# 验证第 9 项预期的源/目标来自 dev_code，而不是写死的 haotian 目录。
setup "集成：dev_code=annibale 时第 9 项按 annibale 路径判定"
rm -rf "$T/mod"
mkdir -p "$T/mod/battery/baa" "$T/mod/engine/lib"
printf 'id=rezhale_toolbox\nversion=9.9.9\nversionCode=999\n' > "$T/mod/module.prop"
printf 'dev_code=annibale\nunlock=1\n' > "$T/mod/battery/baa/settings.conf"
for _f in env.sh battery.sh rear.sh selfcheck.sh; do
  ln -s "$REPO/engine/lib/$_f" "$T/mod/engine/lib/$_f"
done
printf '1 0 0:1 / / rw,relatime - rootfs rootfs rw\n' > "$T/pid1.mountinfo"
cat > "$T/mod/engine/probe.sh" <<'EOS'
#!/bin/sh
XJ_READONLY=1
export XJ_READONLY
DIR=$(cd "$(dirname "$0")" && pwd)
. "$DIR/lib/env.sh"
. "$DIR/lib/battery.sh"
. "$DIR/lib/selfcheck.sh"
BT_CONF="$PROBE_CONF"
LOGDIR="$PROBE_LOG"
STATE="$PROBE_STATE"
printf 'dev=%s\nsrc=%s\ntgt=%s\n' "$(dev_code)" "$(baa_src)" "$(baa_tgt 2>/dev/null)"
sc_call_battery_status() { printf '%s\n' 'unlock=1' 'baa=inactive'; }
sc_item_9
EOS
chmod +x "$T/mod/engine/probe.sh"
PROBE_CONF="$T/mod/battery/baa/settings.conf" \
PROBE_LOG="$T/data/log" \
PROBE_STATE="$T/data/state" \
SC_PID1_MOUNTINFO="$T/pid1.mountinfo" \
XJ_READONLY=1 \
sh "$T/mod/engine/probe.sh" > "$T/probe.out" 2>&1
out=$(cat "$T/probe.out")
expect_has "dev=annibale" "$out"
expect_has "payload_BAA_config_annibale.json" "$out"
# 真实 battery.sh 下本机既无 annibale 载荷也无目标节点：应 SKIP 而不是 FAIL
# （不支持机型不得当通用 FAIL），且不能假 PASS。
expect SKIP "解容不适用" "$out"
expect_has "unlock=1" "$out"
teardown

# ---------- 汇总 ----------
printf '\n合计: %d 通过 / %d 失败\n' "$PASS_N" "$FAIL_N"
if [ "$FAIL_N" -ne 0 ]; then
  printf '失败明细:%s\n' "$FAILED"
  exit 1
fi
exit 0
