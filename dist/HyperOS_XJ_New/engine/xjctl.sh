#!/system/bin/sh
# engine/xjctl.sh — 引擎统一入口。App/WebUI 只调这一个文件，
# 所有子命令都在本进程内完成，不再 spawn 多个 root 脚本。

DIR=$(cd "$(dirname "$0")" && pwd)

# BAA 必须挂载在 PID1 mount namespace 才能被 batteryantiaging 看到。
if [ "$1" = "battery" ] && [ "$XJ_IN_PID1" != "1" ]; then
  for _ns in /system/bin/nsenter /system/xbin/nsenter \
             /data/adb/ksu/bin/nsenter /data/adb/magisk/nsenter; do
    [ -x "$_ns" ] || continue
    exec "$_ns" -t 1 -m -- /system/bin/env XJ_IN_PID1=1 /system/bin/sh "$0" "$@"
  done
  export XJ_IN_PID1=1 XJ_NO_NSENTER=1
fi

. "$DIR/lib/env.sh"
. "$DIR/lib/data.sh"
. "$DIR/lib/power.sh"
. "$DIR/lib/battery.sh"
. "$DIR/lib/apl.sh"

usage() {
  cat <<'EOF'
用法: xjctl.sh <命令> [参数]
  status                 输出统一状态 JSON
  sysinfo                输出 机型名/代号/系统/底包
  read                   输出实时 KV（零 fork 采样）
  detect                 重扫详情节点
  power get|set <档位>|status|restore
  thermal get|set <档位>|gov on|off|status
  battery unlock|pps <on|off|restore|status>|cloud <on|off|status>|apl <on|off|status>|cycle <N|clear|get>|volt <term|off> <值>|restore|status
  setting get <键>|set <键> <值>|dump
  bypass on|off|status
  rear on|off|set key=value...|status
  dtbo status|backup [目录]|resolve|flash <镜像>|flashlocal|haslocal
  dtbo list|dirs|adddir <绝对目录>|deldir <绝对目录>
  log [行数]             查看最近日志（默认 200 行）
  diag pack              打包日志与脱敏配置到 /sdcard/Download/
  apply                  立即跑一轮主循环（单次）
档位: inf fast mid slow；温控档位: default unlimited level_1..level_5
EOF
}

# ---------- 循环伪装（参照 v3.12.9 unlock.sh do_cycle） ----------
CYCLE_NODE=/sys/class/xm_power/fg_master/cyclecount
CYCLE_NODE2=/sys/class/power_supply/battery/cycle_count
CYCLE_REAL_NODE=/sys/class/xm_power/fg_master/qmax_cyclecount

# 真实循环值：qmax_cyclecount 不受伪装影响（权威），回退上次锁存的 cycle_real。
cycle_real_value() {
  _r=$(cat "$CYCLE_REAL_NODE" 2>/dev/null)
  case "$_r" in ''|*[!0-9]*) _r=$(cat "$BT_STATE/cycle_real" 2>/dev/null) ;; esac
  case "$_r" in ''|*[!0-9]*) return 1 ;; esac
  printf '%s' "$_r"
}

