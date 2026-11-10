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
. "$DIR/lib/apl.sh"

# 电池子系统（BAA/PSS/fv/cycle/volt/cloud/APL）存在时，其守护负责这些节点；
# 引擎只保留功率/温控/背屏，避免两边同时写 cycle/fv/term/pd_verifed。
if [ -f "$MODDIR/battery/scripts/fv_guard.sh" ] && [ -f "$MODDIR/battery/service.sh" ]; then
  BT_GUARDS_EXTERNAL=1
else
  BT_GUARDS_EXTERNAL=0
fi

POLL_INTERVAL=$(profile_get poll_interval 3)
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

battery_guard_tick() {
  # 循环伪装：FG 回滚时自动重压。qmax_cyclecount 是不受伪装影响的权威值，只读不写。
  _cf=$(cfg_get cycle_fake "")
  if [ -n "$_cf" ]; then
    # 首次伪装前锁存真实值，卸载时 battery_restore 靠它写回。
    if [ ! -f "$BAT_STATE/cycle_real" ]; then
      _cr=$(cat /sys/class/xm_power/fg_master/qmax_cyclecount 2>/dev/null)
      case "$_cr" in ''|*[!0-9]*) _cr=$(cat /sys/class/xm_power/fg_master/cyclecount 2>/dev/null) ;; esac
      case "$_cr" in
        ''|*[!0-9]*) ;;
        *)
          if [ "$_cr" != "$_cf" ]; then
            mkdir -p "$BAT_STATE" 2>/dev/null
            printf '%s' "$_cr" > "$BAT_STATE/cycle_real" 2>/dev/null
          fi ;;
      esac
    fi
    for _n in /sys/class/xm_power/fg_master/cyclecount \
              /sys/class/power_supply/battery/cycle_count; do
      [ -f "$_n" ] && [ -w "$_n" ] || continue
      [ "$(cat "$_n" 2>/dev/null)" = "$_cf" ] || { chmod 0666 "$_n" 2>/dev/null; echo "$_cf" > "$_n"; } 2>/dev/null
    done
  fi
  # 关机电压：放电中电压低于阈值执行软关机。
  _tv=$(cfg_get term_v "")
  if [ -n "$_tv" ] && [ "$IS_CHARGING" != "1" ]; then
    case "$_tv" in ''|*[!0-9]*) _tv="" ;; esac
    [ -n "$_tv" ] && [ $((BAT_VOLT / 1000)) -le "$_tv" ] && { log "电压 $((BAT_VOLT / 1000))mV ≤ 关机阈值 ${_tv}mV，执行关机"; reboot -p 2>/dev/null || svc power shutdown 2>/dev/null; }
  fi
  # 公版 PPS：验证节点与 freepps 开关保持一致（两节点 + 回读校验在 pps_apply 里）。
  pps_tick
  # smart_fv 守护：充电中才压强 0（解除满充电压限制）；非充电必须写回有效值，
  # 否则手机管家读到 0 会不显示电池健康度。
  if [ "$(cfg_get unlock 1)" = "1" ]; then
    _sf=/sys/class/xm_power/charger/smart_charge/smart_fv
    _fvd=/sys/class/qcom-battery/fv_down
    if [ "$IS_CHARGING" = "1" ]; then
      [ -e "$_sf" ] && [ "$(cat "$_sf" 2>/dev/null)" != "0" ] && { chmod 0664 "$_sf" 2>/dev/null; echo 0 > "$_sf"; } 2>/dev/null
      [ -e "$_fvd" ] && [ "$(cat "$_fvd" 2>/dev/null)" != "0" ] && { chmod 0664 "$_fvd" 2>/dev/null; echo 0 > "$_fvd"; } 2>/dev/null
    elif [ -e "$_sf" ]; then
      _sfv=$(cat "$_sf" 2>/dev/null)
      case "$_sfv" in
        ''|0) ;;
        *)
          # 快照只在读到非 0 时落盘：升级/旧版残留的 0 不能当成原值。
          [ -f "$BAT_STATE/fv_default" ] || printf '%s' "$_sfv" > "$BAT_STATE/fv_default" 2>/dev/null ;;
      esac
      if [ "$_sfv" = "0" ]; then
        _fd=$(cat "$BAT_STATE/fv_default" 2>/dev/null)
        case "$_fd" in
          ''|0|*[!0-9]*) ;;
          *) chmod 0664 "$_sf" 2>/dev/null; echo "$_fd" > "$_sf" 2>/dev/null ;;
        esac
      fi
    fi
  fi
}

