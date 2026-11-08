#!/system/bin/sh
# engine/lib/apl.sh — 自适应功率爬坡器（APL）。被 xjdaemon / xjctl source 的库，不单独常驻。
# 算法整理自「电池解容工具箱」scripts/apl.sh：按 SOC 分段学习硬件真实上限，插枪先回上限，
# 给不上（塌陷/高温/硬故障）就退让并锁定，稳定后小幅上探，学习档案只增不减。
# 本库只负责算目标电流，写 $STATE/apl_target_ma 供 power.sh 的 inf 档读取。
#
# 安全边界（永不越界）：
#   目标 ≤ min(DTBO div_max_curr, apl_max_ma, 20000)
#   温度线：有温控档位温度上限时以其为降档线，低于（上限 − 2℃）才恢复上探；
#           无档位线（unlimited/default/bypass）退回 DTBO ffc_temp_high（上探 = -8，降档 = -3）
#   CV 段（vbat ≥ vterm-30mV）、未插枪、power_limit≠inf 一律让路（移除目标文件）

APL_LIB=1
APL_TGT="$STATE/apl_target_ma"
APL_STATE="$STATE/apl_state"
APL_WARMUP=90
APL_TEMP_HYST=2
APL_ADP_DEBOUNCE=3

# 档位→温度上限映射（单一来源，见 lib/apl_temp.sh）
[ -f "$DIR/lib/apl_temp.sh" ] && . "$DIR/lib/apl_temp.sh"

apl_num() {
  case "$1" in ''|*[!0-9]*) printf '%s' 0 ;; *) printf '%s' "$1" ;; esac
}

# /proc/device-tree 里的 big-endian u32 数组最大值（DTBO 参数）
apl_dt_u32_max() {
  [ -e "$1" ] || { printf '%s' ""; return 0; }
  od -An -tu1 -v "$1" 2>/dev/null | awk '
    { for (i = 1; i <= NF; i++) a[++n] = $i }
    END {
      if (n < 4) { print ""; exit }
      max = 0
      for (i = 1; i + 3 <= n; i += 4) {
        v = a[i] * 16777216 + a[i+1] * 65536 + a[i+2] * 256 + a[i+3]
        if (v > max) max = v
      }
      print max
    }'
}

# 充电泵允许的电流顶（DTBO div_max_curr）
dt_curr_cap_ma() {
  apl_dt_u32_max /proc/device-tree/soc/mca_strategy_quick_charge/div_max_curr
}

# 驱动允许的快充温度上界；APL 的温度线跟随它，避免「自己限制自己」
ffc_temp_high_c() {
  _v=$(apl_dt_u32_max /proc/device-tree/soc/mca_strategy_buckchg_class/ffc_temp_high)
  case "$_v" in ''|*[!0-9]*) printf '%s' 55 ;; *) printf '%s' "$_v" ;; esac
}

apl_vterm_mv() {
  _v=$(apl_dt_u32_max /proc/device-tree/soc/mca_strategy_quick_charge/max_vbat)
  case "$_v" in ''|*[!0-9]*) printf '%s' 4570 ;; *) printf '%s' "$_v" ;; esac
}

# inf 档真实上限：DTBO div_max_curr 与 20000 取小，可用 apl_max_ma 再收紧。
# 注意不能用 max_ma（那是 fast/mid/slow 瓦数换算的封顶），否则解容会被压到几十瓦。
apl_ceiling_ma() {
  _c=20000
  _d=$(dt_curr_cap_ma)
  case "$_d" in ''|*[!0-9]*) _d="" ;; esac
  [ -n "$_d" ] && [ "$_d" -gt 0 ] && [ "$_d" -lt "$_c" ] && _c="$_d"
  _o=$(cfg_get apl_max_ma "")
  case "$_o" in
    ''|*[!0-9]*) ;;
    *) [ "$_o" -gt 0 ] && [ "$_o" -lt "$_c" ] && _c="$_o" ;;
  esac
  printf '%s' "$_c"
}