battery_cycle_set() {
  _v="$1"
  case "$_v" in
    *[!0-9]*) echo "数值无效: $_v"; return 2 ;;
  esac
  # 拒绝 0：实测 FG 把 0 当无效值立即写回真实循环数，配置=0 与节点=真实值永久矛盾；
  # 1~9999 均可正常伪装，取消伪装走 clear。
  if [ "$_v" = "0" ]; then
    echo "数值无效: 0（FG 不接受 0，会立刻写回真实值）"
    echo "  循环伪装请填 1 ~ 9999；要取消伪装用 cycle clear"
    return 2
  fi
  case "$_v" in
    [1-9]|[1-9][0-9]|[1-9][0-9][0-9]|[1-9][0-9][0-9][0-9]) ;;
    *) echo "数值超出范围（1~9999）: $_v"; return 2 ;;
  esac
  # 先捕获真实值再写伪装：qmax 是权威；qmax 不可用时回退当前节点，
  # 但当前节点可能是旧伪装值，等于目标值时不能落盘，否则 clear 会把伪装值当真实值。
  if [ ! -f "$BT_STATE/cycle_real" ]; then
    _r=$(cat "$CYCLE_REAL_NODE" 2>/dev/null)
    case "$_r" in ''|*[!0-9]*) _r=$(cat "$CYCLE_NODE" 2>/dev/null) ;; esac
    case "$_r" in
      ''|*[!0-9]*)
        echo "警告: 未能读到真实循环值，伪装后 clear/卸载只能靠 qmax_cyclecount 恢复"
        cfg_set cycle_fake "$_v"
        _write_nodes=1 ;;
      *)
        if [ "$_r" != "$_v" ]; then
          mkdir -p "$BT_STATE" 2>/dev/null
          printf '%s' "$_r" > "$BT_STATE/cycle_real" 2>/dev/null
        fi
        cfg_set cycle_fake "$_v"
        _write_nodes=1 ;;
    esac
  else
    cfg_set cycle_fake "$_v"
    _write_nodes=1
  fi
  # 立即写节点，不等下一拍守护
  if [ "$_write_nodes" = "1" ]; then
    for _n in "$CYCLE_NODE" "$CYCLE_NODE2"; do
      [ -e "$_n" ] || continue
      chmod 0666 "$_n" 2>/dev/null
      echo "$_v" > "$_n" 2>/dev/null
    done
  fi
  echo "cycle_fake=$(cfg_get cycle_fake "") node=$(cat "$CYCLE_NODE" 2>/dev/null) real=$(cat "$BT_STATE/cycle_real" 2>/dev/null)"
}

battery_cycle_clear() {
  cfg_set cycle_fake ""
  # 立即把两节点写回真实值，不等卸载：伪装时写了两个节点，清除也必须都还原，
  # 否则 battery/cycle_count 停在伪装值直到重启，系统设置里看到的仍是假值。
  _r=$(cycle_real_value)
  if [ -n "$_r" ]; then
    for _n in "$CYCLE_NODE" "$CYCLE_NODE2"; do
      [ -e "$_n" ] || continue
      [ "$(cat "$_n" 2>/dev/null)" = "$_r" ] && continue
      chmod 0666 "$_n" 2>/dev/null
      echo "$_r" > "$_n" 2>/dev/null
    done
    echo "cleared real=$_r node=$(cat "$CYCLE_NODE" 2>/dev/null)"
  else
    echo "cleared（未读到真实值，重启后由 FG 自动恢复）"
  fi
}

