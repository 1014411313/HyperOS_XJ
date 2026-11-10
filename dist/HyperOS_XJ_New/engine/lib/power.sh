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

# ---------- 全局限流写入间隔（参照 charge_guard.sh 的 cg_gap_ok）----------
# 【为什么只用在 inf 档】inf 每次写电流节点都会让 PMIC 重协商 PD 契约，必须压住写入频率；
#   非 inf 档是「开环写目标 + power_closed_loop_ma 闭环微调」（每 2 拍修一次），
#   用 60 秒下限压住会让实际功率明显偏离目标值 —— 参照实现没有闭环这一路，所以不能照搬。
#   cg_last_write_ts 只由 inf 安顿路径更新，与 power_write_ts（非 inf 同签名节流）互不干扰。
CG_GAP_DEFAULT=60

# 返回 0 = 距上次写入已超过最小间隔，允许写；1 = 还在间隔内，拒绝。
cg_gap_ok() {
  # APL 硬故障紧急通道：apl.sh 判定 A 档硬故障时写 state/apl_urgent，
  #   放行一次绕过 60 秒下限（否则「立即退让」会被拖到最多 60 秒后才生效）。
  #   该标志是一次性的：读到这里就删。
  if [ -f "$STATE/apl_urgent" ]; then
    rm -f "$STATE/apl_urgent" 2>/dev/null
    log "power: APL 紧急通道放行（绕过 cg_min_write_gap）"
    return 0
  fi
  _gap=$(cfg_get cg_min_write_gap "$CG_GAP_DEFAULT")
  case "$_gap" in ''|*[!0-9]*) _gap=$CG_GAP_DEFAULT ;; esac
  [ "$_gap" -gt 0 ] 2>/dev/null || _gap=$CG_GAP_DEFAULT
  _lw=$(cat "$BT_STATE/cg_last_write_ts" 2>/dev/null)
  case "$_lw" in ''|*[!0-9]*) _lw=0 ;; esac
  [ "$_lw" = "0" ] && return 0          # 从未写过 → 放行
  _now=$(date +%s)
  # 未来时间戳（系统时间被回拨 / 文件损坏）→ 清掉放行；mksh 的 $(( )) 是 32 位算术，
  #   直接相减会溢出成正数把限流静默废掉，所以先比大小。
  if [ "$_lw" -gt "$_now" ] 2>/dev/null; then
    rm -f "$BT_STATE/cg_last_write_ts" 2>/dev/null
    return 0
  fi
  _diff=$((_now - _lw))
  case "$_diff" in ''|*[!0-9]*) return 0 ;; esac
  [ "$_diff" -ge "$_gap" ] && return 0
  return 1
}

# 记录「inf 档刚刚真正写了电流节点」的时间戳；只在写入成功时调用。
cg_note_write() {
  date +%s > "$BT_STATE/cg_last_write_ts" 2>/dev/null
}

# 「已安顿 / 已封顶 / 失败计数」描述的是【本次开机】的驱动状态：重启后驱动会把节点复位，
#   留着旧标志会让 inf 档开机即自认已安顿 → 永久低功率。用 boot_id 识别新的一次开机并清掉。
power_boot_clear_stale() {
  _b=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
  [ -n "$_b" ] || return 0
  [ "$_b" = "$(cat "$BT_STATE/power_boot_id" 2>/dev/null)" ] && return 0
  rm -f "$BT_STATE/inf_settled" "$BT_STATE/inf_settle_fail" "$BT_STATE/inf_node_capped" 2>/dev/null
  printf '%s' "$_b" > "$BT_STATE/power_boot_id" 2>/dev/null
}

# ctrl_limit / thermal_remove 的原厂值快照：0 与空都视为无效（驱动未就绪、或本就是 0），
#   存下去会在还原时把温控表/限流写坏，所以只留有效值。
save_orig_gate() {
  [ -s "$BT_STATE/power_orig_ctrl" ] || {
    _c=$(cat "$WIRED_CTRL" 2>/dev/null)
    case "$_c" in ''|*[!0-9]*|0) ;; *) printf '%s' "$_c" > "$BT_STATE/power_orig_ctrl" ;; esac
  }
  [ -s "$BT_STATE/power_orig_th" ] || {
    _t=$(cat "$WIRED_TH" 2>/dev/null)
    case "$_t" in ''|*[!0-9]*|0) ;; *) printf '%s' "$_t" > "$BT_STATE/power_orig_th" ;; esac
  }
}

# 还原 ctrl_limit / thermal_remove：有有效原厂值才写回，无效（0/空/缺失）则不写。
restore_gate() {
  _c=$(cat "$BT_STATE/power_orig_ctrl" 2>/dev/null)
  case "$_c" in ''|*[!0-9]*|0) ;; *) wnode "$WIRED_CTRL" "$_c"; wnode "$WIRELESS_CTRL" "$_c" ;; esac
  _t=$(cat "$BT_STATE/power_orig_th" 2>/dev/null)
  case "$_t" in ''|*[!0-9]*|0) ;; *) wnode "$WIRED_TH" "$_t"; wnode "$WIRELESS_TH" "$_t" ;; esac
}

