#!/system/bin/sh
# engine/lib/selfcheck.sh — xjctl selfcheck 的只读自检项。
# 本文件只被 xjctl.sh source，不单独执行。
#
# 约定：每项一行输出 [PASS]/[FAIL]/[HUMAN]/[SKIP]；单项里的任何外部命令失败
#       只让该项 FAIL，不影响其余项（不用 set -e，不提前 return 主流程）。
# 只读：不做功率/温控/BAA/PSS 切换，也不生成诊断包（未实现的动作标 SKIP）。
#
# 设备侧 date 只认 -d '@epoch'（不认 yesterday），awk 无 systime/mktime，
# 所以时间比较统一「date 算基准 + awk 字典序比字符串」，与 retention.sh 一致。
# 设备侧没有 jq/python；status 是「扁平对象、值全字符串」，用 awk 逐字符校验（SC_JSON_AWK）。

SC_TMO=8                       # 单条外部命令超时（秒）：真机上 dumpsys/pm 会卡住
SC_CARD_PKG=com.hyperos.xj
SC_SUB_PKG=com.xiaomi.subscreencenter
SC_REAR_CARD=${SC_REAR_CARD:-/storage/emulated/0/Android/data/com.hyperos.xj/files/rear.conf}
SC_REAR_TMP=${SC_REAR_TMP:-/data/local/tmp/xj_rear.conf}
SC_OLD_MODDIR=/data/adb/modules/HyperOS_XJ
# PID1 视角的挂载表与根目录：selfcheck 进程通常不在 init 的 mount namespace 里，
# 读自己的 /proc/self/mounts 看不到 batteryantiaging 实际能看到的挂载；
# 内容证据（dev:inode/hash/label）一律经 /proc/1/root 读取，不拿自身 namespace 顶替。
# 用 ${VAR-def}（不是 :-）：允许显式置空（离线回归模拟 PID1 视角）。
SC_PID1_MOUNTINFO=${SC_PID1_MOUNTINFO-/proc/1/mountinfo}
SC_PID1_ROOT=${SC_PID1_ROOT-/proc/1/root}

SC_PASS=0
SC_FAIL=0
SC_HUMAN=0
SC_SKIP=0
SC_STATUS=""
SC_STATUS_RC=0
SC_SUB_OK=""
SC_DIR=${DIR:-}
[ -n "$SC_DIR" ] || SC_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)

sc_pass() { SC_PASS=$((SC_PASS + 1)); printf '[PASS] %s %s - %s\n' "$1" "$2" "$3"; }
sc_fail() { SC_FAIL=$((SC_FAIL + 1)); printf '[FAIL] %s %s - %s\n' "$1" "$2" "$3"; }
sc_human() { SC_HUMAN=$((SC_HUMAN + 1)); printf '[HUMAN] %s %s - %s\n' "$1" "$2" "$3"; }
sc_skip() { SC_SKIP=$((SC_SKIP + 1)); printf '[SKIP] %s %s - %s\n' "$1" "$2" "$3"; }

# status JSON 里取字符串字段（取不到给空串）
sc_json_field() {
  printf '%s' "$SC_STATUS" | sed -n 's/.*"'"$1"'":"\([^"]*\)".*/\1/p'
}

# 距今秒数；参数或当前时间不可用时返回非 0（调用方按空处理）
sc_age() {
  _sa_now=$(date +%s 2>/dev/null)
  case "$_sa_now" in ''|*[!0-9]*) return 1 ;; esac
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  printf '%s' "$((_sa_now - $1))"
}

# 当前时间往前 $1 秒的「%F %T」字符串
sc_cutoff() {
  _sc_now=$(date +%s 2>/dev/null)
  case "$_sc_now" in ''|*[!0-9]*) return 0 ;; esac
  date -d "@$((_sc_now - $1))" '+%F %T' 2>/dev/null
}

# 字典序比较（%F %T 的字典序等于时间序）：$1 >= $2 返回 0
sc_ts_ge() {
  awk -v a="$1" -v b="$2" 'BEGIN { exit (a < b) }'
}

# 从 KEY=value 文件读键（末行优先，与 cfg_get 语义一致）
sc_conf_get() {
  grep -a "^$2=" "$1" 2>/dev/null | tail -n 1 | cut -d= -f2-
}

# 子命令封装：回归测试可覆盖同名函数，真机走真实 xjctl。
sc_call_status() { timeout 25 sh "$SC_DIR/xjctl.sh" status 2>/dev/null; }
sc_call_battery_status() { timeout 30 sh "$SC_DIR/xjctl.sh" battery status 2>/dev/null; }

# 背屏支持：与 lib/rear.sh 同一判据（com.xiaomi.subscreencenter 在装）。
# 库已加载时复用 subscreen_probe（只读模式下它不写缓存）；独立运行时退回 pm path。
# 一次探测缓存到 SC_SUB_OK，避免每项 fork pm。
sc_subscreen() {
  case "$SC_SUB_OK" in
    0|1) printf '%s' "$SC_SUB_OK"; return 0 ;;
  esac
  if command -v subscreen_probe >/dev/null 2>&1; then
    SC_SUB_OK=$(subscreen_probe 2>/dev/null)
  elif timeout "$SC_TMO" pm path "$SC_SUB_PKG" 2>/dev/null | grep -q '^package:'; then
    SC_SUB_OK=1
  else
    SC_SUB_OK=0
  fi
  case "$SC_SUB_OK" in 0|1) ;; *) SC_SUB_OK=0 ;; esac
  printf '%s' "$SC_SUB_OK"
}