status_json() {
  _tmp="$STATE/status.$$.frag"; : > "$_tmp"
  data_read 2>/dev/null | while IFS= read -r _l; do
    _k=${_l%%=*}; _v=${_l#*=}
    js_line "$_k" "$_v" str >> "$_tmp"
  done
  js_line thermal_mode "$(cat "$CONFDIR/current_mode" 2>/dev/null)" str >> "$_tmp"
  js_line thermal_default "$(json_get default_mode default)" str >> "$_tmp"
  js_line power_tier "$(power_tier)" str >> "$_tmp"
  js_line power_applied "$(cat "$BT_STATE/power_applied" 2>/dev/null)" str >> "$_tmp"
  js_line unlock "$(cfg_get unlock 1)" str >> "$_tmp"
  js_line freepps "$(cfg_get freepps 0)" str >> "$_tmp"
  js_line cloud_guard "$(cfg_get cloud_guard 1)" str >> "$_tmp"
  js_line apl_enable "$(cfg_get apl_enable 0)" str >> "$_tmp"
  # ---- 守护/功能存活上报（参照 v3.12.9 status_kv.sh）：
  #      让「开关开着但功能实际没跑」可见。读不到（进程不在/文件不存在）给空串，不报错。 ----
  _dpid=$(cat "$STATE/daemon.pid" 2>/dev/null)
  _dup=""
  case "$_dpid" in
    ''|*[!0-9]*) _dpid="" ;;
    *) [ -d "/proc/$_dpid" ] || _dpid="" ;;
  esac
  if [ -n "$_dpid" ]; then
    _mt=$(stat -c %Y "/proc/$_dpid" 2>/dev/null)
    case "$_mt" in ''|*[!0-9]*) ;; *) _dup=$(( $(date +%s) - _mt )) ;; esac
  fi
  js_line daemon_pid "$_dpid" str >> "$_tmp"
  js_line daemon_uptime "$_dup" str >> "$_tmp"
  js_line power_last_write "$(cat "$BT_STATE/cg_last_write_ts" 2>/dev/null)" str >> "$_tmp"
  js_line power_sig "$(cat "$BT_STATE/power_sig" 2>/dev/null)" str >> "$_tmp"
  js_line apl_target_ma "$(cat "$STATE/apl_target_ma" 2>/dev/null)" str >> "$_tmp"
  js_line apl_state_mtime "$(stat -c %Y "$STATE/apl_state" 2>/dev/null)" str >> "$_tmp"
  js_line baa_state "$(baa_state)" str >> "$_tmp"
  js_line battery_state_mtime "$(stat -c %Y "$BT_STATE/cloud_guard.state" 2>/dev/null)" str >> "$_tmp"
  js_line cycle_fake "$(cfg_get cycle_fake "")" str >> "$_tmp"
  js_line term_v "$(cfg_get term_v "")" str >> "$_tmp"
  js_line bypass_manual "$([ -f "$CONFDIR/bypass_manual" ] && echo 1 || echo 0)" str >> "$_tmp"
  js_line rearscreen "$(cfg_get rearscreen 0)" str >> "$_tmp"
  js_line rearscreen_supported "$(pm path com.xiaomi.subscreencenter >/dev/null 2>&1 && echo 1 || echo 0)" str >> "$_tmp"
  js_line module_version "$(mod_version)" str >> "$_tmp"
  js_line kernel "$(uname -r 2>/dev/null)" str >> "$_tmp"
  js_line date "$(date +%F)" str >> "$_tmp"
  _st="$STATE/sysinfo.txt"; _ts="$STATE/sysinfo.ts"
  _now=$(date +%s); _last=$(cat "$_ts" 2>/dev/null)
  case "$_last" in ''|*[!0-9]*) _last=0 ;; esac
  if [ ! -s "$_st" ] || [ $((_now - _last)) -ge 60 ]; then
    # 临时名带 pid：daemon 与 xjctl status 可能并发进入此分支，固定名会互相踩
    _stmp="$_st.$$.tmp"
    data_sysinfo > "$_stmp" 2>/dev/null && mv -f "$_stmp" "$_st"
    rm -f "$_stmp" 2>/dev/null
    printf '%s' "$_now" > "$_ts"
  fi
  js_line device_name "$(sed -n 1p "$_st")" str >> "$_tmp"
  js_line device_code "$(sed -n 2p "$_st")" str >> "$_tmp"
  js_line os_version "$(sed -n 3p "$_st")" str >> "$_tmp"
  js_line base_firmware "$(sed -n 4p "$_st")" str >> "$_tmp"
  js_emit "$_tmp"
  rm -f "$_tmp"
}