save_orig_current() {
  # 读到的 0 不是有效的原厂值（驱动未就绪/未插枪时该节点就可能是 0），
  # 存下去后 restore_current 会把充电上限写回 0，表现为卸载后充电极慢。
  [ -s "$BT_STATE/power_orig_curr" ] && [ "$(cat "$BT_STATE/power_orig_curr" 2>/dev/null)" != "0" ] && return 0
  _c=$(cat "$WIRED_CURR" 2>/dev/null)
  case "$_c" in ''|*[!0-9]*|0) ;; *) printf '%s' "$_c" > "$BT_STATE/power_orig_curr" ;; esac
  _wc=$(cat "$WIRELESS_CURR" 2>/dev/null)
  case "$_wc" in ''|*[!0-9]*|0) ;; *) printf '%s' "$_wc" > "$BT_STATE/power_orig_wless" ;; esac
}

restore_current() {
  _o=$(cat "$BT_STATE/power_orig_curr" 2>/dev/null)
  case "$_o" in ''|*[!0-9]*|0) _o=$(cat "$CONFDIR/rt_charge_orig" 2>/dev/null) ;; esac
  case "$_o" in ''|*[!0-9]*|0) _o="" ;; esac
  [ -n "$_o" ] && { wnode "$WIRED_CURR" "$_o"; wnode "$WIRED_CURR2" "$_o"; }
  _wo=$(cat "$BT_STATE/power_orig_wless" 2>/dev/null)
  case "$_wo" in ''|*[!0-9]*|0) _wo="" ;; esac
  [ -n "$_wo" ] && wnode "$WIRELESS_CURR" "$_wo"
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
    # 电流节点回读容差 2%（下限 100µA）：驱动会对写入值做量化/裁剪（如 20000→19700），
    #   要求完全相等会每拍判「未生效」→ 每拍重写 → PD 重协商锯齿。
    _want_uv=$((_ma * 1000))
    _tol_uv=$((_ma * 1000 / 50))
    [ "$_tol_uv" -lt 100 ] && _tol_uv=100
    for _p in "$WIRED_CURR" "$WIRED_CURR2"; do
      [ -e "$_p" ] || continue
      _now=$(cat "$_p" 2>/dev/null)
      case "$_now" in ''|*[!0-9]*) _ok=0; continue ;; esac
      _d=$((_now - _want_uv)); [ "$_d" -lt 0 ] && _d=$((0 - _d))
      [ "$_d" -le "$_tol_uv" ] || _ok=0
    done
    if [ -e "$WIRELESS_CURR" ]; then
      _now=$(cat "$WIRELESS_CURR" 2>/dev/null)
      case "$_now" in
        ''|*[!0-9]*) _ok=0 ;;
        *)
          _tol_ma=$((_ma / 50))
          [ "$_tol_ma" -lt 1 ] && _tol_ma=1
          _d=$((_now - _ma)); [ "$_d" -lt 0 ] && _d=$((0 - _d))
          [ "$_d" -le "$_tol_ma" ] || _ok=0 ;;
      esac
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
    power_boot_clear_stale
    _ma=$(apl_target_ma) || _ma=$(apl_ceiling_ma)
    case "$_ma" in ''|*[!0-9]*) _ma="" ;; esac
    _th=$(power_thermal_target)
    _sig="inf|${_th}|0|"
    _chk=""
  else
    # 离开 inf 档就清「已安顿」与失败计数，让下次回到全速重新算一次（生效档位已含 power_limit 约束）。
    rm -f "$BT_STATE/inf_settled" "$BT_STATE/inf_settle_fail" 2>/dev/null
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
      # inf 还要确认电流节点已放行（≥10A）；已落「已安顿 / 已封顶」标志时视为已放行，不再补写。
      if [ ! -f "$BT_STATE/inf_settled" ] && [ ! -f "$BT_STATE/inf_node_capped" ]; then
        _cur=$(cat "$WIRED_CURR" 2>/dev/null)
        case "$_cur" in ''|*[!0-9]*) _cur=0 ;; esac
        [ "$_cur" -ge 10000000 ] || _need=1
      fi
    fi
  fi
  if [ "$_need" = "0" ]; then
    printf '%s' "$_tier" > "$BT_STATE/power_applied" 2>/dev/null
    return 0
  fi

  # 同档位（签名不变）但节点被驱动复位时按最小写间隔节流 —— 【仅非 inf】。
  #   inf 档的电流安顿走 cg_gap_ok 全局限流；非 inf 档签名里含闭环算出的 _ma，
  #   签名一变（闭环微调）立即写，不能被 60 秒下限压住，否则功率会偏离目标。
  if [ "$_prev" = "$_sig" ] && [ "$_tier" != "inf" ]; then
    _now=$(date +%s)
    _ts=$(cat "$BT_STATE/power_write_ts" 2>/dev/null)
    case "$_ts" in ''|*[!0-9]*) _ts=0 ;; esac
    _gap=$(cfg_get cg_min_write_gap 60)
    case "$_gap" in ''|*[!0-9]*) _gap=60 ;; esac
    [ $((_now - _ts)) -lt "$_gap" ] && return 0
  fi

  save_orig_current
  save_orig_gate

  # ---- 闸门节点（thermal_remove / ctrl_limit）----
  # 【为什么不受 60 秒下限约束】温控总闸翻转要即时生效（高温立即交回 DT 温控表），
  #   拖到 60 秒后就是持续高温充电；且写这两个节点不会触发 PMIC 重协商 PD 契约。
  if [ "$(nodes_ok "$_th" "$_lv" "")" = "0" ]; then
    if [ "$_th" = "1" ]; then
      wnode "$WIRED_TH" 1
      wnode "$WIRELESS_TH" 1
      wnode "$WIRED_CTRL" 0
      wnode "$WIRELESS_CTRL" 0
    else
      wnode "$WIRED_TH" 0
      wnode "$WIRELESS_TH" 0
      wnode "$WIRED_CTRL" "$_lv"
      wnode "$WIRELESS_CTRL" "$_lv"
    fi
  fi

  # ---- 电流节点 ----
  # 【为什么分档处理】inf 档只做「进入时一次性安顿」：已安顿/已封顶就不再写，
  #   否则每次写都会让 PMIC 重协商 PD 契约（功率锯齿）。其余档按闭环值每拍写。
  #   只有真正充电（Charging/Full）才安顿：拔枪时驱动给的默认值插枪后保不住，落旗会永久卡低功率。
  _cur_write=0
  if [ "$_tier" = "inf" ]; then
    if [ "$_th" = "1" ] && [ ! -f "$BT_STATE/inf_settled" ] && [ ! -f "$BT_STATE/inf_node_capped" ]; then
      case "$(cat "${BAT:-/sys/class/power_supply/battery}/status" 2>/dev/null)" in
        *Charging*|Full) _cur_write=1 ;;
      esac
    fi
  else
    _cur_write=1
  fi

  _ma_written=0
  if [ "$_cur_write" = "1" ] && [ -n "$_ma" ]; then
    # 【为什么只有 inf 过全局限流】inf 每次写都触发 PMIC 重协商 PD 契约，必须压住写入频率；
    #   非 inf 的闭环微调靠每 2 拍修正贴住目标，加 60 秒下限会让实际功率明显偏离目标值。
    _may_write=1
    if [ "$_tier" = "inf" ]; then
      cg_gap_ok || _may_write=0
    fi
    if [ "$_may_write" = "1" ]; then
      wnode "$WIRED_CURR" $((_ma * 1000)) && _ma_written=1
      wnode "$WIRED_CURR2" $((_ma * 1000))
      wnode "$WIRELESS_CURR" "$_ma"
      [ "$_tier" = "inf" ] && [ "$_ma_written" = "1" ] && cg_note_write
    fi
  fi

  if [ "$_tier" = "inf" ] && [ "$_th" = "1" ] && [ "$_ma_written" = "1" ]; then
    # 写入后回读确认：达到「可用上限」（≥10A）才落安顿标志；未达则不落，下轮（60 秒后）重试。
    _after=$(cat "$WIRED_CURR" 2>/dev/null)
    case "$_after" in ''|*[!0-9]*) _after=0 ;; esac
    if [ "$_after" -ge 10000000 ]; then
      rm -f "$BT_STATE/inf_settle_fail" 2>/dev/null
      date +%s > "$BT_STATE/inf_settled" 2>/dev/null
      log "power: inf 安顿成功（节点 ${_after}µA，不再写电流节点）"
    else
      _fn=$(cat "$BT_STATE/inf_settle_fail" 2>/dev/null)
      case "$_fn" in ''|*[!0-9]*) _fn=0 ;; esac
      _fn=$((_fn + 1))
      printf '%s' "$_fn" > "$BT_STATE/inf_settle_fail" 2>/dev/null
      if [ "$_fn" -ge 3 ]; then
        # 连试 3 次都抬不到 10A → 本机型该节点物理上限就低于 10A（或该列是适配器输入侧，
        #   快充时本就 500~8000mA）。继续重写只会持续触发 PD 重协商 —— 本次开机停手。
        printf '%s' "$_after" > "$BT_STATE/inf_node_capped" 2>/dev/null
        log "power: inf 电流节点上限 ${_after}µA(<10A)，本次开机不再写电流节点"
      else
        log "power: inf 安顿未生效（节点 ${_after}µA），60 秒后重试(${_fn}/3)"
      fi
    fi
  fi

  printf '%s' "$_sig" > "$BT_STATE/power_sig" 2>/dev/null
  printf '%s' "$_tier" > "$BT_STATE/power_applied" 2>/dev/null
  # 非 inf 同签名节流用的时间戳；inf 档不读它（inf 用 cg_last_write_ts）。
  date +%s > "$BT_STATE/power_write_ts" 2>/dev/null
}

power_restore() {
  # ctrl / thermal_remove 还原为原厂值（有有效快照才写，无效不写）——
  #   之前无条件写 0 会把驱动原厂的非 0 限流/温控默认值抹掉。
  restore_gate
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