# ---------- 1 模块已挂载 ----------
sc_item_1() {
  _n="模块已挂载"
  if [ ! -f "$MODDIR/module.prop" ]; then
    sc_fail 1 "$_n" "未找到 $MODDIR/module.prop（模块未安装/未挂载）"
    return 0
  fi
  # 模块 id 优先取 module.prop 的 id（MODDIR 回退到脚本上级目录时 basename 不是 id）
  _id=$(grep -m1 '^id=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2 | tr -d '\r')
  [ -n "$_id" ] || _id=${MODDIR##*/}
  [ -n "$_id" ] || _id=rezhale_toolbox
  _ver=$(mod_version 2>/dev/null)
  _vc=$(grep -m1 '^versionCode=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2 | tr -d '\r')
  if [ -d "/data/adb/modules_update/$_id" ]; then
    sc_fail 1 "$_n" "version=$_ver 但 /data/adb/modules_update/$_id 仍在（升级未生效，需重启）"
    return 0
  fi
  sc_pass 1 "$_n" "version=$_ver versionCode=$_vc，无升级暂存"
}

# ---------- 2 旧 id 残留清零 ----------
sc_item_2() {
  _n="旧 id 残留清零"
  _bad=""
  [ -d "$SC_OLD_MODDIR" ] && _bad="$SC_OLD_MODDIR 仍存在"
  # 目标串必须带完整安装路径：旧模块只装在 /data/adb/modules 下，
  # 只比 HyperOS_XJ/engine 会把任何叫该名字的仓库目录（包括本仓库）当成残留。
  # 脚本正文会出现在 sh -c / timeout 的 cmdline 里，所以模式在子 shell 内拼接，避免自匹配。
  # 命中时回报 pid，便于人确认到底是谁在跑旧路径。
  _hitp=$(timeout "$SC_TMO" sh -c '
    pat="/data/adb/modules/HyperOS""_XJ/engine"
    self=$(readlink /proc/self 2>/dev/null); self=${self#/proc/}
    for d in /proc/[0-9]*; do
      p=${d#/proc/}
      [ "$p" = "$self" ] && continue
      [ "$p" = "$PPID" ] && continue
      if ! c=$(tr "\000" " " < "$d/cmdline" 2>/dev/null); then
        # 进程可能在遍历期间退出；仍存在的进程读失败才代表扫描不完整。
        [ ! -d "$d" ] || failed=1
        continue
      fi
      case "$c" in *"$pat"*) hits="$hits $p" ;; esac
    done
    [ "$failed" != "1" ] || exit 2
    printf "%s" "${hits# }"
  ' 2>/dev/null)
  _hitrc=$?

  # 空输出不一定是「无残留」：超时（124）或扫描失败时同样为空，此时无法判定。
  if [ "$_hitrc" -ne 0 ]; then
    sc_fail 2 "$_n" "${_bad:+$_bad; }进程扫描未能完成（退出码 $_hitrc），无法判定是否有进程跑旧路径"
    return 0
  fi

  if [ -n "$_hitp" ]; then
    _bad="${_bad:+$_bad; }pid 仍在跑旧路径: $_hitp"
  fi
  if [ -n "$_bad" ]; then
    sc_fail 2 "$_n" "$_bad"
  else
    sc_pass 2 "$_n" "无 $SC_OLD_MODDIR，无进程跑旧路径"
  fi
}

# ---------- 3 App 已装且版本一致 ----------
sc_item_3() {
  _n="App 已装且版本一致"
  _p=$(timeout "$SC_TMO" pm path "$SC_CARD_PKG" 2>/dev/null)
  case "$_p" in
    *package:*) ;;
    *) sc_fail 3 "$_n" "pm path $SC_CARD_PKG 无结果（未安装或 pm 不可用）"; return 0 ;;
  esac
  _av=$(timeout 10 dumpsys package "$SC_CARD_PKG" 2>/dev/null | grep -m1 'versionName=' | sed 's/.*versionName=//' | cut -d' ' -f1 | tr -d '\r')
  _mv=$(mod_version 2>/dev/null)
  if [ -z "$_av" ]; then
    sc_fail 3 "$_n" "已安装但读不到 versionName（dumpsys 不可用）"
  elif [ -z "$_mv" ]; then
    sc_fail 3 "$_n" "读不到 module.prop 的 version"
  else
    _a2=${_av#v}; _m2=${_mv#v}
    if [ "$_a2" = "$_m2" ]; then
      sc_pass 3 "$_n" "app=$_av module=$_mv"
    else
      sc_fail 3 "$_n" "app=$_av module=$_mv（版本不一致）"
    fi
  fi
}

# ---------- 4 daemon 存活（必须核对进程身份，不能只看 PID 存在） ----------
# daemon_pid 来自 status，是「当时」写出的 pid；重启后该号可能被系统进程复用，
# 所以还要读 /proc/PID/cmdline，要求其中一个 argv 精确等于本模块 engine/xjdaemon.sh。
sc_item_4() {
  _n="daemon 存活"
  if [ "$SC_STATUS_RC" != "0" ]; then
    sc_fail 4 "$_n" "xjctl status 退出码 $SC_STATUS_RC（超时或失败），取不到可信的 daemon_pid"
    return 0
  fi
  _pid=$(sc_json_field daemon_pid)
  case "$_pid" in
    ''|*[!0-9]*) sc_fail 4 "$_n" "status 里 daemon_pid 为空或非数字（daemon 未运行）"; return 0 ;;
  esac
  if [ ! -d "/proc/$_pid" ]; then
    sc_fail 4 "$_n" "daemon_pid=$_pid 但 /proc/$_pid 不存在（pid 残留）"
    return 0
  fi
  _d1=""
  [ -n "$SC_DIR" ] && _d1="$SC_DIR/xjdaemon.sh"
  _d2="$MODDIR/engine/xjdaemon.sh"
  _cl=$(timeout "$SC_TMO" tr '\000' '\n' < "/proc/$_pid/cmdline" 2>/dev/null)
  _crc=$?
  if [ "$_crc" -ne 0 ]; then
    sc_fail 4 "$_n" "daemon_pid=$_pid，但读不到 /proc/$_pid/cmdline（退出码 $_crc），无法确认进程身份"
    return 0
  fi
  _hit=0; _mp=""; _shown=""
  _old_ifs=$IFS
  set -f
  IFS='
'
  for _a in $_cl; do
    [ -n "$_a" ] || continue
    _shown="${_shown:+$_shown }$_a"
    case "$_a" in
      "$_d1"|"$_d2") _hit=1; _mp="$_a" ;;
    esac
  done
  IFS=$_old_ifs
  set +f
  _shown=$(printf '%.120s' "$_shown")
  if [ "$_hit" = "1" ]; then
    sc_pass 4 "$_n" "daemon_pid=$_pid 的 cmdline 含 $_mp"
  else
    sc_fail 4 "$_n" "daemon_pid=$_pid 的 cmdline 不含本模块 xjdaemon.sh（pid 被复用或进程已换）: $_shown"
  fi
}

# ---------- 5 采样器在出数 ----------
# diag_log.sh 超过 4MiB 后 tail -c 保留末尾，可能把唯一表头截掉（bug-127），
# 所以表头缺失不判失败；但只要表头还在，就必须是 14 列（# + 13 个字段名）。
# 仅新段头不足以证明在出数：还要该段里有 13 列且取值合法的数据行。
sc_item_5() {
  _n="采样器在出数"
  _f="$LOGDIR/diag_samples.log"
  if [ ! -s "$_f" ]; then
    sc_fail 5 "$_n" "无 $_f（采样器未落盘）"
    return 0
  fi
  _st=$(timeout "$SC_TMO" awk '
    /^# soc / { hdr = NF; next }
    /^# [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9]$/ {
      seg = NR; ts = substr($0, 3); good = 0; next
    }
    {
      if (!seg) next
      if (NF != 13) next
      ok = 1
      for (i = 1; i <= 10; i++) {
        v = $i
        if (v == "-") continue
        if (v !~ /^-?[0-9]+$/) { ok = 0; break }
      }
      if (ok) good++
    }
    END { printf "%s\t%s\t%s\t%s\n", (hdr == "" ? "-" : hdr), (seg == "" ? "-" : seg), (ts == "" ? "-" : ts), (seg == "" ? "-" : good) }
  ' "$_f" 2>/dev/null)
  _arc=$?
  if [ "$_arc" -ne 0 ] || [ -z "$_st" ]; then
    sc_fail 5 "$_n" "解析 $_f 未能完成（退出码 $_arc），无法判定"
    return 0
  fi
  _hdr=$(printf '%s\n' "$_st" | cut -f1)
  _seg=$(printf '%s\n' "$_st" | cut -f2)
  _ts=$(printf '%s\n' "$_st" | cut -f3)
  _good=$(printf '%s\n' "$_st" | cut -f4)
  if [ "$_seg" = "-" ]; then
    sc_fail 5 "$_n" "无 '# 时间' 段头（采样未按段落盘）"
    return 0
  fi
  if [ "$_hdr" != "-" ] && [ "$_hdr" != "14" ]; then
    sc_fail 5 "$_n" "表头 ${_hdr} 列（应为 14 列：# + 13 个字段名）"
    return 0
  fi
  if [ "$_good" = "0" ]; then
    sc_fail 5 "$_n" "最新段（$_ts）没有 13 列的合法数据行"
    return 0
  fi
  _now=$(sc_cutoff 0)
  if [ -z "$_now" ]; then
    sc_fail 5 "$_n" "无法取当前时间（date 不可用），无法判定段头时间"
    return 0
  fi
  if awk -v a="$_ts" -v b="$_now" 'BEGIN { exit !(a > b) }'; then
    sc_fail 5 "$_n" "最新段头 $_ts 晚于当前时间 $_now（时钟或数据不可信）"
    return 0
  fi
  _cut=$(sc_cutoff 300)
  _hnote=""
  [ "$_hdr" = "-" ] && _hnote="（表头已被截尾丢弃，按 13 列数据行校验）"
  if [ -z "$_cut" ]; then
    sc_fail 5 "$_n" "无法计算 5 分钟基准（date 不可用）"
  elif sc_ts_ge "$_ts" "$_cut"; then
    sc_pass 5 "$_n" "最新段 $_ts，${_good} 行合法数据${_hnote}"
  else
    sc_fail 5 "$_n" "最新段头 $_ts 已超过 5 分钟（采样停了）"
  fi
}

# ---------- 6 配置完整性 ----------
# 只判两件事：module.prop 的 NUL 字节数为 0；settings.conf 每行可解析为 KEY=value 或注释/空行。
# 空值（KEY=）是合法状态：cycle_fake / term_v / rear_bg 的清除动作就是写入空串。
sc_item_6() {
  _n="配置完整性"
  _bad=""
  if [ ! -f "$BT_CONF" ]; then
    _bad="$BT_CONF 缺失，无法判定"
  else
    _mal=$(awk '
      /^[[:space:]]*(#|$)/ { next }
      { if (index($0, "=") <= 1) printf "%d ", NR }' "$BT_CONF" 2>/dev/null)
    _arc=$?
    if [ "$_arc" -ne 0 ]; then
      _bad="settings.conf 解析未能完成（退出码 $_arc），无法判定"
    else
      _mal=$(printf '%s' "$_mal" | sed 's/ $//')
      [ -n "$_mal" ] && _bad="settings.conf 畸形行: $_mal（既非 KEY=value 也非注释/空行）"
    fi
  fi
  if [ -f "$MODDIR/module.prop" ]; then
    _bytes=$(stat -c %s "$MODDIR/module.prop" 2>/dev/null)
    case "$_bytes" in
      ''|*[!0-9]*)
        _bad="${_bad:+$_bad; }module.prop 字节数读取失败，无法判定 NUL 字节数" ;;
      *)
        _plain=$(tr -d '\000' < "$MODDIR/module.prop" 2>/dev/null | wc -c | tr -d ' ')
        case "$_plain" in ''|*[!0-9]*) _plain=0 ;; esac
        _nul=$((_bytes - _plain))
        [ "$_nul" -gt 0 ] && _bad="${_bad:+$_bad; }module.prop 含 $_nul 个 NUL 字节" ;;
    esac
  fi
  if [ -n "$_bad" ]; then
    sc_fail 6 "$_n" "$_bad"
  else
    sc_pass 6 "$_n" "settings.conf 每行可解析为 KEY=value 或注释/空行，module.prop 无 NUL 字节"
  fi
}

# ---------- 7 留存策略在跑 ----------
# retention.sh 的 RETENTION_INTERVAL=86400，tick 每拍检查是否到期；
# 宽限 3600s 留给守护重启/休眠错过的拍，所以上限 90000s。
# 这个时间戳只证明清理流程按时被调用，不代表清理全部成功。
sc_item_7() {
  _n="留存策略在跑"
  _ts=$(cat "$STATE/retention.ts" 2>/dev/null | tr -d ' \r\n')
  case "$_ts" in
    ''|*[!0-9]*) sc_fail 7 "$_n" "无 $STATE/retention.ts 或内容非法（清理从未执行）"; return 0 ;;
  esac
  _now=$(date +%s 2>/dev/null)
  case "$_now" in
    ''|*[!0-9]*) sc_fail 7 "$_n" "取不到当前时间（date 不可用），无法判定"; return 0 ;;
  esac
  _age=$((_now - _ts))
  if [ "$_age" -lt 0 ]; then
    sc_fail 7 "$_n" "retention.ts=$_ts 晚于当前时间 ${_age#-}s（未来时间戳，不可信）"
  elif [ "$_age" -gt 90000 ]; then
    sc_fail 7 "$_n" "上次清理在 ${_age}s 前（上限 90000s = 间隔 86400s + 宽限 3600s）"
  else
    sc_pass 7 "$_n" "上次清理在 ${_age}s 前（上限 90000s = 间隔 86400s + 宽限 3600s；仅说明清理流程按时执行，不代表清理全部成功）"
  fi
}