case "$1" in
  status) status_json ;;
  sysinfo) data_sysinfo ;;
  read) data_read ;;
  detect) data_detect ;;

  power)
    case "$2" in
      get) power_tier; echo ;;
      set) _t=$(norm_power "$3"); cfg_set power_limit "$_t"; power_apply "$_t"; echo "$_t" ;;
      status) power_status ;;
      restore) power_restore; echo restored ;;
      *) usage; exit 9 ;;
    esac ;;

  thermal)
    case "$2" in
      get) json_get default_mode default; echo ;;
      set)
        case "$3" in default|unlimited|level_1|level_2|level_3|level_4|level_5) ;; *) echo "非法档位: $3"; exit 2 ;; esac
        json_set default_mode "$3" str
        echo "$3" ;;
      gov)
        case "$3" in
          on) json_set charge_gov true bool; echo on ;;
          off) json_set charge_gov false bool; echo off ;;
          *) json_get charge_gov true ;;
        esac ;;
      status) cat "$CONFDIR/status.json" 2>/dev/null ;;
      *) usage; exit 9 ;;
    esac ;;

  battery)
    case "$2" in
      boot) battery_boot ;;
      restore) battery_restore ;;
      unlock)
        case "$3" in
          on) battery_unlock_on ;;
          off) battery_unlock_off ;;
          *) echo "unlock=$(cfg_get unlock 1) baa=$(baa_state) pss=$(pss_state)" ;;
        esac ;;
      pps)
        case "$3" in
          on) cfg_set freepps 1; pps_apply 1 ;;
          off) cfg_set freepps 0; pps_apply 0 ;;
          restore) pps_apply 0 ;;
          *) pps_status ;;
        esac ;;
      cloud)
        case "$3" in
          on)
            cfg_set cloud_guard 1
            baa_refresh_src >/dev/null 2>&1
            baa_mount >/dev/null 2>&1
            setprop ctl.restart batteryantiaging 2>/dev/null
            ;;
          off)
            cfg_set cloud_guard 0
            cloud_restore >/dev/null 2>&1
            baa_refresh_src >/dev/null 2>&1
            baa_mount >/dev/null 2>&1
            setprop ctl.restart batteryantiaging 2>/dev/null
            ;;
          status) cloud_status ;;
          *) cfg_get cloud_guard 1 ;;
        esac ;;
      apl)
        case "$3" in
          on) cfg_set apl_enable 1 ;;
          off) cfg_set apl_enable 0; rm -f "$STATE/apl_target_ma" 2>/dev/null ;;
          status) apl_status ;;
          *) cfg_get apl_enable 0 ;;
        esac ;;
      cycle)
        case "$3" in
          clear) battery_cycle_clear ;;
          get|'')
            # 无参数=读当前（App 走 status JSON，这里给人眼用）：伪装值 + 真实值 + 两个节点。
            echo "cycle_fake=$(cfg_get cycle_fake "")"
            echo "cycle_real=$(cycle_real_value 2>/dev/null)"
            echo "node=$(cat "$CYCLE_NODE" 2>/dev/null)"
            echo "node2=$(cat "$CYCLE_NODE2" 2>/dev/null)"
            ;;
          *) battery_cycle_set "$3" ;;
        esac ;;
      volt)
        case "$3" in
          term) cfg_set term_v "$4" ;;
          off) cfg_set term_v "" ;;
          *) echo "term_v=$(cfg_get term_v "")" ;;
        esac ;;
      status)
        echo "unlock=$(cfg_get unlock 1)"
        echo "baa=$(baa_state)"
        echo "pss=$(pss_state)"
        echo "freepps=$(cfg_get freepps 0)"
        echo "cloud_guard=$(cfg_get cloud_guard 1)"
        echo "apl_enable=$(cfg_get apl_enable 0)"
        echo "cycle_fake=$(cfg_get cycle_fake "")"
        echo "term_v=$(cfg_get term_v "")"
        echo "power_limit=$(cfg_get power_limit inf)"
        ;;
      *) usage; exit 9 ;;
    esac ;;

  setting)
    case "$2" in
      get) json_get "$3" ""; echo ;;
      set)
        _k="$3"; _v="$4"
        case "$_v" in
          true|false) json_set "$_k" "$_v" bool ;;
          ''|*[!0-9.-]*) json_set "$_k" "$_v" str ;;
          *) json_set "$_k" "$_v" num ;;
        esac ;;
      dump)
        for _k in enabled default_mode charge_mode charge_level sleep_level \
                  time_start time_end time_level game_mode game_level game_apps game_fps \
                  game_bypass bypass_temp_on bypass_temp_off bypass_level bypass_current \
                  hyperos4 no_hitemp_stop charge_gov power_plan_on power_plan_start \
                  power_plan_end power_plan_mode; do
          printf '%s=%s\n' "$_k" "$(json_get "$_k" "")"
        done ;;
      *) usage; exit 9 ;;
    esac ;;

  bypass)
    case "$2" in
      on) printf '1' > "$CONFDIR/bypass_manual" ;;
      off) rm -f "$CONFDIR/bypass_manual" ;;
      status) [ -f "$CONFDIR/bypass_manual" ] && echo on || echo off ;;
      *) usage; exit 9 ;;
    esac ;;

  rear)
    . "$DIR/lib/rear.sh"
    case "$2" in
      on) cfg_set rearscreen 1; rear_ensure ;;
      off) cfg_set rearscreen 0; rear_stop; echo off ;;
      status) rear_status ;;
      set)
        shift 2
        [ $# -gt 0 ] || { echo "用法: rear set fields=... font_scale=... accent=... bg=..."; exit 2; }
        for _kv in "$@"; do
          case "$_kv" in *=*) ;; *) echo "非法参数: $_kv"; exit 2 ;; esac
          _k=${_kv%%=*}; _v=${_kv#*=}
          case "$_k" in
            fields)
              case "$_v" in ''|*[!a-z_,]*) echo "非法 fields: $_v"; exit 2 ;; esac
              cfg_set rear_fields "$_v" ;;
            font_scale)
              case "$_v" in 0.85|1.0|1.2) ;; *) echo "非法 font_scale: $_v"; exit 2 ;; esac
              cfg_set rear_font_scale "$_v" ;;
            accent)
              case "$_v" in \#[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]) ;; *) echo "非法 accent: $_v"; exit 2 ;; esac
              cfg_set rear_accent "$_v" ;;
            bg)
              case "$_v" in
                '') cfg_set rear_bg "" ;;
                color:\#[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F])
                  cfg_set rear_bg "$_v" ;;
                image:*)
                  _p=${_v#image:}
                  case "$_p" in
                    /*) ;;
                    *) echo "非法 bg: $_v"; exit 2 ;;
                  esac
                  case "$_p" in
                    *[!:/A-Za-z0-9_.-]*) echo "非法 bg: $_v"; exit 2 ;;
                  esac
                  cfg_set rear_bg "$_v" ;;
                *) echo "非法 bg: $_v"; exit 2 ;;
              esac ;;
            *) echo "未知键: $_k"; exit 2 ;;
          esac
        done
        [ "$(cfg_get rearscreen 0)" = "1" ] && rear_push_config
        echo "fields=$(cfg_get rear_fields "temp,soc,volt,cur") font_scale=$(cfg_get rear_font_scale "1.0") accent=$(cfg_get rear_accent "#38BDF8") bg=$(cfg_get rear_bg)"
        ;;
      *) usage; exit 9 ;;
    esac ;;

  dtbo)
    . "$DIR/lib/dtbo.sh"
    case "$2" in
      status) dtbo_status; exit $? ;;
      backup) dtbo_backup "$3"; exit $? ;;
      flash)  dtbo_flash "$3"; exit $? ;;
      flashlocal) dtbo_flashlocal; exit $? ;;
      download)   dtbo_download; exit $? ;;
      haslocal)   dtbo_haslocal; exit $? ;;
      resolve)    dtbo_resolve; exit $? ;;
      list)   dtbo_scan_list; exit $? ;;
      dirs)   dtbo_dirs_show; exit $? ;;
      adddir) dtbo_dirs_add "$3"; exit $? ;;
      deldir) dtbo_dirs_del "$3"; exit $? ;;
      *) usage; exit 9 ;;
    esac ;;

  update)
    . "$DIR/lib/update.sh"
    case "$2" in
      check) update_check; exit $? ;;
      *) usage; exit 9 ;;
    esac ;;

  log)
    . "$DIR/lib/diag.sh"
    diag_log "$2"; exit $? ;;

  diag)
    . "$DIR/lib/diag.sh"
    case "$2" in
      pack) diag_pack; exit $? ;;
      *) usage; exit 9 ;;
    esac ;;

  apply) sh "$DIR/xjdaemon.sh" --once ;;
  help|"") usage ;;
  *) usage; exit 9 ;;
esac
