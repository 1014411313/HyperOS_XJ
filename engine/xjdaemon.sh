#!/system/bin/sh
# engine/xjdaemon.sh — 引擎唯一常驻进程。一个循环包掉：
#   采样 → 温控场景判定 → 旁路 → 功率档 → 电池守卫 → 背屏喂数 → 状态快照。

DIR=$(cd "$(dirname "$0")" && pwd)
. "$DIR/lib/env.sh"
. "$DIR/lib/data.sh"
. "$DIR/lib/power.sh"
. "$DIR/lib/thermal.sh"
. "$DIR/lib/rear.sh"
. "$DIR/lib/battery.sh"
. "$DIR/lib/pps.sh"
. "$DIR/lib/apl.sh"
. "$DIR/lib/diag_log.sh"
. "$DIR/lib/retention.sh"

# 电池子系统（BAA/PSS/fv/cycle/volt/cloud/APL）存在时，其守护负责这些节点；
# 引擎只保留功率/温控/背屏，避免两边同时写 cycle/fv/term/pd_verifed。
if [ -f "$MODDIR/battery/scripts/fv_guard.sh" ] && [ -f "$MODDIR/battery/service.sh" ]; then
  BT_GUARDS_EXTERNAL=1
else
  BT_GUARDS_EXTERNAL=0
fi

POLL_INTERVAL=$(profile_get poll_interval 5)
FG_INTERVAL=$(profile_get foreground_interval 10)
CHARGE_INTERVAL=$(profile_get charge_interval 1)

screen_on() { [ "$(dumpsys deviceidle get screen 2>/dev/null)" != "false" ]; }