# ---------- 8 status JSON 可解析 ----------
# 设备上没有 jq/python；本项目 status 是「扁平对象、值全为字符串」，所以只实现这种形状的
# 严格解析：逐字符校验引号、转义、逗号与结尾，任何非法截断/拼接/尾随逗号都不放过。
SC_JSON_AWK='
BEGIN { n = 0; err = ""; nk = 0 }
{ if (n++) s = s "\n" $0; else s = $0 }
END {
  sub(/^[ \t\r\n]+/, "", s); sub(/[ \t\r\n]+$/, "", s)
  if (length(s) < 2 || substr(s, 1, 1) != "{" || substr(s, length(s), 1) != "}") {
    print "FAIL 首字符必须是 { 且末字符必须是 }（输出被截断或拼接）"; exit 0
  }
  b = substr(s, 2, length(s) - 2); L = length(b); i = 1
  skipws()
  if (i <= L) {
    while (1) {
      skipws()
      if (!pstr()) { printf "FAIL 键解析失败：%s（位置 %d）\n", err, i; exit 0 }
      k = sv
      if (k in seen) { printf "FAIL 重复键：%s\n", k; exit 0 }
      seen[k] = 1; nk++
      skipws()
      if (substr(b, i, 1) != ":") { printf "FAIL 键 %s 后缺冒号（位置 %d）\n", k, i; exit 0 }
      i++
      skipws()
      if (!pstr()) { printf "FAIL 键 %s 的值解析失败：%s（位置 %d）\n", k, err, i; exit 0 }
      skipws()
      if (i > L) break
      if (substr(b, i, 1) != ",") { printf "FAIL 键 %s 的值后缺逗号或对象结束（位置 %d）\n", k, i; exit 0 }
      i++
      if (i > L) { print "FAIL 对象以逗号结尾（尾随逗号）"; exit 0 }
    }
  }
  miss = ""
  split("module_version device_code daemon_pid", need, " ")
  for (j = 1; j <= 3; j++) if (!(need[j] in seen)) miss = miss " " need[j]
  if (miss != "") { printf "FAIL 缺少必需键:%s\n", miss; exit 0 }
  printf "OK %d\n", nk
}
function skipws(   c) {
  while (i <= L) {
    c = substr(b, i, 1)
    if (c == " " || c == "\t" || c == "\r" || c == "\n") i++
    else break
  }
}
function hex4(h,   n, c) {
  if (length(h) != 4) return -1
  if (h !~ /^[0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f]$/) return -1
  n = 0
  while (length(h) > 0) {
    c = tolower(substr(h, 1, 1))
    h = substr(h, 2)
    if (c >= "0" && c <= "9") n = n * 16 + (c + 0)
    else n = n * 16 + (index("abcdef", c) + 9)
  }
  return n
}
# 码点 → UTF-8 字节串（POSIX awk 无编解码，按字节手拼）
function u2utf(cp,   b1, b2, b3, b4) {
  if (cp < 128) return sprintf("%c", cp)
  if (cp < 2048) return sprintf("%c%c", 192 + int(cp / 64), 128 + cp % 64)
  if (cp < 65536) return sprintf("%c%c%c", 224 + int(cp / 4096), 128 + int(cp / 64) % 64, 128 + cp % 64)
  return sprintf("%c%c%c%c", 240 + int(cp / 262144), 128 + int(cp / 4096) % 64, 128 + int(cp / 64) % 64, 128 + cp % 64)
}
# 解析一个 JSON 字符串字面量，把结果规范化（解码转义）后放进 sv。
# 键必须完整规范化：\\u0062 与 b、\\/ 与 /、原始中文与 \\u4e2d 都应得到同一字节串，
# 否则重复键漏检、不同键误判重复。
function pstr(   c, h, cp, lo) {
  sv = ""
  if (substr(b, i, 1) != "\"") { err = "期望字符串开始"; return 0 }
  i++
  while (i <= L) {
    c = substr(b, i, 1)
    if (c == "\\") {
      i++
      if (i > L) { err = "转义序列被截断"; return 0 }
      c = substr(b, i, 1)
      if (c == "u") {
        h = substr(b, i + 1, 4)
        cp = hex4(h)
        if (cp < 0) { err = "\\u 转义非法"; return 0 }
        i += 5
        if (cp >= 55296 && cp <= 56319) {
          # high surrogate：紧跟 low surrogate 才能组成一个码点
          if (substr(b, i, 1) == "\\" && substr(b, i + 1, 1) == "u") {
            lo = hex4(substr(b, i + 2, 4))
            if (lo >= 56320 && lo <= 57343) {
              sv = sv u2utf(65536 + (cp - 55296) * 1024 + (lo - 56320))
              i += 6
              continue
            }
          }
          sv = sv "\\u" h   # 孤立代理：保留字面，不把不同写法混为一谈
          continue
        }
        if (cp >= 56320 && cp <= 57343) { sv = sv "\\u" h; continue }
        if (cp == 0) { sv = sv "\\u0000"; continue }
        sv = sv u2utf(cp)
        continue
      }
      if (c == "\"") { sv = sv "\""; i++; continue }
      if (c == "\\") { sv = sv "\\"; i++; continue }
      if (c == "/") { sv = sv "/"; i++; continue }
      if (c == "b") { sv = sv sprintf("%c", 8); i++; continue }
      if (c == "f") { sv = sv sprintf("%c", 12); i++; continue }
      if (c == "n") { sv = sv sprintf("%c", 10); i++; continue }
      if (c == "r") { sv = sv sprintf("%c", 13); i++; continue }
      if (c == "t") { sv = sv sprintf("%c", 9); i++; continue }
      err = "非法转义 \\" c
      return 0
    }
    if (c == "\"") { i++; return 1 }
    if (c < " ") { err = "字符串内含未转义控制字符"; return 0 }
    sv = sv c
    i++
  }
  err = "字符串未闭合"; return 0
}
'