run_tick() {
  TICK_N=$((TICK_N + 1))
  ensure_thermal_dir
  hitemp_guard

  _label=""
  if [ -f "$MODDIR/disable" ]; then
    _label=disabled
  elif [ "$(json_get enabled true)" = "false" ]; then
    _label=off
  fi
  if [ -n "$_label" ]; then
    release_charge
    rear_stop
    if [ "$(cat "$CONFDIR/current_mode" 2>/dev/null)" != "$_label" ]; then
      apply_default
      printf '%s' "$_label" > "$CONFDIR/current_mode"
      update_status "$_label"
    fi
    return 0
  fi

  hyperos_keepalive
  sample

  # 云控屏蔽：云端下发目录巡检隔离，约每 20 拍（1 分钟）一次。
  [ $((TICK_N % 20)) = 1 ] && cloud_tick

  _on=0; screen_on && _on=1
  _now=$(date +%s)
  _last_fg=$(cat "$CONFDIR/.fg_time" 2>/dev/null); [ -z "$_last_fg" ] && _last_fg=0
  if [ "$_on" = "1" ]; then
    [ $((_now - _last_fg)) -ge "$FG_INTERVAL" ] && { printf '%s' "$(foreground_pkg)" > "$CONFDIR/.fg_pkg"; printf '%s' "$_now" > "$CONFDIR/.fg_time"; }
  else
    : > "$CONFDIR/.fg_pkg"
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
    apply_bypass
    write_status_json
    [ "$BT_GUARDS_EXTERNAL" = "1" ] || battery_guard_tick
    rear_tick
    return 0
  fi

  # 离开旁路时先交还充电节点，再应用功率档。
  [ -f "$CONFDIR/bypass_active" ] && release_charge
  # APL：每拍算一次 inf 档电流目标（写 state/apl_target_ma，供 power_apply 读取）。
  apl_tick
  power_apply "$(effective_tier)"

  _target="default"
  if [ "$IS_CHARGING" = "1" ] && [ "$MTK_MODE" != "1" ]; then
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
  if [ "$(json_get game_mode true)" = "true" ] && [ "$_on" = "1" ] && is_gaming; then
    _gaming=1
    _target=$(json_get game_level unlimited)
  fi

  # 场景标记：normal（非充电非游戏）/ charge / game
  _scene="normal"
  [ "$IS_CHARGING" = "1" ] && [ "$MTK_MODE" != "1" ] && _scene="charge"
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

  [ "$BT_GUARDS_EXTERNAL" = "1" ] || battery_guard_tick
  rear_tick
  update_status "$(cat "$CONFDIR/current_mode" 2>/dev/null)"
  write_status_json
}

bootstrap || log "初始化失败，稍后重试"
factory_backup
save_charge_orig
data_detect >/dev/null 2>&1

TICK_N=0
if [ "$1" = "--once" ]; then
  run_tick
  exit 0
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

# 开机先恢复原生背屏时钟，防止上次卡片投屏失败后重启仍黑屏。
rear_boot_recover

while true; do
  run_tick
  # 充电中快循环：档位切换、闭环反馈、inf 安顿都需要更短的响应时间。
  if [ "${IS_CHARGING:-0}" = "1" ]; then
    sleep "$CHARGE_INTERVAL"
  else
    sleep "$POLL_INTERVAL"
  fi
done
