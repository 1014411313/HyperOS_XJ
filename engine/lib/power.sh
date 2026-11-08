#!/system/bin/sh
# engine/lib/power.sh — 充电功率档执行。逻辑整理自解容工具箱的 power_limit /
# charge_power 与 common.sh 的直接控制节点，写节点全部在本进程内完成。

WIRED_CURR=/sys/class/xm_power/charger/charger_thermal/wired_chg_curr
WIRED_CURR2=/sys/class/xm_power/charger/charger_thermal/wired_chg_curr2
WIRELESS_CURR=/sys/class/xm_power/charger/charger_thermal/wireless_chg_curr
WIRED_CTRL=/sys/class/xm_power/charger/charger_thermal/wired_ctrl_limit
WIRELESS_CTRL=/sys/class/xm_power/charger/charger_thermal/wireless_ctrl_limit
WIRED_TH=/sys/class/xm_power/charger/charger_thermal/wired_thermal_remove
WIRELESS_TH=/sys/class/xm_power/charger/charger_thermal/wireless_thermal_remove

max_ma() {
  _m=$(cfg_get max_ma "")
  case "$_m" in
    ''|*[!0-9]*)
      _dev=$(cfg_get dev_code "")
      [ -n "$_dev" ] || _dev=$(getprop ro.product.vendor.device 2>/dev/null)
      [ -n "$_dev" ] || _dev=$(getprop ro.product.device 2>/dev/null)
      case "$_dev" in
        dada) _m=12000 ;;
        miro) _m=10250 ;;
        songyuan|pudding) _m=9200 ;;
        warsaw|myron|annibale|popsicle) _m=9000 ;;
        bixi) _m=6500 ;;
        *) _m=8000 ;;
      esac ;;
  esac
  printf '%s' "$_m"
}

read_voltage_uv() {
  for _p in /sys/class/power_supply/battery/voltage_now \
            /sys/class/power_supply/main/voltage_now \
            /sys/class/power_supply/usb/voltage_now; do
    _v=$(cat "$_p" 2>/dev/null)
    case "$_v" in ''|*[!0-9]*|0) continue ;; esac
    [ "$_v" -lt 1000 ] && continue
    [ "$_v" -lt 100000 ] && _v=$((_v * 1000))
    printf '%s' "$_v"
    return 0
  done
  printf '%s' 4000000
}

tier_watt() {
  case "$1" in
    inf) echo 0 ;;
    fast) _w=$(cfg_get fast_w 40) ;;
    mid) _w=$(cfg_get mid_w 25) ;;
    slow) _w=$(cfg_get slow_w 10) ;;
    ''|*[!0-9]*) _w=0 ;;
    *) _w=$1 ;;
  esac
  case "$_w" in ''|*[!0-9]*) _w=0 ;; esac
  echo "$_w"
}

watt_to_ma() {
  _w=$1 _uv=$2
  _ma=$(awk -v w="$_w" -v v="$_uv" 'BEGIN { printf "%d", w * 1e9 / v }')
  case "$_ma" in ''|*[!0-9]*) _ma=0 ;; esac
  _mx=$(max_ma)
  [ "$_ma" -gt "$_mx" ] && _ma=$_mx
  [ "$_ma" -lt 500 ] && _ma=500
  echo "$_ma"
}

# inf 档的电流目标由 APL 决定（apl.sh 每拍写 $STATE/apl_target_ma）。
# 读不到目标（APL 关闭 / 让路 / 未插枪）返回非 0，调用方退回原有「不限速」行为。
apl_target_ma() {
  [ "$(cfg_get apl_enable 0)" = "1" ] || return 1
  _t=$(cat "$STATE/apl_target_ma" 2>/dev/null)
  case "$_t" in ''|*[!0-9]*) return 1 ;; esac
  # 锁温时目标会低于 2000mA（5W ≈ 1250mA），下限放到 300mA，否则会被当成「无目标」退回全速
  [ "$_t" -ge 300 ] || return 1
  if [ -n "$APL_LIB" ]; then
    _c=$(apl_ceiling_ma)
    [ "$_t" -gt "$_c" ] && _t="$_c"
  fi
  printf '%s' "$_t"
}