sc_item_8() {
  _n="status JSON 可解析"
  if [ "$SC_STATUS_RC" != "0" ]; then
    sc_fail 8 "$_n" "xjctl status 退出码 $SC_STATUS_RC（超时或失败），无法校验 JSON"
    return 0
  fi
  if [ -z "$SC_STATUS" ]; then
    sc_fail 8 "$_n" "xjctl status 无输出"
    return 0
  fi
  _json=$(printf '%s' "$SC_STATUS" | awk "$SC_JSON_AWK" 2>/dev/null)
  _jrc=$?
  case "$_json" in
    'OK '*)
      sc_pass 8 "$_n" "完整 flat JSON（${_json#OK } 个键），含 module_version/device_code/daemon_pid" ;;
    'FAIL '*)
      sc_fail 8 "$_n" "${_json#FAIL }" ;;
    *)
      sc_fail 8 "$_n" "JSON 校验未能完成（awk 退出码 $_jrc），无法判定" ;;
  esac
}

# ---------- 9 解容状态（必须在 PID1 挂载视角求值） ----------
# battery status 只回答「源与目标内容/标签是否一致」，内容一致不等于已 bind 挂载。
# 判据分两层，缺任一层只能报「无法判定」，不假 PASS：
#   结构：/proc/1/mountinfo 里目标必须是挂载点，且 (major:minor, root) 等于
#         「源所在挂载点的设备号 + 该挂载点 fs root + 源相对该挂载点的路径」。
#         /data 独立挂载时 root 是 /adb/... 而不是绝对路径，必须先求源所属挂载点；
#   内容：用 /proc/1/root 前缀读 PID1 视角下的源与目标，dev:inode 必须相同
#         （bind 不复制 inode），再核 sha256 与 SELinux 标签。
#         自身 namespace 的 stat 不能代表 PID1 视角，所以一律走 /proc/1/root。
sc_pid1_path() { printf '%s%s' "$SC_PID1_ROOT" "$1"; }