apl_rd() {
  _v=$(cat "$1" 2>/dev/null)
  case "$_v" in ''|*[!0-9-]*) printf '%s' 0 ;; *) printf '%s' "$_v" ;; esac
}

# 适配器指纹；变化即认为换了充电头，整份学习档案作废重新探
apl_adp_fp() {
  _v=$(apl_rd /sys/class/power_supply/usb/voltage_max)
  _p=$(apl_rd /sys/class/xm_power/charger/charger_common/power_max)
  [ "$_p" = "0" ] && _p=$(apl_rd /sys/class/power_supply/usb/input_power_limit)
  printf '%s_%s' "$_v" "$_p"
}

apl_prof_get() {
  [ -f "$_apl_prof" ] || { printf '%s' ""; return 0; }
  grep -a "^$1=" "$_apl_prof" 2>/dev/null | tail -n1 | cut -d= -f2
}

apl_prof_set() {
  {
    [ -f "$_apl_prof" ] && grep -av "^$1=" "$_apl_prof" 2>/dev/null
    printf '%s=%s\n' "$1" "$2"
  } > "$_apl_prof.tmp" 2>/dev/null && mv -f "$_apl_prof.tmp" "$_apl_prof" 2>/dev/null
}

# 所有 SOC 段的最大学习值：起步不被某一段低值污染
apl_prof_best() {
  [ -f "$_apl_prof" ] || { printf '%s' ""; return 0; }
  grep -a '^b[0-9]=' "$_apl_prof" 2>/dev/null | cut -d= -f2 | awk '
    { if ($1 + 0 > max) max = $1 + 0 }
    END { if (max > 0) print max }'
}

# 适配器变更判定（去抖）：新指纹需连续 APL_ADP_DEBOUNCE 拍稳定才认定换头，返回原因；
# 首次记录（档案无 adp）立即生效，避免插枪协商期指纹抖动把功率反复重置。
apl_adp_check() {
  _fp="$1"
  _old=$(apl_prof_get adp)
  if [ -z "$_old" ]; then
    apl_prof_set adp "$_fp"
    apl_prof_set adpc ""
    apl_prof_set adpn 0
    return 0
  fi
  if [ "$_old" = "$_fp" ]; then
    [ -n "$(apl_prof_get adpc)" ] && { apl_prof_set adpc ""; apl_prof_set adpn 0; }
    return 0
  fi
  _cand=$(apl_prof_get adpc)
  if [ "$_cand" = "$_fp" ]; then
    _n=$(( $(apl_num "$(apl_prof_get adpn)") + 1 ))
  else
    _n=1
  fi
  if [ "$_n" -ge "$APL_ADP_DEBOUNCE" ]; then
    apl_prof_set adp "$_fp"
    apl_prof_set adpc ""
    apl_prof_set adpn 0
    printf '%s' "适配器变更"
    return 0
  fi
  apl_prof_set adpc "$_fp"
  apl_prof_set adpn "$_n"
}

# dmesg 增量里出现充电泵硬故障
apl_hard_fault() {
  _mark="$STATE/apl_dmesg_mark"
  _last=$(apl_num "$(cat "$_mark" 2>/dev/null)")
  _hit=$(dmesg 2>/dev/null | tail -n 300 | grep -acE "open path fail|cp work abnormal|monitor charge abnormal")
  _hit=$(apl_num "$_hit")
  printf '%s' "$_hit" > "$_mark" 2>/dev/null
  [ "$_hit" -gt "$_last" ]
}