ctrl_level() {
  _w=$1
  [ "$_w" -ge 40 ] && { echo 0; return 0; }
  [ "$_w" -ge 30 ] && { echo 2; return 0; }
  [ "$_w" -ge 25 ] && { echo 4; return 0; }
  [ "$_w" -ge 20 ] && { echo 7; return 0; }
  [ "$_w" -ge 15 ] && { echo 10; return 0; }
  [ "$_w" -ge 10 ] && { echo 12; return 0; }
  echo 14
}

# inf 档的温控总闸：电池温度 ≥ thermal_ceil（默认 580 = 58.0℃）时把 wired_thermal_remove
# 交回驱动 DT 温控表；带滞回，避免在阈值附近反复翻转触发 PMIC 重协商。
power_thermal_target() {
  _t=${BAT_TEMP:-0}
  case "$_t" in ''|*[!0-9-]*) printf '%s' 1; return ;; esac
  _on=$(cfg_get thermal_ceil 580)
  case "$_on" in ''|*[!0-9]*) _on=580 ;; esac
  _hyst=$(cfg_get thermal_ceil_hyst 30)
  case "$_hyst" in ''|*[!0-9]*) _hyst=30 ;; esac
  _f="$BT_STATE/thermal_gate"
  _st=$(cat "$_f" 2>/dev/null)
  if [ "$_t" -ge "$_on" ]; then
    printf '%s' 0 > "$_f" 2>/dev/null
    printf '%s' 0
    return
  fi
  if [ "$_st" = "0" ] && [ "$_t" -gt $((_on - _hyst)) ]; then
    printf '%s' 0
    return
  fi
  [ "$_st" = "0" ] && printf '%s' 1 > "$_f" 2>/dev/null
  printf '%s' 1
}

# 非 inf 档闭环反馈：系统会把电流节点"顶高"（实测低值压得住、中值被顶），开环写一次
# 对不准档位实际功率，这里按实测电池电流每 2 拍微调写入值，让它贴住目标。
power_closed_loop_ma() {
  _tier="$1" _want="$2"
  case "$_want" in ''|*[!0-9]*) printf '%s' "$_want"; return ;; esac
  _b="${BAT:-/sys/class/power_supply/battery}"
  _f="$BT_STATE/closed_loop"
  _c=$(cat "$_f" 2>/dev/null)
  _ct=${_c%%"|"*}
  _cn=${_c#*"|"}
  case "$_cn" in ''|*[!0-9]*) _cn="$_want" ;; esac
  [ "$_ct" = "$_tier" ] || _cn="$_want"
  case "$(cat "$_b/status" 2>/dev/null)" in
    Discharging|"Not charging")
      printf '%s|%s\n' "$_tier" "$_want" > "$_f.tmp" 2>/dev/null && mv -f "$_f.tmp" "$_f" 2>/dev/null
      printf '%s' "$_want"
      return ;;
  esac
  _i=$(cat "$_b/current_now" 2>/dev/null)
  case "$_i" in ''|*[!0-9-]*) printf '%s' "$_cn"; return ;; esac
  _i=${_i#-}
  _i=$((_i / 1000))
  # 偏离过大直接跳回目标，不再逐拍爬（否则被砍狠了要几十秒才恢复）
  [ "$_cn" -lt $((_want / 2)) ] && _cn="$_want"
  if [ $(( ${TICK_N:-0} % 2 )) -eq 0 ]; then
    [ "$_i" -gt $((_want * 115 / 100)) ] && _cn=$((_cn * 80 / 100))
    [ "$_i" -lt $((_want * 85 / 100)) ] && _cn=$((_cn * 110 / 100))
  fi
  [ "$_cn" -gt "$_want" ] && _cn="$_want"
  _fl=$((_want * 25 / 100))
  [ "$_fl" -lt 400 ] && _fl=400
  [ "$_cn" -lt "$_fl" ] && _cn="$_fl"
  printf '%s|%s\n' "$_tier" "$_cn" > "$_f.tmp" 2>/dev/null && mv -f "$_f.tmp" "$_f" 2>/dev/null
  printf '%s' "$_cn"
}

