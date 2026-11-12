#!/system/bin/sh
# engine/lib/retention.sh — 诊断数据保留策略。
#
# 目标：手机端所有对诊断有用的数据只保留最近 1 天，每天清理一次；
#       诊断包因此天然带着「最近一天」的完整数据，便于定位问题。
#
# 设备能力实测（toybox）：date 支持 -d '@epoch'，但不认 yesterday/-1 day；
# awk 没有 systime/mktime。所以 cutoff 用 date 从 epoch 算出「24 小时前」的
# 时间字符串，再按字典序与行首时间戳比较（%F %T 的字典序等于时间序）。

RETENTION_TS="$STATE/retention.ts"
RETENTION_INTERVAL=86400   # 清理频率：每天一次
RETENTION_MAX_AGE=86400    # 数据保留时长：1 天

# 24 小时前的时间字符串（%F %T），失败时输出空串表示本次跳过。
retention_cutoff() {
  _rc_now=$(date +%s 2>/dev/null)
  case "$_rc_now" in ''|*[!0-9]*) return 0 ;; esac
  date -d "@$((_rc_now - RETENTION_MAX_AGE))" '+%F %T' 2>/dev/null
}

# 普通日志：行首是「YYYY-MM-DD HH:MM:SS」，早于 cutoff 的行丢弃；非时间戳行保留。
retention_trim_lines() {
  _rt_f="$1" _rt_cut="$2"
  [ -s "$_rt_f" ] || return 0
  awk -v cut="$_rt_cut" '
    {
      if (length($0) >= 19 && substr($0, 5, 1) == "-" && substr($0, 11, 1) == " ") {
        if (substr($0, 1, 19) >= cut) print
      } else {
        print
      }
    }
  ' "$_rt_f" > "$_rt_f.new.$$" 2>/dev/null || { rm -f "$_rt_f.new.$$"; return 0; }
  mv -f "$_rt_f.new.$$" "$_rt_f" 2>/dev/null || rm -f "$_rt_f.new.$$" 2>/dev/null
  return 0
}

# 采样日志：结构是「# 表头」+ 若干「# 时间」段头 + 数据行；按段头时间整体保留或丢弃。
retention_trim_samples() {
  _rs_f="$1" _rs_cut="$2"
  [ -s "$_rs_f" ] || return 0
  awk -v cut="$_rs_cut" '
    BEGIN { hdr = 0; keep = 0 }
    /^# soc / { header = $0; next }
    /^#/ {
      keep = (substr($0, 3) >= cut)
      if (keep && !hdr) { print header; hdr = 1 }
      if (keep) print
      next
    }
    { if (keep) print }
  ' "$_rs_f" > "$_rs_f.new.$$" 2>/dev/null || { rm -f "$_rs_f.new.$$"; return 0; }
  mv -f "$_rs_f.new.$$" "$_rs_f" 2>/dev/null || rm -f "$_rs_f.new.$$" 2>/dev/null
  return 0
}

# 本地诊断包与问题截图：超过保留期的删掉，避免长期占空间与留存隐私数据。
retention_clean_local() {
  _rc_now=$(date +%s 2>/dev/null)
  case "$_rc_now" in ''|*[!0-9]*) return 0 ;; esac
  for _rc_d in /sdcard/Download /storage/emulated/0/Download; do
    [ -d "$_rc_d" ] || continue
    for _rc_f in "$_rc_d"/rezhale_toolbox_diag_*; do
      [ -f "$_rc_f" ] || continue
      _rc_m=$(stat -c %Y "$_rc_f" 2>/dev/null)
      case "$_rc_m" in ''|*[!0-9]*) continue ;; esac
      [ $((_rc_now - _rc_m)) -ge "$RETENTION_MAX_AGE" ] && rm -f "$_rc_f" 2>/dev/null
    done
  done
  for _rc_d in /data/user/0/com.hyperos.xj/files/diag /data/user/0/com.hyperos.xj/files/share \
               "$DATA_DIR/state/diag_pics"; do
    [ -d "$_rc_d" ] || continue
    for _rc_f in "$_rc_d"/*; do
      [ -f "$_rc_f" ] || continue
      _rc_m=$(stat -c %Y "$_rc_f" 2>/dev/null)
      case "$_rc_m" in ''|*[!0-9]*) continue ;; esac
      [ $((_rc_now - _rc_m)) -ge "$RETENTION_MAX_AGE" ] && rm -f "$_rc_f" 2>/dev/null
    done
  done
  return 0
}

retention_run() {
  _rr_cut=$(retention_cutoff)
  [ -n "$_rr_cut" ] || return 0
  for _rr_f in "$LOGDIR"/*.log; do
    [ -f "$_rr_f" ] || continue
    case "${_rr_f##*/}" in
      diag_samples.log) retention_trim_samples "$_rr_f" "$_rr_cut" ;;
      *) retention_trim_lines "$_rr_f" "$_rr_cut" ;;
    esac
  done
  retention_clean_local
  return 0
}

# 每拍可调用：内部按时间戳判断是否到期，未到期直接返回（只多一次 cat + date）。
retention_tick() {
  _rt_now=$(date +%s 2>/dev/null)
  case "$_rt_now" in ''|*[!0-9]*) return 0 ;; esac
  _rt_last=$(cat "$RETENTION_TS" 2>/dev/null)
  case "$_rt_last" in ''|*[!0-9]*) _rt_last=0 ;; esac
  [ $((_rt_now - _rt_last)) -ge "$RETENTION_INTERVAL" ] || return 0
  retention_run
  printf '%s' "$_rt_now" > "$RETENTION_TS.tmp" 2>/dev/null \
    && mv -f "$RETENTION_TS.tmp" "$RETENTION_TS" 2>/dev/null
  return 0
}