sc_pid1_stat() { stat -c '%d:%i' "$(sc_pid1_path "$1")" 2>/dev/null; }

sc_pid1_hash() { sha256sum "$(sc_pid1_path "$1")" 2>/dev/null | awk '{print $1}'; }

sc_pid1_label() { stat -c %C "$(sc_pid1_path "$1")" 2>/dev/null; }

# 在 PID1 mountinfo 里找包含 $1 的最长前缀挂载点，输出 "dev<TAB>fsroot<TAB>mountpoint"
sc_mi_owner() {
  printf '%s\n' "$_mib" | awk -v p="$1" '
    {
      mp = $5
      if (mp == "/") hit = (substr(p, 1, 1) == "/")
      else hit = (p == mp || substr(p, 1, length(mp) + 1) == mp "/")
      if (hit && length(mp) >= bestlen) { bestlen = length(mp); dev = $3; root = $4; bmp = mp }
    }
    END { if (bestlen == 0) print "none"; else printf "%s\t%s\t%s\n", dev, root, bmp }
  '
}

# bind 目标的 root = 源挂载点的 fs root + 源相对该挂载点的路径（连续斜杠归一）
sc_mi_expect_root() {
  _p=$1; _mp=$2; _r=$3
  if [ "$_mp" = "/" ]; then _sfx=$_p; else _sfx=${_p#"$_mp"}; fi
  printf '%s%s' "$_r" "$_sfx" | awk '{ gsub(/\/\/+/, "/"); print }'
}
sc_item_9() {
  _n="解容状态（PID1）"
  if ! command -v baa_src >/dev/null 2>&1 || ! command -v baa_tgt >/dev/null 2>&1; then
    sc_fail 9 "$_n" "battery 库未加载（baa_src/baa_tgt 不可用），无法确定预期挂载源/目标"
    return 0
  fi
  _dev=$(dev_code 2>/dev/null)
  if [ -z "$_dev" ]; then
    sc_fail 9 "$_n" "dev_code 为空，无法定位机型（无法判定，与 unknown 不同）"
    return 0
  fi
  _src=$(baa_src 2>/dev/null)
  _tgt=$(baa_tgt 2>/dev/null)
  if [ -z "$_src" ] || [ -z "$_tgt" ]; then
    sc_fail 9 "$_n" "无法确定 BAA 源/目标路径（dev_code=$_dev）"
    return 0
  fi
  # 机型未识别，或本机既无载荷也无目标节点：解容不适用，SKIP 并写明 unlock 现值。
  if [ ! -f "$_src" ] && [ ! -f "$_tgt" ]; then
    _un0=""
    if _bs0=$(sc_call_battery_status); then
      _un0=$(printf '%s\n' "$_bs0" | grep -m1 '^unlock=' | cut -d= -f2)
    fi
    sc_skip 9 "$_n" "dev_code=$_dev 无 BAA 载荷与目标节点，解容不适用（unlock=${_un0:-未知}）"
    return 0
  fi
  _mi="$SC_PID1_MOUNTINFO"
  if [ ! -r "$_mi" ]; then
    sc_fail 9 "$_n" "读不到 $_mi（PID1 挂载视角不可用），无法判定解容挂载"
    return 0
  fi
  _mib=$(timeout "$SC_TMO" cat "$_mi" 2>/dev/null)
  _mirc=$?
  if [ "$_mirc" -ne 0 ] || [ -z "$_mib" ]; then
    sc_fail 9 "$_n" "读取 $_mi 失败（退出码 $_mirc），无法判定解容挂载"
    return 0
  fi
  _bs=$(sc_call_battery_status)
  _brc=$?
  if [ "$_brc" -ne 0 ]; then
    sc_fail 9 "$_n" "battery status 退出码 $_brc（PID1 跳转失败或超时），无法判定"
    return 0
  fi
  _un=$(printf '%s\n' "$_bs" | grep -m1 '^unlock=' | cut -d= -f2)
  _baa=$(printf '%s\n' "$_bs" | grep -m1 '^baa=' | cut -d= -f2)
  case "$_un" in
    0|1) ;;
    '') sc_fail 9 "$_n" "battery status 无 unlock= 输出，无法判定"; return 0 ;;
    *) sc_fail 9 "$_n" "unlock=$_un 非法（只接受 0/1）"; return 0 ;;
  esac
  case "$_baa" in
    active|inactive) ;;
    '') sc_fail 9 "$_n" "battery status 无 baa= 输出，无法判定"; return 0 ;;
    *) sc_fail 9 "$_n" "baa=$_baa 非法（只接受 active/inactive）"; return 0 ;;
  esac
  # mountinfo 里是解析过 symlink 的真实路径
  _srcr=$(readlink -f "$_src" 2>/dev/null)
  _tgtr=$(readlink -f "$_tgt" 2>/dev/null)
  [ -n "$_srcr" ] || _srcr=$_src
  [ -n "$_tgtr" ] || _tgtr=$_tgt

  # 目标必须确实是 PID1 的挂载点（同路径可能多层叠加，取最后一条=栈顶）
  _trows=$(printf '%s\n' "$_mib" | awk -v t="$_tgtr" -v t2="$_tgt" '
    $5 == t || $5 == t2 { print $3 "\t" $4 "\t" $5 }')
  if [ -z "$_trows" ]; then
    if [ "$_un" = "1" ]; then
      sc_fail 9 "$_n" "unlock=1 但 PID1 mountinfo 里 $_tgtr 不是挂载点（baa=$_baa 只说明内容/标签一致，不等于已 bind 挂载）"
    else
      sc_pass 9 "$_n" "unlock=0，PID1 mountinfo 中 $_tgtr 不是挂载点（baa=$_baa）"
    fi
    return 0
  fi
  _tline=$(printf '%s\n' "$_trows" | tail -n 1)
  _tdev=$(printf '%s' "$_tline" | cut -f1)
  _troot=$(printf '%s' "$_tline" | cut -f2)

  # 结构判据：源所属挂载点的设备号 + fs root + 源相对路径 == 目标行的 (dev, root)
  _struct="unknown"; _exp=""; _sdev=""
  _sown=$(sc_mi_owner "$_srcr")
  if [ "$_sown" != "none" ]; then
    _sdev=$(printf '%s' "$_sown" | cut -f1)
    _sroot=$(printf '%s' "$_sown" | cut -f2)
    _smp=$(printf '%s' "$_sown" | cut -f3)
    _exp=$(sc_mi_expect_root "$_srcr" "$_smp" "$_sroot")
    if [ "$_tdev" = "$_sdev" ] && [ "$_troot" = "$_exp" ]; then
      _struct="ok"
    else
      _struct="bad"
    fi
  fi

  # 内容判据：PID1 视角下源与目标必须是同一 inode
  _tstat=$(sc_pid1_stat "$_tgtr")
  _sstat=$(sc_pid1_stat "$_srcr")
  _ino="unknown"
  if [ -n "$_tstat" ] && [ -n "$_sstat" ]; then
    if [ "$_tstat" = "$_sstat" ]; then _ino="ok"; else _ino="bad"; fi
  fi

  if [ "$_ino" = "bad" ]; then
    sc_fail 9 "$_n" "PID1 视角 $_tgtr 与源 $_srcr 不是同一 inode（${_tstat:-读不到} vs ${_sstat:-读不到}），挂载来源不是预期载荷"
    return 0
  fi
  if [ "$_struct" = "bad" ]; then
    if [ "$_ino" = "ok" ]; then
      sc_fail 9 "$_n" "PID1 mountinfo 结构不符（目标 ${_tdev}:${_troot}，预期 ${_sdev}:${_exp}），但 inode 相同，无法判定来源"
    else
      sc_fail 9 "$_n" "PID1 里 $_tgtr 的挂载来源不是预期载荷：${_tdev}:${_troot}（预期 ${_sdev}:${_exp}）"
    fi
    return 0
  fi
  if [ "$_struct" = "unknown" ] || [ "$_ino" = "unknown" ]; then
    sc_fail 9 "$_n" "PID1 mountinfo 有 $_tgtr 挂载，但无法在 PID1 视角读取源/目标（${_sstat:-读不到}/${_tstat:-读不到}），无法确认 bind 来源"
    return 0
  fi

  _ss=$(stat -c %s "$(sc_pid1_path "$_srcr")" 2>/dev/null)
  case "$_ss" in ''|*[!0-9]*) _ss=0 ;; esac
  _sh=$(sc_pid1_hash "$_srcr")
  _th=$(sc_pid1_hash "$_tgtr")
  if [ "$_ss" -le 8 ] || [ -z "$_sh" ]; then
    sc_fail 9 "$_n" "源 $_srcr 为空或读不到 sha256（无法确认载荷）"
    return 0
  fi
  if [ "$_th" != "$_sh" ]; then
    sc_fail 9 "$_n" "PID1 视角目标与源内容不一致（hash ${_th:-读不到} vs $(printf '%.12s' "$_sh")）"
    return 0
  fi
  _sl=$(sc_pid1_label "$_srcr")
  _tl=$(sc_pid1_label "$_tgtr")
  if [ -z "$_sl" ]; then
    sc_fail 9 "$_n" "读不到源 SELinux 标签，无法确认标签"
    return 0
  fi
  case "$_sl" in
    "$BAA_LABEL"|"$_tl") ;;
    *) sc_fail 9 "$_n" "源标签 $_sl 既不是 $BAA_LABEL 也不等于目标标签 ${_tl:-空}"; return 0 ;;
  esac
  if [ "$_un" = "1" ]; then
    sc_pass 9 "$_n" "unlock=1，PID1 mountinfo 确认 $_tgtr 是挂载点且来源为 $_srcr（${_tdev}:${_troot}，inode=$_tstat，hash=$(printf '%.12s' "$_sh")）"
  else
    sc_fail 9 "$_n" "unlock=0（解容关闭）但 PID1 仍挂载 $_tgtr <- $_srcr，应执行 battery unlock off 卸载"
  fi
}