wnode() {
  [ -e "$1" ] || return 1
  [ -w "$1" ] || chmod 0664 "$1" 2>/dev/null
  { echo "$2" > "$1"; } 2>/dev/null
}

save_orig_current() {
  [ -s "$BT_STATE/power_orig_curr" ] && return 0
  [ -r "$WIRED_CURR" ] && cat "$WIRED_CURR" 2>/dev/null > "$BT_STATE/power_orig_curr"
  [ -r "$WIRELESS_CURR" ] && cat "$WIRELESS_CURR" 2>/dev/null > "$BT_STATE/power_orig_wless"
}

restore_current() {
  _o=$(cat "$BT_STATE/power_orig_curr" 2>/dev/null)
  case "$_o" in ''|*[!0-9]*) ;; *) wnode "$WIRED_CURR" "$_o"; wnode "$WIRED_CURR2" "$_o" ;; esac
  _wo=$(cat "$BT_STATE/power_orig_wless" 2>/dev/null)
  case "$_wo" in ''|*[!0-9]*) ;; *) wnode "$WIRELESS_CURR" "$_wo" ;; esac
}

# 节点是否已符合期望值；$1=th $2=lv $3=ma(可空)。输出 1=符合 / 0=需要重写。
nodes_ok() {
  _th="$1" _lv="$2" _ma="$3" _ok=1
  for _p in "$WIRED_TH" "$WIRELESS_TH"; do
    [ -e "$_p" ] || continue
    [ "$(cat "$_p" 2>/dev/null)" = "$_th" ] || _ok=0
  done
  for _p in "$WIRED_CTRL" "$WIRELESS_CTRL"; do
    [ -e "$_p" ] || continue
    [ "$(cat "$_p" 2>/dev/null)" = "$_lv" ] || _ok=0
  done
  if [ -n "$_ma" ]; then
    for _p in "$WIRED_CURR" "$WIRED_CURR2"; do
      [ -e "$_p" ] || continue
      [ "$(cat "$_p" 2>/dev/null)" = "$((_ma * 1000))" ] || _ok=0
    done
    if [ -e "$WIRELESS_CURR" ]; then
      [ "$(cat "$WIRELESS_CURR" 2>/dev/null)" = "$_ma" ] || _ok=0
    fi
  fi
  echo "$_ok"
}