foreground_pkg() {
  _fg=$(dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' | sed -e 's/.*mCurrentFocus=//' -e 's/}.*//' -e 's/.*[ {]//')
  [ -z "$_fg" ] && _fg=$(dumpsys activity activities 2>/dev/null | grep -m1 'mResumedActivity' | sed -e 's/.*mResumedActivity=//' -e 's/}.*//' -e 's/.*[ {]//')
  case "$_fg" in
    */*) echo "${_fg%%/*}" ;;
    *.) echo "" ;;
    *) case "$_fg" in *[!A-Za-z0-9._]*) echo "" ;; *) echo "$_fg" ;; esac ;;
  esac
}

is_gaming() {
  _pkgs=$(json_get game_apps "")
  [ -n "$_pkgs" ] || return 1
  _fg=$(cat "$CONFDIR/.fg_pkg" 2>/dev/null)
  [ -n "$_fg" ] || return 1
  for _p in $(echo "$_pkgs" | tr '|' ' '); do [ "$_fg" = "$_p" ] && return 0; done
  return 1
}

charge_gov_limit() {
  case "$1" in
    level_1) echo 45 ;; level_2) echo 40 ;; level_3) echo 35 ;;
    level_4) echo 31 ;; level_5) echo 26 ;; *) echo "" ;;
  esac
}

plan_in_window() {
  _s=$(json_get power_plan_start 23); _e=$(json_get power_plan_end 7)
  case "$_s" in ''|*[!0-9]*) _s=23 ;; esac
  case "$_e" in ''|*[!0-9]*) _e=7 ;; esac
  _s=${_s#0}; [ -n "$_s" ] || _s=0
  _e=${_e#0}; [ -n "$_e" ] || _e=0
  _h=$(date +%H); _h=${_h#0}; [ -n "$_h" ] || _h=0
  if [ "$_s" -eq "$_e" ]; then return 0
  elif [ "$_s" -lt "$_e" ]; then [ "$_h" -ge "$_s" ] && [ "$_h" -lt "$_e" ]
  else [ "$_h" -ge "$_s" ] || [ "$_h" -lt "$_e" ]; fi
}

# 有效功率档 = 用户档，被充电计划/温控联动向下收紧，取最严的一档。
effective_tier() {
  _tier=$(power_tier)
  if [ "$(json_get power_plan_on false)" = "true" ] && plan_in_window; then
    _m=$(norm_power "$(json_get power_plan_mode slow)")
    [ "$(power_rank "$_m")" -lt "$(power_rank "$_tier")" ] && _tier="$_m"
  fi
  if [ "$(json_get charge_gov true)" = "true" ]; then
    _mode=$(cat "$CONFDIR/current_mode" 2>/dev/null)
    _lim=$(charge_gov_limit "$_mode")
    if [ -n "$_lim" ] && [ $((BAT_TEMP / 10)) -ge "$_lim" ]; then
      _want=mid
      [ $((BAT_TEMP / 10)) -ge $((_lim + 4)) ] && _want=slow
      [ "$(power_rank "$_want")" -lt "$(power_rank "$_tier")" ] && _tier="$_want"
    fi
  fi
  echo "$_tier"
}

bypass_decide() {
  _tc=$1
  [ "$(cat "$CONFDIR/bypass_manual" 2>/dev/null)" = "1" ] && { echo "1:manual"; return 0; }
  _t_on=$(json_get bypass_temp_on 0); case "$_t_on" in ''|*[!0-9]*) _t_on=0 ;; esac
  _t_off=$(json_get bypass_temp_off 0); case "$_t_off" in ''|*[!0-9]*) _t_off=0 ;; esac
  _lvl=$(json_get bypass_level 0); case "$_lvl" in ''|*[!0-9]*) _lvl=0 ;; esac
  # 温度触发带迟滞：已处于旁路时用回落阈值，否则用开启阈值。
  if [ "$_t_on" -gt 0 ]; then
    _eff_on=$_t_on
    [ -f "$CONFDIR/bypass_active" ] && [ "$_t_off" -gt 0 ] && [ "$_t_off" -lt "$_t_on" ] && _eff_on=$_t_off
    [ "$_tc" -ge "$_eff_on" ] && { echo "1:temp"; return 0; }
  fi
  [ "$_lvl" -gt 2 ] && [ "$_lvl" -le 100 ] && [ "$BAT_CAP" -ge "$_lvl" ] && { echo "1:level"; return 0; }
  [ "$(cat "$CONFDIR/bypass_allow" 2>/dev/null)" = "1" ] && { echo "1:game"; return 0; }
  echo 0
}

apply_fps() {
  _fps=$1
  case "$_fps" in ''|*[!0-9]*) _fps=0 ;; esac
  [ "$(cat "$CONFDIR/.fps_applied" 2>/dev/null)" = "$_fps" ] && return 0
  if [ "$_fps" = "0" ]; then
    _pk=$(cat "$CONFDIR/rt_fps_peak_orig" 2>/dev/null); _mn=$(cat "$CONFDIR/rt_fps_min_orig" 2>/dev/null)
    [ -n "$_pk" ] && settings put system peak_refresh_rate "$_pk" 2>/dev/null
    [ -n "$_mn" ] && settings put system min_refresh_rate "$_mn" 2>/dev/null
    rm -f "$CONFDIR/rt_fps_peak_orig" "$CONFDIR/rt_fps_min_orig" 2>/dev/null
  else
    [ -f "$CONFDIR/rt_fps_peak_orig" ] || {
      settings get system peak_refresh_rate 2>/dev/null > "$CONFDIR/rt_fps_peak_orig"
      settings get system min_refresh_rate 2>/dev/null > "$CONFDIR/rt_fps_min_orig"
    }
    settings put system min_refresh_rate "$_fps" 2>/dev/null
    settings put system peak_refresh_rate "$_fps" 2>/dev/null
  fi
  printf '%s' "$_fps" > "$CONFDIR/.fps_applied"
}

runtime_now() {
  IFS=' ' read -r _uptime _idle < /proc/uptime
  XJ_NOW=${_uptime%%.*}
}

app_visible() {
  local until now
  until=
  IFS= read -r until < /storage/emulated/0/Android/data/com.hyperos.xj/files/visible.until 2>/dev/null
  case "$until" in ''|*[!0-9]*) return 1 ;; esac
  now=$(date +%s)
  [ "$until" -gt "$now" ] && [ "$until" -le $((now + 20)) ]
}

publish_tick() {
  local mode interval
  mode=$(cat "$CONFDIR/current_mode" 2>/dev/null)
  update_status "$mode"
  interval=30
  app_visible && interval=1
  if [ "$XJ_CONFIG_CHANGED" = 1 ] || [ "$XJ_CONNECTION_CHANGED" = 1 ] ||
     [ "${XJ_STATUS_MODE:-}" != "$mode" ] || [ $((XJ_NOW - ${XJ_STATUS_AT:-0})) -ge "$interval" ]; then
    write_status_json
    XJ_STATUS_AT=$XJ_NOW
    XJ_STATUS_MODE=$mode
  fi
  interval=60
  [ "$USB_CONNECTED:$WIRELESS_CONNECTED" = 0:0 ] || interval=5
  if [ "$XJ_CONNECTION_CHANGED" = 1 ] || [ "$XJ_CONFIG_CHANGED" = 1 ] ||
     [ $((XJ_NOW - ${XJ_DIAG_AT:-0})) -ge "$interval" ]; then
    diag_log_tick
    XJ_DIAG_AT=$XJ_NOW
  fi
}

connection_read() {
  local node value _connection
  USB_CONNECTED=0; WIRELESS_CONNECTED=0
  for node in /sys/class/power_supply/usb/present /sys/class/power_supply/usb/online; do
    if [ -r "$node" ] && IFS= read -r value < "$node"; then
      [ "$value" != 1 ] || USB_CONNECTED=1
    fi
  done
  for node in /sys/class/power_supply/wireless/present /sys/class/power_supply/wireless/online; do
    if [ -r "$node" ] && IFS= read -r value < "$node"; then
      [ "$value" != 1 ] || WIRELESS_CONNECTED=1
    fi
  done
  [ "${IS_CHARGING:-0}" != 1 ] || [ "$WIRELESS_CONNECTED" = 1 ] || USB_CONNECTED=1
  XJ_CONNECTION_CHANGED=0
  _connection="$USB_CONNECTED:$WIRELESS_CONNECTED:${IS_CHARGING:-0}"
  [ "${XJ_CONNECTION:-}" = "$_connection" ] || XJ_CONNECTION_CHANGED=1
  XJ_CONNECTION=$_connection
}

run_tick() {
  local _label _on _games _game_enabled _want_bypass _d _target _ts _te _tl _h _sl _gaming _scene _t_limit _tb _src _thermal_rc
  TICK_N=$((TICK_N + 1))
  runtime_now
  runtime_config_refresh
  if [ -z "$THERMAL_DAEMON" ] || [ -z "$THERMAL_PATH" ]; then
    bootstrap || return 1
  fi
  sample
  connection_read
  if [ "$XJ_CONFIG_CHANGED" = 1 ] || [ $((XJ_NOW - ${XJ_PROPERTY_AT:-0})) -ge 60 ]; then
    hitemp_guard
    hyperos_keepalive
    XJ_PROPERTY_AT=$XJ_NOW
  fi

  _label=""
  if [ -f "$MODDIR/disable" ]; then
    _label=disabled
  elif [ "$(json_get enabled true)" = "false" ]; then
    _label=off
  fi
  # 公版 PPS 仅由持锁的常驻进程写入；禁用时等拔线归还节点。
  if [ "$XJ_PPS_OWNER" = 1 ]; then
    if [ -n "$_label" ]; then
      pps_tick 0
    else
      pps_tick "$(cfg_get freepps 0)"
    fi
  fi
  if [ -n "$_label" ]; then
    if [ "${XJ_DISABLED_HANDLED:-}" != "$_label" ]; then
      release_charge
      rear_stop
      apply_default || return 1
      printf '%s' "$_label" > "$CONFDIR/current_mode"
      update_status "$_label"
      XJ_DISABLED_HANDLED=$_label
      write_status_json
    fi
    return 0
  fi
  XJ_DISABLED_HANDLED=

  # 诊断数据保留：只留最近 1 天，每天清一次（函数内自查时间戳，未到期直接返回）。
  if [ $((XJ_NOW - ${XJ_RETENTION_AT:-0})) -ge 3600 ] || [ "${XJ_RETENTION_AT:-0}" = 0 ]; then
    retention_tick
    XJ_RETENTION_AT=$XJ_NOW
  fi

  _on=0
  _games=$(json_get game_apps "")
  _game_enabled=$(json_get game_mode true)
  if [ "$USB_CONNECTED" = 1 ] || [ "$WIRELESS_CONNECTED" = 1 ] || { [ "$_game_enabled" = true ] && [ -n "$_games" ]; }; then
    screen_on && _on=1
  fi
  if [ "$_on" = 1 ] && [ "$_game_enabled" = true ] && [ -n "$_games" ]; then
    if [ "$XJ_CONFIG_CHANGED" = 1 ] || [ $((XJ_NOW - ${XJ_FG_AT:-0})) -ge "$FG_INTERVAL" ]; then
      foreground_pkg > "$CONFDIR/.fg_pkg"
      XJ_FG_AT=$XJ_NOW
    fi
  elif [ "${XJ_FG_AT:-0}" != 0 ]; then
    : > "$CONFDIR/.fg_pkg"
    XJ_FG_AT=0
  fi

  _want_bypass=0
  if [ "$MTK_MODE" != "1" ] && [ "$IS_CHARGING" = "1" ]; then
    if [ "$(json_get game_mode true)" = "true" ] && [ "$_on" = "1" ] && [ "$(json_get game_bypass false)" = "true" ] && is_gaming; then
      printf '1' > "$CONFDIR/bypass_allow"
    else
      rm -f "$CONFDIR/bypass_allow" 2>/dev/null
    fi
    _d=$(bypass_decide $((BAT_TEMP / 10)))
    case "$_d" in 1:*) _want_bypass=1 ;; 1) _want_bypass=1 ;; esac
  fi
  if [ "$_want_bypass" = "1" ]; then
    printf '1' > "$CONFDIR/bypass_active"
    apply_bypass || return 1
    publish_tick
    [ "$BT_GUARDS_EXTERNAL" = "1" ] || battery_guard_tick
    rear_tick
    return 0
  fi

  # 离开旁路时先交还充电节点，再应用功率档。
  [ -f "$CONFDIR/bypass_active" ] && release_charge
  # APL：每拍算一次 inf 档电流目标（写 state/apl_target_ma，供 power_apply 读取）。
  if [ "$USB_CONNECTED" = 1 ] && [ "$WIRELESS_CONNECTED" != 1 ]; then
    apl_tick
    power_apply "$(effective_tier)"
    XJ_POWER_ACTIVE=1
  elif [ "${XJ_POWER_ACTIVE:-0}" = 1 ] || [ -f "$STATE/apl_target_ma" ]; then
    rm -f "$STATE/apl_target_ma" "$STATE/apl_urgent" 2>/dev/null
    power_restore
    XJ_POWER_ACTIVE=0
  fi

  _target="default"
  if { [ "$USB_CONNECTED" = 1 ] || [ "$WIRELESS_CONNECTED" = 1 ]; } && [ "$MTK_MODE" != 1 ]; then
    if [ "$(json_get charge_mode true)" = "true" ]; then
      _target=$(json_get charge_level unlimited)
      _ts=$(json_get time_start 0); _te=$(json_get time_end 0); _tl=$(json_get time_level level_1)
      case "$_ts" in ''|*[!0-9]*) _ts=0 ;; esac
      case "$_te" in ''|*[!0-9]*) _te=0 ;; esac
      _h=$(date +%H); _h=${_h#0}; [ -n "$_h" ] || _h=0
      if [ "$_ts" -ne 0 ] && [ "$_te" -ne 0 ] && [ "$_ts" -ne "$_te" ]; then
        if [ "$_ts" -gt "$_te" ]; then { [ "$_h" -ge "$_ts" ] || [ "$_h" -lt "$_te" ]; } && _target="$_tl"; else { [ "$_h" -ge "$_ts" ] && [ "$_h" -lt "$_te" ]; } && _target="$_tl"; fi
      fi
      [ "$_on" = "0" ] && { _sl=$(json_get sleep_level ""); [ -n "$_sl" ] && _target="$_sl"; }
    else
      _target="default"
    fi
  else
    _target=$(json_get default_mode default)
  fi

  _gaming=0
  if [ "$_game_enabled" = true ] && [ -n "$_games" ] && [ "$_on" = 1 ] && is_gaming; then
    _gaming=1
    _target=$(json_get game_level unlimited)
  fi

  # 场景标记：normal（非充电非游戏）/ charge / game
  _scene="normal"
  { [ "$USB_CONNECTED" = 1 ] || [ "$WIRELESS_CONNECTED" = 1 ]; } && [ "$MTK_MODE" != 1 ] && _scene=charge
  [ "$_gaming" = "1" ] && _scene="game"

  # 普通/游戏场景：电池温度 ≥ 档位上限 → 交回官方温控（解除档位对温度的接管）；
  # 回落到「上限-2℃」再恢复档位；带滞回防抖动。充电场景不交回，功率由 APL 温度线处理。
  if [ "$_scene" = "normal" ] || [ "$_scene" = "game" ]; then
    _t_limit=$(apl_level_temp_c "$_target")
    if [ -n "$_t_limit" ]; then
      _tb=$(( BAT_TEMP / 10 ))
      if [ "$_tb" -ge "$_t_limit" ]; then
        printf '%s' "$_target" > "$CONFDIR/.thermal_handback_mode" 2>/dev/null
        : > "$CONFDIR/.thermal_handback" 2>/dev/null
      elif [ -f "$CONFDIR/.thermal_handback" ] && [ "$_tb" -le $(( _t_limit - 2 )) ]; then
        rm -f "$CONFDIR/.thermal_handback" 2>/dev/null
      fi
      [ -f "$CONFDIR/.thermal_handback" ] && _target="default"
    fi
  else
    rm -f "$CONFDIR/.thermal_handback" "$CONFDIR/.thermal_handback_mode" 2>/dev/null
  fi

  valid_mode "$_target" || { _target=$(cat "$CONFDIR/current_mode" 2>/dev/null); valid_mode "$_target" || _target="default"; }
  _target=$(guard_apply "$_target")

  [ "$_gaming" = "1" ] && apply_fps "$(json_get game_fps 0)" || apply_fps 0

  case "$_target" in
    default) apply_default ;;
    unlimited) apply_conf_mode unlimited "$MODDIR/t_blank" ;;
    level_1|level_2|level_3|level_4|level_5)
      _src=$(src_for_mode "$_target")
      if [ -s "$_src" ] && [ "$(wc -c < "$_src" 2>/dev/null || echo 0)" -ge 512 ]; then
        apply_conf_mode "$_target" "$_src"
      else
        apply_default
      fi
      ;;
    *) apply_default ;;
  esac
  _thermal_rc=$?

  [ "$BT_GUARDS_EXTERNAL" = "1" ] || battery_guard_tick
  rear_tick
  publish_tick
  return "$_thermal_rc"
}

bootstrap || log "初始化失败，稍后重试"
factory_backup
[ -s "$CACHE/ps.cache" ] && [ -s "$CACHE/detail.list" ] || data_detect >/dev/null 2>&1

TICK_N=0
XJ_PPS_OWNER=0
if [ "$1" = "--once" ]; then
  run_tick
  exit $?
fi

# 单实例：mkdir 原子锁 + pid 存活校验。
# 不用 flock：实测小米15 Pro（Android 17）的 toybox flock 不支持 `flock -n <fd>`，
# 会报 Bad file descriptor，导致每次启动都被误判为「已有实例」而退出。
# 也不遍历 /proc 读 cmdline 找同名进程：目标退出时那个读会阻塞（bug-121）。
mkdir -p "$STATE" 2>/dev/null
rm -f "$STATE/daemon.lock" 2>/dev/null
LOCKDIR="$STATE/daemon.lock.d"
_lock_hold() {
  mkdir "$LOCKDIR" 2>/dev/null || return 1
  printf '%s\n' "$$" > "$LOCKDIR/pid" 2>/dev/null
  return 0
}
if ! _lock_hold; then
  _lp=$(cat "$LOCKDIR/pid" 2>/dev/null)
  _alive=0
  case "$_lp" in
    ''|*[!0-9]*) ;;
    *) kill -0 "$_lp" 2>/dev/null && [ "$(cat "/proc/$_lp/comm" 2>/dev/null)" = "sh" ] && _alive=1 ;;
  esac
  if [ "$_alive" = "1" ]; then
    log "已有 daemon 实例(pid $_lp)，退出"
    exit 0
  fi
  rm -rf "$LOCKDIR" 2>/dev/null
  _lock_hold || { log "daemon 锁获取失败，退出"; exit 0; }
fi
trap 'rm -rf "$LOCKDIR" 2>/dev/null' EXIT INT TERM HUP
printf '%s\n' "$$" > "$STATE/daemon.pid" 2>/dev/null
XJ_PPS_OWNER=1

# 开机先恢复原生背屏时钟，防止上次卡片投屏失败后重启仍黑屏。
rear_boot_recover

while true; do
  run_tick
  # 充电中快循环：档位切换、闭环反馈、inf 安顿都需要更短的响应时间。
  _sleep=$POLL_INTERVAL
  app_visible && _sleep=1
  if [ "${USB_CONNECTED:-0}" = 1 ] || [ "${IS_CHARGING:-0}" = 1 ]; then
    _sleep=$CHARGE_INTERVAL
  fi
  _battery_interval=$(battery_poll_interval)
  case "$_battery_interval" in ''|*[!0-9]*) ;; *) [ "$_battery_interval" -ge "$_sleep" ] || _sleep=$_battery_interval ;; esac
  sleep "$_sleep"
done