# ---------- 10 不支持机型不乱挂 ----------
sc_item_10() {
  _n="不支持机型不乱挂"
  _dev=$(dev_code 2>/dev/null)
  if [ -z "$_dev" ]; then
    sc_fail 10 "$_n" "dev_code 为空，无法判定机型是否受支持"
    return 0
  fi
  case " $SUPPORTED_DEVS " in
    *" $_dev "*)
      sc_pass 10 "$_n" "dev_code=$_dev 在支持列表内，本条不适用"
      return 0 ;;
  esac
  # 与第9项同一视角：PID1 能看到而我们看不到的挂载，才是 batteryantiaging 真正读到的。
  _mi="$SC_PID1_MOUNTINFO"
  if [ ! -r "$_mi" ]; then
    sc_fail 10 "$_n" "读不到 $_mi（PID1 挂载视角不可用），无法判定是否有 BAA 挂载"
    return 0
  fi
  _mnt=$(timeout "$SC_TMO" grep -a 'BAA_config_' "$_mi" 2>/dev/null)
  _mrc=$?
  case "$_mrc" in
    0|1) ;;
    *)
      sc_fail 10 "$_n" "读取 $_mi 失败（grep 退出码 $_mrc，2=读取失败 124=超时），无法判定是否有 BAA 挂载"
      return 0 ;;
  esac
  _mnt=$(printf '%s\n' "$_mnt" | head -n 1)
  if [ -n "$_mnt" ]; then
    sc_fail 10 "$_n" "dev_code=${_dev:-空} 不受支持却有 BAA 挂载: $(printf '%s' "$_mnt" | cut -c1-80)"
  elif [ "$_dev" = "unknown" ]; then
    sc_pass 10 "$_n" "dev_code=unknown 且 PID1 无 BAA 挂载"
  else
    sc_fail 10 "$_n" "dev_code=${_dev:-空} 不在支持列表，且未归一为 unknown"
  fi
}