# 应用一个功率档；inf=不限速，其余按瓦数换算电流写节点。
# inf：th=1（解除驱动 DT 温控）+ ctrl=0，电流节点只做一次性「安顿」。th 绝不能写 0：
#   驱动 DT 温控表一旦接管会把功率压到约 2W，此时电流节点写多少都没用（参考实现实测）。
#   所以 APL 目标只作安顿值，不改 th，也不进幂等签名 —— APL 每拍会变，进了签名就会
#   每拍重写 wired_chg_curr，而每次写都触发 PMIC 重协商 PD 契约（功率锯齿）。
# 其余档：th=0 + ctrl 阶梯 + 按瓦数换算电流。
power_apply() {
  _tier=$(norm_power "$1")
  _th=1 _lv=0 _ma=""
  if [ "$_tier" = "inf" ]; then
    _ma=$(apl_target_ma) || _ma=$(apl_ceiling_ma)
    case "$_ma" in ''|*[!0-9]*) _ma="" ;; esac
    _th=$(power_thermal_target)
    _sig="inf|${_th}|0|"
    _chk=""
  else
    _w=$(tier_watt "$_tier")
    _uv=$(read_voltage_uv)
    _ma=$(watt_to_ma "$_w" "$_uv")
    _ma=$(power_closed_loop_ma "$_tier" "$_ma")
    _lv=$(ctrl_level "$_w")
    _th=0
    _sig="${_tier}|${_th}|${_lv}|${_ma}"
    _chk="$_ma"
  fi

  _prev=$(cat "$BT_STATE/power_sig" 2>/dev/null)
  _need=1
  if [ "$_prev" = "$_sig" ] && [ "$(nodes_ok "$_th" "$_lv" "$_chk")" = "1" ]; then
    _need=0
    if [ "$_tier" = "inf" ]; then
      # inf 还要确认电流节点已放行（≥10A）；偏低说明未安顿，需补写一次。
      _cur=$(cat "$WIRED_CURR" 2>/dev/null)
      case "$_cur" in ''|*[!0-9]*) _cur=0 ;; esac
      [ "$_cur" -ge 10000000 ] || _need=1
    fi
  fi
  if [ "$_need" = "0" ]; then
    printf '%s' "$_tier" > "$BT_STATE/power_applied" 2>/dev/null
    return 0
  fi

  # 同档位但节点被驱动复位时按最小写间隔节流。
  if [ "$_prev" = "$_sig" ]; then
    _now=$(date +%s)
    _ts=$(cat "$BT_STATE/power_write_ts" 2>/dev/null)
    case "$_ts" in ''|*[!0-9]*) _ts=0 ;; esac
    _gap=$(cfg_get cg_min_write_gap 60)
    case "$_gap" in ''|*[!0-9]*) _gap=60 ;; esac
    [ $((_now - _ts)) -lt "$_gap" ] && return 0
  fi

  save_orig_current
  if [ "$_th" = "1" ]; then
    wnode "$WIRED_TH" 1
    wnode "$WIRELESS_TH" 1
    wnode "$WIRED_CTRL" 0
    wnode "$WIRELESS_CTRL" 0
    if [ -n "$_ma" ]; then
      wnode "$WIRED_CURR" $((_ma * 1000))
      wnode "$WIRED_CURR2" $((_ma * 1000))
      wnode "$WIRELESS_CURR" "$_ma"
    fi
  elif [ "$_tier" = "inf" ]; then
    # inf 高温回退：交回驱动 DT 温控表，不再顶电流节点
    wnode "$WIRED_TH" 0
    wnode "$WIRELESS_TH" 0
    wnode "$WIRED_CTRL" 0
    wnode "$WIRELESS_CTRL" 0
  else
    wnode "$WIRED_TH" 0
    wnode "$WIRELESS_TH" 0
    wnode "$WIRED_CTRL" "$_lv"
    wnode "$WIRELESS_CTRL" "$_lv"
    wnode "$WIRED_CURR" $((_ma * 1000))
    wnode "$WIRED_CURR2" $((_ma * 1000))
    wnode "$WIRELESS_CURR" "$_ma"
  fi
  printf '%s' "$_sig" > "$BT_STATE/power_sig" 2>/dev/null
  printf '%s' "$_tier" > "$BT_STATE/power_applied" 2>/dev/null
  date +%s > "$BT_STATE/power_write_ts" 2>/dev/null
}

power_restore() {
  wnode "$WIRED_TH" 0
  wnode "$WIRELESS_TH" 0
  wnode "$WIRED_CTRL" 0
  wnode "$WIRELESS_CTRL" 0
  restore_current
  rm -f "$BT_STATE/power_applied" "$BT_STATE/power_sig" 2>/dev/null
}

power_status() {
  echo "tier=$(power_tier)"
  echo "applied=$(cat "$BT_STATE/power_applied" 2>/dev/null)"
  echo "wired_th=$(cat "$WIRED_TH" 2>/dev/null)"
  echo "wired_ctrl=$(cat "$WIRED_CTRL" 2>/dev/null)"
  echo "wired_curr=$(cat "$WIRED_CURR" 2>/dev/null)"
  echo "wireless_curr=$(cat "$WIRELESS_CURR" 2>/dev/null)"
}