# 运行态：一行 8 个字段，read 读回（无 fork）
apl_state_load() {
  apl_prev_uon=0; apl_warm_start=0; apl_lock_until=0; apl_low_cnt=0
  apl_stable_cnt=0; apl_stab_ibat=0; apl_prev_ibat=0; apl_last_up=0
  [ -f "$APL_STATE" ] || return 0
  IFS=' ' read -r apl_prev_uon apl_warm_start apl_lock_until apl_low_cnt \
                 apl_stable_cnt apl_stab_ibat apl_prev_ibat apl_last_up < "$APL_STATE" 2>/dev/null
  case "$apl_prev_uon" in ''|*[!0-9]*) apl_prev_uon=0 ;; esac
  case "$apl_warm_start" in ''|*[!0-9]*) apl_warm_start=0 ;; esac
  case "$apl_lock_until" in ''|*[!0-9]*) apl_lock_until=0 ;; esac
  case "$apl_low_cnt" in ''|*[!0-9]*) apl_low_cnt=0 ;; esac
  case "$apl_stable_cnt" in ''|*[!0-9]*) apl_stable_cnt=0 ;; esac
  case "$apl_stab_ibat" in ''|*[!0-9]*) apl_stab_ibat=0 ;; esac
  case "$apl_prev_ibat" in ''|*[!0-9]*) apl_prev_ibat=0 ;; esac
  case "$apl_last_up" in ''|*[!0-9]*) apl_last_up=0 ;; esac
}

apl_state_save() {
  printf '%s %s %s %s %s %s %s %s\n' "$apl_prev_uon" "$apl_warm_start" "$apl_lock_until" \
    "$apl_low_cnt" "$apl_stable_cnt" "$apl_stab_ibat" "$apl_prev_ibat" "$apl_last_up" \
    > "$APL_STATE.tmp" 2>/dev/null && mv -f "$APL_STATE.tmp" "$APL_STATE" 2>/dev/null
}

apl_tgt_write() {
  printf '%s' "$1" > "$APL_TGT.tmp" 2>/dev/null && mv -f "$APL_TGT.tmp" "$APL_TGT" 2>/dev/null
}