# ---------- 11 背屏配置贯通 ----------
# 无背屏：整组背屏项 SKIP（不是 PASS，避免把「不适用」算成通过）。
# 启用：两份配置都要在，enabled 必须为 1，keep_on/fields 必须存在且合法，两副本一致。
# 关闭：只要求没有 enabled=1 残留，不要求两份文件存在。
sc_item_11() {
  _n="背屏配置贯通"
  if [ "$(sc_subscreen)" != "1" ]; then
    sc_skip 11 "$_n" "无 $SC_SUB_PKG（该机型无背屏），背屏相关项不适用"
    return 0
  fi
  _on=$(cfg_get rearscreen 0)
  if [ "$_on" != "1" ]; then
    _left=""
    grep -q '^enabled=1' "$SC_REAR_CARD" 2>/dev/null && _left="$SC_REAR_CARD"
    grep -q '^enabled=1' "$SC_REAR_TMP" 2>/dev/null && _left="${_left:+$_left }$SC_REAR_TMP"
    if [ -n "$_left" ]; then
      sc_fail 11 "$_n" "rearscreen=0 但仍有 enabled=1 残留: $_left"
    else
      sc_pass 11 "$_n" "rearscreen=0，两份配置无 enabled=1 残留"
    fi
    return 0
  fi
  if [ ! -f "$SC_REAR_CARD" ] || [ ! -f "$SC_REAR_TMP" ]; then
    _miss=""
    [ -f "$SC_REAR_CARD" ] || _miss="$SC_REAR_CARD"
    [ -f "$SC_REAR_TMP" ] || _miss="${_miss:+$_miss }$SC_REAR_TMP"
    sc_fail 11 "$_n" "rearscreen=1 但缺失: $_miss（配置丢失）"
    return 0
  fi
  _bad=""
  for _k in enabled keep_on fields; do
    _va=$(sc_conf_get "$SC_REAR_CARD" "$_k")
    _vb=$(sc_conf_get "$SC_REAR_TMP" "$_k")
    case "$_k" in
      enabled)
        [ "$_va" = "1" ] || _bad="${_bad:+$_bad; }card enabled=${_va:-空}（应为 1）"
        [ "$_vb" = "1" ] || _bad="${_bad:+$_bad; }tmp enabled=${_vb:-空}（应为 1）"
        ;;
      keep_on)
        case "$_va" in 0|1) ;; *) _bad="${_bad:+$_bad; }card keep_on=${_va:-空}（应为 0/1）" ;; esac
        case "$_vb" in 0|1) ;; *) _bad="${_bad:+$_bad; }tmp keep_on=${_vb:-空}（应为 0/1）" ;; esac
        ;;
      fields)
        [ -n "$_va" ] || _bad="${_bad:+$_bad; }card fields 为空"
        [ -n "$_vb" ] || _bad="${_bad:+$_bad; }tmp fields 为空"
        ;;
    esac
    [ "$_va" = "$_vb" ] || _bad="${_bad:+$_bad; }$_k 两副本不一致（card=${_va:-空} tmp=${_vb:-空}）"
  done
  if [ -n "$_bad" ]; then
    sc_fail 11 "$_n" "$_bad"
  else
    sc_pass 11 "$_n" "rearscreen=1，两份 enabled=1、keep_on 合法、fields 非空且一致"
  fi
}

# ---------- 12 背屏引擎动作轨迹 ----------
# 只看最近一条动作轨迹：启用时必须是 ready；关闭时不要求 ready，
# 也不把历史 ready / 历史失败当成当前结论，只如实报出最近一条是什么。
sc_item_12() {
  _n="背屏引擎动作轨迹"
  if [ "$(sc_subscreen)" != "1" ]; then
    sc_skip 12 "$_n" "无 $SC_SUB_PKG（该机型无背屏），背屏相关项不适用"
    return 0
  fi
  _on=$(cfg_get rearscreen 0)
  _f="$LOGDIR/rearscreen.log"
  _last=""
  if [ -s "$_f" ]; then
    _t=$(timeout "$SC_TMO" tail -n 200 "$_f" 2>/dev/null)
    _trc=$?
    if [ "$_trc" -ne 0 ]; then
      sc_fail 12 "$_n" "读取 $_f 失败（退出码 $_trc），无法判定"
      return 0
    fi
    _last=$(printf '%s\n' "$_t" \
      | grep -aE 'lsposed overlay ready|lsposed overlay disabled|install failed|data dir unavailable|config write failed' \
      | tail -n 1)
  fi
  _shown=$(printf '%s' "$_last" | cut -c1-80)
  if [ "$_on" = "1" ]; then
    case "$_last" in
      *'lsposed overlay ready'*)
        sc_pass 12 "$_n" "rearscreen=1，最近轨迹: $_shown" ;;
      '')
        sc_fail 12 "$_n" "rearscreen=1 但最近 200 行无启用/关闭/失败轨迹" ;;
      *)
        sc_fail 12 "$_n" "rearscreen=1 但最近轨迹不是启用成功: $_shown" ;;
    esac
  else
    case "$_last" in
      *'lsposed overlay disabled'*)
        sc_pass 12 "$_n" "rearscreen=0，最近轨迹为关闭: $_shown" ;;
      '')
        sc_pass 12 "$_n" "rearscreen=0，最近 200 行无背屏动作轨迹" ;;
      *)
        sc_pass 12 "$_n" "rearscreen=0，最近轨迹为历史动作: $_shown（关闭状态不要求 ready）" ;;
    esac
  fi
}

