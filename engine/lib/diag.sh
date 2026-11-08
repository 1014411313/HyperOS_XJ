#!/system/bin/sh
# engine/lib/diag.sh — 日志查看与诊断打包。
# 被 xjctl.sh 在 `log` / `diag` 分支内 source；依赖 env.sh 的 MODDIR/CONFDIR/LOGDIR。
# 只收集日志与非敏感配置：settings.json 脱敏后保留，敏感文件不打包。

# settings.json 脱敏：命中敏感键名的值替换为 "***"，其余原样保留。
diag_sanitize_json() {
  awk '
    {
      line = $0
      if (match(line, /^[[:space:]]*"[^"]+"[[:space:]]*:[[:space:]]*/)) {
        key = substr(line, RSTART, RLENGTH)
        sub(/^[[:space:]]*"/, "", key)
        sub(/"[[:space:]]*:[[:space:]]*$/, "", key)
        lk = tolower(key)
        if (lk ~ /(token|secret|password|passwd|credential|api_?key|private|imei|serial|(^|_)sn($|_))/) {
          prefix = substr(line, 1, RSTART + RLENGTH - 1)
          rest = substr(line, RSTART + RLENGTH)
          comma = (rest ~ /,[[:space:]]*$/) ? "," : ""
          line = prefix "\"***\"" comma
        }
      }
      print line
    }
  ' "$1" > "$2"
}

diag_is_sensitive_name() {
  case "$1" in
    *token*|*secret*|*password*|*passwd*|*credential*|*api?key*|*_key*|*.key|*imei*|*serial*) return 0 ;;
  esac
  return 1
}

# log/ 下按修改时间取最近的一个文件。
diag_recent_log() {
  [ -d "$LOGDIR" ] || return 1
  _newest=""; _mt=-1
  for _f in "$LOGDIR"/*; do
    [ -f "$_f" ] || continue
    _m=$(stat -c %Y "$_f" 2>/dev/null)
    case "$_m" in ''|*[!0-9]*) continue ;; esac
    if [ "$_m" -gt "$_mt" ]; then _mt="$_m"; _newest="$_f"; fi
  done
  [ -n "$_newest" ] || return 1
  printf '%s' "$_newest"
}

diag_log() {
  _n="${1:-200}"
  case "$_n" in ''|*[!0-9]*) echo "行数必须是数字: $_n"; return 2 ;; esac
  _f=$(diag_recent_log) || { echo "日志目录为空: $LOGDIR"; return 1; }
  echo "== 最近日志: $(basename "$_f")（最后 $_n 行）=="
  echo "路径: $_f"
  tail -n "$_n" "$_f" 2>/dev/null
}

# 打包：优先 zip（$.zip），设备无 zip 工具时回退 tar.gz（如实告知扩展名）。
diag_do_zip() {
  _stage="$1"; _out="$2"
  if command -v zip >/dev/null 2>&1; then
    ( cd "$(dirname "$_stage")" && zip -qr "$_out" "$(basename "$_stage")" ) >/dev/null 2>&1 \
      && [ -s "$_out" ] && { echo "zip"; return 0; }
  fi
  for _bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox; do
    [ -x "$_bb" ] || continue
    "$_bb" zip --help >/dev/null 2>&1 || continue
    ( cd "$(dirname "$_stage")" && "$_bb" zip -qr "$_out" "$(basename "$_stage")" ) >/dev/null 2>&1 \
      && [ -s "$_out" ] && { echo "busybox zip"; return 0; }
  done
  return 1
}

diag_do_targz() {
  _stage="$1"; _out="$2"
  if command -v tar >/dev/null 2>&1; then
    ( cd "$(dirname "$_stage")" && tar -czf "$_out" "$(basename "$_stage")" ) >/dev/null 2>&1 \
      && [ -s "$_out" ] && { echo "tar"; return 0; }
  fi
  for _bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox; do
    [ -x "$_bb" ] || continue
    ( cd "$(dirname "$_stage")" && "$_bb" tar -czf "$_out" "$(basename "$_stage")" ) >/dev/null 2>&1 \
      && [ -s "$_out" ] && { echo "busybox tar"; return 0; }
  done
  return 1
}

diag_pack() {
  _stamp=$(date +%Y%m%d-%H%M%S)
  _stage="/data/local/tmp/HyperOS_XJ_diag_$_stamp"
  rm -rf "$_stage" 2>/dev/null
  mkdir -p "$_stage/log" "$_stage/config" 2>/dev/null \
    || { echo "无法创建临时目录: $_stage"; return 1; }

  {
    echo "HyperOS_XJ 诊断包"
    echo "采集时间: $(date '+%F %T')"
    echo "机型    : $(getprop ro.product.model 2>/dev/null) / $(getprop ro.product.device 2>/dev/null)"
    echo "机型代号: $(dev_code 2>/dev/null)"
    echo "模块版本: $(mod_version)"
    echo "内核    : $(uname -r 2>/dev/null)"
    echo "槽位    : $(getprop ro.boot.slot_suffix 2>/dev/null)"
    echo "系统    : Android $(getprop ro.build.version.release 2>/dev/null) (SDK $(getprop ro.build.version.sdk 2>/dev/null))"
  } > "$_stage/device_info.txt" 2>/dev/null

  _n=0
  for _f in "$LOGDIR"/*; do
    [ -f "$_f" ] || continue
    cp -f "$_f" "$_stage/log/" 2>/dev/null && _n=$((_n + 1))
  done

  _cn=0
  for _f in "$CONFDIR"/*; do
    [ -f "$_f" ] || continue
    _b=$(basename "$_f")
    if [ "$_b" = "settings.json" ]; then
      diag_sanitize_json "$_f" "$_stage/config/$_b" 2>/dev/null && _cn=$((_cn + 1))
      continue
    fi
    diag_is_sensitive_name "$_b" && continue
    cp -f "$_f" "$_stage/config/$_b" 2>/dev/null && _cn=$((_cn + 1))
  done

  mkdir -p /sdcard/Download 2>/dev/null
  _zip="/sdcard/Download/HyperOS_XJ_diag_$_stamp.zip"
  _how=$(diag_do_zip "$_stage" "$_zip") || _how=""
  if [ -n "$_how" ]; then
    _sz=$(stat -c %s "$_zip" 2>/dev/null)
    rm -rf "$_stage" 2>/dev/null
    echo "已导出: $_zip"
    echo "大小: ${_sz:-?} 字节   打包: $_how"
    echo "内容: 日志 $_n 个，配置 $_cn 个（settings.json 已脱敏）"
    return 0
  fi

  # 设备无 zip 工具：回退 tar.gz，并按真实扩展名报告。
  _tgz="/sdcard/Download/HyperOS_XJ_diag_$_stamp.tar.gz"
  _how=$(diag_do_targz "$_stage" "$_tgz") || _how=""
  if [ -n "$_how" ]; then
    _sz=$(stat -c %s "$_tgz" 2>/dev/null)
    rm -rf "$_stage" 2>/dev/null
    echo "已导出: $_tgz"
    echo "说明: 设备无 zip 工具，已用 $_how 打包（扩展名 .tar.gz）"
    echo "大小: ${_sz:-?} 字节"
    echo "内容: 日志 $_n 个，配置 $_cn 个（settings.json 已脱敏）"
    return 0
  fi

  echo "打包失败: 设备无 zip/tar 可用；散装文件保留在 $_stage"
  return 1
}