# 每拍调用一次；tick 间隔由 xjdaemon 的 POLL_INTERVAL 决定。
apl_tick() {
  if [ "$(cfg_get apl_enable 0)" != "1" ]; then
    rm -f "$APL_TGT" 2>/dev/null
    return 0
  fi

  _apl_dev=$(cfg_get dev_code "")
  [ -n "$_apl_dev" ] || _apl_dev=$(getprop ro.product.device 2>/dev/null)
  [ -n "$_apl_dev" ] || _apl_dev=unknown
  _apl_prof="$STATE/apl_profile.$_apl_dev.kv"
  _apl_now=$(date +%s)

  _apl_soc=$(apl_num "${BAT_CAP:-0}")
  _apl_mv=$(( $(apl_num "${BAT_VOLT:-0}") / 1000 ))
  _apl_ma=$(( $(apl_num "${BAT_CUR:-0}") / 1000 ))
  [ "$_apl_ma" -lt 0 ] && _apl_ma=$(( -_apl_ma ))
  _apl_pw=$(( _apl_mv * _apl_ma / 1000 ))
  _apl_tb=$(( $(apl_num "${BAT_TEMP:-0}") / 10 ))
  _apl_uon=$(apl_rd /sys/class/power_supply/usb/online)
  _apl_vterm=$(apl_vterm_mv)
  _apl_cap=$(apl_ceiling_ma)
  # 温度线：有档位温度上限 → 以其为降档线，低于（上限 − 滞回）才恢复上探；
  # 无档位线（unlimited/default/bypass）→ 退回 DTBO ffc_temp_high（上探 -8，降档 -3）。
  _apl_lt=$(apl_mode_temp_c)
  if [ -n "$_apl_lt" ]; then
    _apl_tdn="$_apl_lt"
    _apl_tup=$(( _apl_lt - APL_TEMP_HYST ))
  else
    _apl_fh=$(ffc_temp_high_c)
    _apl_tup=$(( _apl_fh - 8 )); [ "$_apl_tup" -lt 40 ] && _apl_tup=40
    _apl_tdn=$(( _apl_fh - 3 )); [ "$_apl_tdn" -lt 45 ] && _apl_tdn=45
  fi
  _apl_lpw=$(apl_mode_power_w)
  _apl_gap=$(apl_num "$(cfg_get cg_min_write_gap 60)")
  [ "$_apl_gap" -lt 15 ] && _apl_gap=15

  # ---- 学习档案：适配器变更（去抖）/ 超过 7 天 → 作废重学 ----
  _apl_fp=$(apl_adp_fp)
  _apl_seen=$(apl_num "$(apl_prof_get seen)")
  _apl_why=$(apl_adp_check "$_apl_fp")
  [ "$_apl_seen" -gt 0 ] && [ $(( _apl_now - _apl_seen )) -gt 604800 ] && \
    _apl_why="${_apl_why:+$_apl_why;}档案>7天"
  if [ -n "$_apl_why" ]; then
    : > "$_apl_prof" 2>/dev/null
    apl_prof_set adp "$_apl_fp"
    apl_prof_set seen "$_apl_now"
    blog "APL 档案重置: $_apl_why"
  elif [ "$_apl_seen" = "0" ] || [ $(( _apl_now - _apl_seen )) -gt 600 ]; then
    apl_prof_set seen "$_apl_now"
  fi

  apl_state_load

  # ---- 让路：不写目标，power.sh 读不到文件时退回原有 inf 行为 ----
  _apl_let=""
  [ "$(cfg_get power_limit inf)" != "inf" ] && _apl_let="power_limit≠inf"
  [ "$_apl_uon" != "1" ] && _apl_let="未插枪"
  case "$IS_CHARGING" in 1) ;; *) [ -z "$_apl_let" ] && _apl_let="status=$BAT_STATUS" ;; esac
  [ "$_apl_mv" -ge $(( _apl_vterm - 30 )) ] && _apl_let="CV段($_apl_mv/$_apl_vterm)"
  if [ -n "$_apl_let" ]; then
    rm -f "$APL_TGT" 2>/dev/null
    apl_prev_uon="$_apl_uon"
    apl_state_save
    return 0
  fi

  # ---- 起始目标：沿用上一拍目标；档案作废时从上限重新试 ----
  _apl_best=$(apl_prof_best)
  _apl_floor=$(( _apl_cap * 75 / 100 ))
  _apl_tgt=$_apl_cap
  [ -n "$_apl_best" ] && [ "$_apl_best" -gt "$_apl_floor" ] && [ "$_apl_best" -le "$_apl_cap" ] && _apl_tgt="$_apl_best"
  [ "$_apl_tgt" -lt "$_apl_floor" ] && _apl_tgt="$_apl_floor"
  _apl_prev=$(cat "$APL_TGT" 2>/dev/null)
  case "$_apl_prev" in ''|*[!0-9]*) _apl_prev="" ;; esac
  [ -n "$_apl_prev" ] && [ "$_apl_prev" -ge 2000 ] && _apl_tgt="$_apl_prev"
  [ -n "$_apl_why" ] && _apl_tgt="$_apl_cap"
  [ "$_apl_tgt" -gt "$_apl_cap" ] && _apl_tgt="$_apl_cap"

  # ---- 插枪边沿：热身期先回上限，给不上会自动退让 ----
  if [ "$_apl_uon" = "1" ] && [ "$apl_prev_uon" != "1" ]; then
    apl_warm_start=$_apl_now
    _apl_tgt=$_apl_cap
    apl_low_cnt=0; apl_stable_cnt=0; apl_last_up=$_apl_now
    blog "APL 插枪：热身 ${APL_WARMUP}s，目标先置 ${_apl_cap}mA"
  fi

  # ---- A 档：硬故障 ----
  if apl_hard_fault; then
    _apl_new=$(( _apl_ma * 80 / 100 ))
    _apl_fl=$(( _apl_cap * 50 / 100 ))
    [ "$_apl_new" -lt "$_apl_fl" ] && _apl_new="$_apl_fl"
    [ "$_apl_new" -gt "$_apl_cap" ] && _apl_new="$_apl_cap"
    _apl_tgt="$_apl_new"
    blog "APL 硬故障：降 ${_apl_tgt}mA，锁 30s"
    apl_lock_until=$(( _apl_now + 30 ))
    apl_low_cnt=0; apl_stable_cnt=0; apl_last_up=$_apl_now
  fi

  # ---- B 档：功率塌陷（插枪后 40s 宽限期，等电流爬上来）----
  _apl_grace=0
  [ "$apl_warm_start" -gt 0 ] && [ $(( _apl_now - apl_warm_start )) -lt 40 ] && _apl_grace=1
  _apl_want=$(( _apl_tgt * _apl_mv / 1000 ))
  if [ "$_apl_grace" = "0" ] && [ "$_apl_want" -gt 0 ] && [ "$_apl_pw" -lt $(( _apl_want * 55 / 100 )) ]; then
    apl_low_cnt=$(( apl_low_cnt + 1 ))
  else
    apl_low_cnt=0
  fi
  if [ "$apl_low_cnt" -ge 2 ]; then
    _apl_new=$(( _apl_ma * 105 / 100 ))
    _apl_fl=$(( _apl_cap * 50 / 100 ))
    [ "$_apl_new" -lt "$_apl_fl" ] && _apl_new="$_apl_fl"
    [ "$_apl_new" -gt "$_apl_cap" ] && _apl_new="$_apl_cap"
    if [ "$_apl_new" -lt "$_apl_tgt" ]; then
      _apl_tgt="$_apl_new"
      blog "APL 塌陷($(( _apl_pw / 1000 ))W < 目标$(( _apl_want / 1000 ))W)：降 ${_apl_tgt}mA，锁 60s"
      apl_lock_until=$(( _apl_now + 60 ))
      apl_stable_cnt=0; apl_last_up=$_apl_now
    fi
    apl_low_cnt=0
  fi

  # ---- 温度保护 ----
  if [ "$_apl_tb" -ge "$_apl_tdn" ]; then
    _apl_new=$(( _apl_tgt * 80 / 100 ))
    if [ -n "$_apl_lpw" ]; then
      # 有档位功率上限：温度超上限时目标电流不得高于「上限瓦数 / 电压」，
      # 超过上限 3℃ 以上直接压到档位功率，不再逐拍 20% 磨。
      _apl_pcap=$(( _apl_lpw * 1000 / _apl_mv ))
      [ "$_apl_pcap" -lt 2000 ] && _apl_pcap=2000
      [ "$_apl_new" -gt "$_apl_pcap" ] && _apl_new="$_apl_pcap"
      [ "$_apl_tb" -ge $(( _apl_lt + 3 )) ] && _apl_new="$_apl_pcap"
      _apl_fl=$(( _apl_cap * 10 / 100 ))
      [ "$_apl_fl" -lt 2000 ] && _apl_fl=2000
    else
      _apl_fl=$(( _apl_cap * 50 / 100 ))
    fi
    [ "$_apl_new" -lt "$_apl_fl" ] && _apl_new="$_apl_fl"
    if [ "$_apl_new" -lt "$_apl_tgt" ]; then
      _apl_tgt="$_apl_new"
      blog "APL 电池 ${_apl_tb}℃ ≥ 降档线 ${_apl_tdn}℃（档位 ${_apl_lpw:-无}W）：降 → ${_apl_tgt}mA"
      apl_lock_until=$(( _apl_now + 60 ))
      apl_stable_cnt=0
    fi
  fi

  _apl_locked=0
  [ "$_apl_now" -lt "$apl_lock_until" ] && _apl_locked=1

  # ---- 稳定计数：实际功率达到目标的 88% ----
  if [ "$_apl_want" -gt 0 ] && [ "$_apl_pw" -ge $(( _apl_want * 88 / 100 )) ]; then
    apl_stable_cnt=$(( apl_stable_cnt + 1 ))
  else
    apl_stable_cnt=0
  fi

  # ---- 上探：热身 +8%，稳定 ≥6 拍 +6%，否则 ≥9 拍 +3% ----
  if [ "$_apl_locked" = "0" ] && [ "$_apl_tb" -lt "$_apl_tup" ] && [ "$_apl_tgt" -lt "$_apl_cap" ]; then
    _apl_step=0; _apl_need=99
    if [ "$apl_warm_start" -gt 0 ] && [ $(( _apl_now - apl_warm_start )) -lt "$APL_WARMUP" ]; then
      _apl_step=$(( _apl_tgt * 8 / 100 )); _apl_need=3
    elif [ "$apl_stable_cnt" -ge 6 ]; then
      _apl_step=$(( _apl_tgt * 6 / 100 )); _apl_need=6
    elif [ "$apl_stable_cnt" -ge 9 ]; then
      _apl_step=$(( _apl_tgt * 3 / 100 )); _apl_need=9
    fi
    [ "$_apl_step" -lt 200 ] && _apl_step=200
    if [ "$apl_stable_cnt" -ge "$_apl_need" ] && [ $(( _apl_now - apl_last_up )) -ge "$_apl_gap" ]; then
      _apl_new=$(( _apl_tgt + _apl_step ))
      [ "$_apl_new" -gt "$_apl_cap" ] && _apl_new="$_apl_cap"
      if [ "$_apl_new" -gt "$_apl_tgt" ]; then
        _apl_tgt="$_apl_new"
        blog "APL 上探 +${_apl_step} → ${_apl_tgt}mA / 上限 ${_apl_cap}mA"
        apl_last_up=$_apl_now; apl_stable_cnt=0
      fi
    fi
  fi

  # ---- 学习落盘：实际电流连续 3 拍波动 <±5% 且 >2000mA，按 SOC 段记现实值，只增不减 ----
  _apl_ibl=$(( _apl_ma * 95 / 100 ))
  _apl_ibh=$(( _apl_ma * 105 / 100 ))
  if [ "$apl_prev_ibat" -ge "$_apl_ibl" ] && [ "$apl_prev_ibat" -le "$_apl_ibh" ] && [ "$_apl_ma" -gt 2000 ]; then
    apl_stab_ibat=$(( apl_stab_ibat + 1 ))
  else
    apl_stab_ibat=0
  fi
  if [ "$apl_stab_ibat" -ge 3 ] && [ "$_apl_ma" -gt 2000 ]; then
    _apl_seg=$(( _apl_soc / 10 ))
    [ "$_apl_seg" -gt 9 ] && _apl_seg=9
    _apl_real=$_apl_ma
    [ "$_apl_real" -gt "$_apl_cap" ] && _apl_real="$_apl_cap"
    _apl_old=$(apl_num "$(apl_prof_get "b$_apl_seg")")
    if [ "$_apl_old" -eq 0 ]; then
      apl_prof_set "b$_apl_seg" "$_apl_real"
    elif [ "$_apl_real" -gt "$_apl_old" ]; then
      apl_prof_set "b$_apl_seg" $(( (_apl_old * 3 + _apl_real * 7) / 10 ))
    fi
  fi

  # ---- 落盘 ----
  # 有档位功率上限时，50% 硬底不再适用（否则二档 25W 会被抬回 10000mA/40W）
  _apl_fl=$(( _apl_cap * 50 / 100 ))
  if [ -n "$_apl_lpw" ]; then
    _apl_pcap=$(( _apl_lpw * 1000 / _apl_mv ))
    [ "$_apl_pcap" -lt "$_apl_fl" ] && _apl_fl="$_apl_pcap"
  fi
  [ "$_apl_tgt" -lt "$_apl_fl" ] && _apl_tgt="$_apl_fl"
  [ "$_apl_tgt" -gt "$_apl_cap" ] && _apl_tgt="$_apl_cap"
  [ "$_apl_tgt" -lt 2000 ] && _apl_tgt=2000
  apl_tgt_write "$_apl_tgt"
  apl_prev_uon="$_apl_uon"
  apl_prev_ibat="$_apl_ma"
  apl_state_save
}

# 状态输出（xjctl 用）
apl_status() {
  _d=$(cfg_get dev_code "")
  [ -n "$_d" ] || _d=unknown
  echo "apl_enable=$(cfg_get apl_enable 0)"
  echo "目标=$(cat "$APL_TGT" 2>/dev/null) mA 上限=$(apl_ceiling_ma) mA"
  echo "档案=$STATE/apl_profile.$_d.kv"
  [ -f "$STATE/apl_profile.$_d.kv" ] && cat "$STATE/apl_profile.$_d.kv"
}