# ---------- 13 背屏服务存活 ----------
# 关闭时不要求 RearService。启用时看 dumpsys 的 ServiceRecord：真实 Android 上运行中的
# 是 app=ProcessRecord{...}，已停是 app=null（没有 isStopped 字段）。
# 多条 ServiceRecord 时只要有一处不是运行太，就不能算 PASS（旧记录不能充当当前状态）。
# FGS 可能因后台限制没起来，但 overlay 由 LSPosed hook 在 subscreencenter 进程内绘制、
# 不依赖 FGS，面板可能仍正常：这种情况下判 HUMAN 交人眼，不 FAIL、不臆造同步已坏。
# dumpsys 本身不可用/无权限仍判 FAIL：那是无法判定，不是「服务没跑」。
sc_item_13() {
  _n="背屏服务存活"
  if [ "$(sc_subscreen)" != "1" ]; then
    sc_skip 13 "$_n" "无 $SC_SUB_PKG（该机型无背屏），背屏相关项不适用"
    return 0
  fi
  _on=$(cfg_get rearscreen 0)
  if [ "$_on" != "1" ]; then
    sc_skip 13 "$_n" "rearscreen=0（未启用），不要求 RearService 运行"
    return 0
  fi
  _d=$(timeout 15 dumpsys activity services "$SC_CARD_PKG" 2>/dev/null)
  _drc=$?
  if [ "$_drc" -ne 0 ] || [ -z "$_d" ]; then
    sc_fail 13 "$_n" "dumpsys activity services 无输出（退出码 $_drc，不可用或无权限），无法判定"
    return 0
  fi
  _st=$(printf '%s\n' "$_d" | awk '
    function check() {
      if (rec ~ /RearService/) {
        n++
        if (rec ~ /app=ProcessRecord/) run++
        else idle++
      }
      rec = ""
    }
    /^[[:space:]]*\* ServiceRecord\{/ { if (rec != "") check(); rec = $0; next }
    { if (rec != "") rec = rec "\n" $0 }
    END {
      if (rec != "") check()
      if (n == 0) print "none"
      else if (run > 0 && idle == 0) print "running"
      else print "notrunning"
    }
  ')
  if [ "$_st" = "running" ]; then
    sc_pass 13 "$_n" "dumpsys 中存在运行中的 $SC_CARD_PKG RearService"
  else
    sc_human 13 "$_n" "dumpsys 未见运行中的 RearService（$_st）；overlay 由 LSPosed hook 在 $SC_SUB_PKG 进程内提供、不依赖 FGS，后台限制可能让 FGS 起不来而面板仍正常，请人眼确认"
  fi
}

# ---------- 14 背屏设备支持 ----------
sc_item_14() {
  _n="背屏设备支持"
  if [ "$(sc_subscreen)" = "1" ]; then
    sc_pass 14 "$_n" "pm path $SC_SUB_PKG 有结果（与 lib/rear.sh 同一判据）"
  else
    sc_skip 14 "$_n" "无 $SC_SUB_PKG（该机型无背屏），背屏相关项不适用"
  fi
}

selfcheck_main() {
  _mv=$(mod_version 2>/dev/null)
  printf '自检目标: module=%s dir=%s\n' "${_mv:-未知}" "$MODDIR"
  # 只读：下面的 status / battery status 子进程都不得落盘。必须 export（battery 会 exec
  # nsenter 重开进程，普通赋值传不过去）。xjctl.sh 在 source lib 前已按 selfcheck 设过一次，
  # 这里再 export 保证任何调用路径都继承。
  export XJ_READONLY=1
  # 一次 status 供第 4、8 项共用；退出码单独记录，超时/失败由这两项各自判 FAIL。
  SC_STATUS=$(sc_call_status)
  SC_STATUS_RC=$?
  _sup=$(sc_subscreen)

  sc_item_1
  sc_item_2
  sc_item_3
  sc_item_4
  sc_item_5
  sc_item_6
  sc_item_7
  sc_item_8
  sc_item_9
  sc_item_10
  sc_item_11
  sc_item_12
  sc_item_13
  sc_item_14

  if [ "$_sup" = "1" ]; then
    sc_human 15 "LSPosed 启用与作用域" "人眼确认 LSPosed 已启用 $SC_CARD_PKG，作用域只勾 $SC_SUB_PKG（无自动查询接口）"
    sc_human 16 "背屏面板实际表现" "人眼确认 rear on 后面板出现、rear off 后 2 秒内隐藏、原生时钟不黑"
  else
    sc_skip 15 "LSPosed 启用与作用域" "无 $SC_SUB_PKG（该机型无背屏），不适用"
    sc_skip 16 "背屏面板实际表现" "无 $SC_SUB_PKG（该机型无背屏），不适用"
  fi
  sc_human 17 "充电阶段与双电芯口径" "人眼确认充电阶段文字、双电芯电压/电流口径是否合理"
  sc_human 18 "重启后复检" "重启后再跑 1-14，面板表现仍按第 16 项人眼确认"

  sc_skip 19 "功率档切换" "未实现（自检保持只读，不切功率档）"
  sc_skip 20 "温控档切换" "未实现（自检保持只读，不切温控档）"
  sc_skip 21 "BAA/PSS 开关" "未实现（自检保持只读，不切解容/省电服务）"
  sc_skip 22 "诊断包生成" "未实现（自检保持只读，不往存储写包）"

  printf '合计: %d PASS / %d FAIL / %d HUMAN / %d SKIP\n' "$SC_PASS" "$SC_FAIL" "$SC_HUMAN" "$SC_SKIP"
  [ "$SC_FAIL" -eq 0 ] || return 3
  return 0
}
