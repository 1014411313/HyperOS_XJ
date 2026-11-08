#!/system/bin/sh
# engine/lib/thermal.sh — 温控执行层：档位落盘、旁路、原厂备份/还原、充电电流。
# 逻辑整理自 thermal_core.sh，全部改为函数，由 xjdaemon 单一循环调用。

MTK_MODE=0
[ -f "$CONFDIR/mtk_fix" ] && MTK_MODE=1

THERMAL_DAEMON=""
THERMAL_PATH=""
CHARGE_NODE=""
VOLTAGE_NODE=""
BUILD_VER=$(mod_version)
[ -n "$BUILD_VER" ] || BUILD_VER="engine"
LAST_RESTART=0

profile_get() {
  _p="$MODDIR/profiles/default.conf"
  _v=$(grep -aE "^[[:space:]]*$1[[:space:]]*=" "$_p" 2>/dev/null | tail -n1 | sed 's/^[^=]*=//; s/[[:space:]]*$//')
  [ -n "$_v" ] && printf '%s' "$_v" || printf '%s' "${2:-}"
}

ENABLE_GUARD=$(profile_get enable_guard 1)
TEMP_GUARD=$(profile_get temp_guard 480)
LOCK_CONF=$(profile_get lock_conf 1)
COOLDOWN=$(profile_get restart_cooldown 6)

mode_label() {
  case "$1" in
    default) echo "系统默认" ;; unlimited) echo "无限制" ;;
    level_1) echo "一档" ;; level_2) echo "二档" ;; level_3) echo "三档" ;;
    level_4) echo "四档" ;; level_5) echo "五档" ;; bypass) echo "旁路供电" ;;
    off) echo "已关闭" ;; disabled) echo "已禁用" ;;
    *) [ -n "$1" ] && echo "$1" || echo "系统默认" ;;
  esac
}

valid_mode() {
  case "$1" in
    default|unlimited|level_1|level_2|level_3|level_4|level_5|bypass|off|disabled) return 0 ;;
    *) return 1 ;;
  esac
}

src_for_mode() {
  case "$1" in
    unlimited) echo "$MODDIR/t_blank" ;;
    level_1|level_2|level_3|level_4|level_5) echo "$MODDIR/thermal/${1#level_}/thermal-scene.conf" ;;
    *) echo "" ;;
  esac
}

detect_daemon() {
  for _d in mi_thermald thermal-engine thermald thermal-manager; do
    if command -v "$_d" >/dev/null 2>&1 || [ -x "/system/bin/$_d" ] \
       || [ -x "/vendor/bin/$_d" ] || [ -x "/system/vendor/bin/$_d" ] || [ -x "/system_ext/bin/$_d" ]; then
      echo "$_d"; return 0
    fi
  done
  return 1
}

daemon_running() {
  [ -n "$THERMAL_DAEMON" ] && pgrep -f "$THERMAL_DAEMON" >/dev/null 2>&1
}

detect_thermal_path() {
  for _p in /data/vendor/thermal/config /data/thermal/config /data/vendor/thermal/thermal-config; do
    [ -d "$_p" ] && { echo "$_p"; return 0; }
  done
  if [ -d /data/vendor/thermal ] || [ ! -d /data/thermal ]; then
    echo "/data/vendor/thermal/config"
  else
    echo "/data/thermal/config"
  fi
}

detect_charge_node() {
  _ov=$(profile_get charge_node "")
  if [ -n "$_ov" ] && [ -f "$_ov" ] && [ -w "$_ov" ]; then echo "$_ov"; return 0; fi
  for _n in /sys/class/xm_power/charger/charger_thermal/wired_chg_curr \
            /sys/class/power_supply/battery/constant_charge_current_max \
            /sys/class/power_supply/battery/charge_control_limit \
            /sys/class/power_supply/battery/constant_charge_current \
            /sys/class/power_supply/battery/input_current_limit; do
    [ -f "$_n" ] && [ -w "$_n" ] && { echo "$_n"; return 0; }
  done
  return 1
}

detect_voltage_node() {
  for _n in /sys/class/power_supply/battery/voltage_now /sys/class/power_supply/battery/voltage_ocv; do
    [ -f "$_n" ] && { echo "$_n"; return 0; }
  done
  return 1
}

probe() {
  THERMAL_DAEMON=$(detect_daemon)
  THERMAL_PATH=$(detect_thermal_path)
  CHARGE_NODE=$(detect_charge_node)
  VOLTAGE_NODE=$(detect_voltage_node)
  _platform=$(getprop ro.board.platform 2>/dev/null)
  _model=$(getprop ro.product.model 2>/dev/null)
  _market=$(getprop ro.product.marketname 2>/dev/null)
  [ -z "$_market" ] && _market="$_model"
  _os=$(getprop ro.mi.os.version.incremental 2>/dev/null | sed 's/OS//' | cut -d'.' -f1)

  printf '%s' "$THERMAL_DAEMON" > "$CONFDIR/rt_thermal_daemon"
  printf '%s' "$THERMAL_PATH" > "$CONFDIR/rt_thermal_path"
  printf '%s' "$CHARGE_NODE" > "$CONFDIR/rt_charge_node"
  printf '%s' "$VOLTAGE_NODE" > "$CONFDIR/rt_voltage_node"
  printf '%s' "$_platform" > "$CONFDIR/rt_platform"
  printf '%s' "$_model" > "$CONFDIR/rt_model"
  printf '{"thermal_daemon":"%s","os_version":"%s","thermal_path":"%s","charge_node":"%s","voltage_node":"%s","platform":"%s","model":"%s","market_name":"%s","build":"%s"}\n' \
    "$THERMAL_DAEMON" "$_os" "$THERMAL_PATH" "$CHARGE_NODE" "$VOLTAGE_NODE" \
    "$_platform" "$_model" "$_market" "$BUILD_VER" > "$CONFDIR/runtime.json"
  log "探测: daemon=${THERMAL_DAEMON:-无} path=$THERMAL_PATH node=${CHARGE_NODE:-无}"
  [ -n "$THERMAL_DAEMON" ]
}

bootstrap() {
  THERMAL_DAEMON=$(cat "$CONFDIR/rt_thermal_daemon" 2>/dev/null)
  THERMAL_PATH=$(cat "$CONFDIR/rt_thermal_path" 2>/dev/null)
  CHARGE_NODE=$(cat "$CONFDIR/rt_charge_node" 2>/dev/null)
  VOLTAGE_NODE=$(cat "$CONFDIR/rt_voltage_node" 2>/dev/null)
  if [ -z "$THERMAL_DAEMON" ] || [ -z "$THERMAL_PATH" ] || [ ! -d "$THERMAL_PATH" ]; then
    probe || return 1
  fi
  [ -n "$CHARGE_NODE" ] || CHARGE_NODE=$(detect_charge_node)
  [ -n "$VOLTAGE_NODE" ] || VOLTAGE_NODE=$(detect_voltage_node)
  return 0
}

ensure_thermal_dir() {
  [ -d "$THERMAL_PATH" ] || THERMAL_PATH=$(detect_thermal_path)
  _parent=$(dirname "$THERMAL_PATH")
  chattr -R -i -a "$_parent" 2>/dev/null
  [ -d "$THERMAL_PATH" ] || mkdir -p "$THERMAL_PATH" 2>/dev/null
  chown -R root:system "$_parent" 2>/dev/null
  chcon -R u:object_r:thermal_data_file:s0 "$_parent" 2>/dev/null
  chmod -R 0771 "$_parent" 2>/dev/null
}

scan_thermal_dir() {
  for _d in /system/vendor/etc /vendor/etc /odm/etc /system_ext/etc /system/etc; do
    [ -d "$_d" ] && echo "$_d"
  done
}

refresh_thermal_list() {
  : > "$MODDIR/thermal_list" 2>/dev/null
  for _d in $(scan_thermal_dir); do
    for _f in "$_d"/thermal*.conf; do [ -f "$_f" ] && basename "$_f"; done
  done | sort -u > "$MODDIR/thermal_list" 2>/dev/null
  [ -s "$MODDIR/thermal_list" ]
}

write_map_files() {
  [ -s "$MODDIR/thermal_list" ] || refresh_thermal_list
  grep -i 'thermal-' "$MODDIR/thermal_list" 2>/dev/null | grep -i -- '-map' | grep -iv -- '-region-map' > "$CONFDIR/.maps" 2>/dev/null
  _n=0
  while IFS= read -r _i; do
    [ -n "$_i" ] || continue
    for _d in $(scan_thermal_dir); do
      if [ -f "$_d/$_i" ]; then cp -f "$MODDIR/t_map" "$THERMAL_PATH/$_i" 2>/dev/null && _n=$((_n + 1)); break; fi
    done
  done < "$CONFDIR/.maps"
  echo "$_n"
}

unlock_files() { chattr -R -i -a "$THERMAL_PATH" 2>/dev/null; }

lock_files() {
  [ "$LOCK_CONF" = "1" ] || return 0
  chattr +i "$THERMAL_PATH/thermal-normal.conf" 2>/dev/null
  while IFS= read -r _f; do
    [ -n "$_f" ] && [ -f "$THERMAL_PATH/$_f" ] && chattr +i "$THERMAL_PATH/$_f" 2>/dev/null
  done < "$CONFDIR/.maps"
}

restart_daemon() {
  _now=$(date +%s)
  [ "${LAST_RESTART:-0}" -gt 0 ] && [ $((_now - LAST_RESTART)) -lt "$COOLDOWN" ] && return 0
  LAST_RESTART=$_now
  unlock_files
  stop "$THERMAL_DAEMON" 2>/dev/null
  sleep 1
  start "$THERMAL_DAEMON" 2>/dev/null
  sleep 1
  daemon_running || { command -v restart >/dev/null 2>&1 && restart "$THERMAL_DAEMON" 2>/dev/null; }
}

backup_ready() { [ -f "$MODDIR/backup/thermal-config/.done" ] || [ -f "/data/adb/HyperOS_XJ_backup/thermal-config/.done" ]; }
backup_source() { [ -f "$MODDIR/backup/thermal-config/.done" ] && echo "$MODDIR/backup/thermal-config" || echo "/data/adb/HyperOS_XJ_backup/thermal-config"; }

factory_backup() {
  backup_ready && return 0
  [ -d "$THERMAL_PATH" ] || return 1
  _n=$(ls -A "$THERMAL_PATH" 2>/dev/null | wc -l)
  [ "${_n:-0}" -gt 0 ] || return 1
  _bk="$MODDIR/backup/thermal-config"
  mkdir -p "$_bk" 2>/dev/null
  cp -a "$THERMAL_PATH/." "$_bk/" 2>/dev/null
  echo "$(date '+%F %T') from=$THERMAL_PATH files=$_n" > "$_bk/.done"
  log "原厂备份完成: ${_n} 个文件"
}

restore_factory() {
  unlock_files
  chattr -R -i -a "$THERMAL_PATH" 2>/dev/null
  case "$THERMAL_PATH" in */config|*/thermal-config) ;; *) return 1 ;; esac
  rm -rf "$THERMAL_PATH"/* 2>/dev/null
  _bk=$(backup_source)
  [ -f "$_bk/.done" ] && cp -a "$_bk/." "$THERMAL_PATH/" 2>/dev/null && rm -f "$THERMAL_PATH/.done" 2>/dev/null
  chown -R root:system "$THERMAL_PATH" 2>/dev/null
  chcon -R u:object_r:thermal_data_file:s0 "$THERMAL_PATH" 2>/dev/null
  chmod -R 0771 "$THERMAL_PATH" 2>/dev/null
}

apply_conf_mode() {
  _mode="$1" _src="$2"
  ensure_thermal_dir
  _tmd5=$(md5sum "$_src" 2>/dev/null | cut -d' ' -f1)
  _curmd5=$(md5sum "$THERMAL_PATH/thermal-normal.conf" 2>/dev/null | cut -d' ' -f1)
  _curname=$(cat "$CONFDIR/current_mode" 2>/dev/null)
  [ -n "$_tmd5" ] || return 1
  [ "$_curmd5" = "$_tmd5" ] && [ "$_curname" = "$_mode" ] && return 0
  unlock_files
  ensure_thermal_dir
  cp -f "$_src" "$THERMAL_PATH/thermal-normal.conf" 2>/dev/null || return 1
  _n=$(write_map_files)
  restart_daemon
  lock_files
  printf '%s' "$_mode" > "$CONFDIR/current_mode"
  update_status "$_mode"
  log "切换 → $(mode_label "$_mode") ｜ map=$_n"
}

apply_default() {
  _cur=$(cat "$CONFDIR/current_mode" 2>/dev/null)
  [ "$_cur" = "default" ] && return 0
  ensure_thermal_dir
  restore_factory
  restart_daemon
  printf 'default' > "$CONFDIR/current_mode"
  update_status default
  log "切换 → 系统默认（已还原原厂配置）"
}

apply_bypass() {
  [ "$MTK_MODE" = "1" ] && return 0
  ensure_thermal_dir
  _src="$MODDIR/t_bypass_0"
  _tmd5=$(md5sum "$_src" 2>/dev/null | cut -d' ' -f1)
  _curmd5=$(md5sum "$THERMAL_PATH/thermal-normal.conf" 2>/dev/null | cut -d' ' -f1)
  if [ -n "$_tmd5" ] && [ "$_curmd5" != "$_tmd5" ]; then
    unlock_files
    cp -f "$_src" "$THERMAL_PATH/thermal-normal.conf" 2>/dev/null
    write_map_files >/dev/null
    restart_daemon
    lock_files
  fi
  set_charge_current "$(bypass_current)"
  [ "$(cat "$CONFDIR/current_mode" 2>/dev/null)" = "bypass" ] || {
    printf 'bypass' > "$CONFDIR/current_mode"; update_status bypass; log "切换 → 旁路供电"; }
}

save_charge_orig() {
  [ "$MTK_MODE" = "1" ] && return 0
  [ -s "$CONFDIR/rt_charge_orig" ] && return 0
  [ -n "$CHARGE_NODE" ] || CHARGE_NODE=$(detect_charge_node)
  _v=$(cat "$CHARGE_NODE" 2>/dev/null)
  case "$_v" in ''|*[!0-9]*|0) return 1 ;; esac
  printf '%s' "$_v" > "$CONFDIR/rt_charge_orig"
  log "记录充电电流原值 = $_v ($CHARGE_NODE)"
}

set_charge_current() {
  [ "$MTK_MODE" = "1" ] && return 0
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  [ -n "$CHARGE_NODE" ] || CHARGE_NODE=$(detect_charge_node)
  [ -f "$CHARGE_NODE" ] && [ -w "$CHARGE_NODE" ] || return 1
  [ "$(cat "$CHARGE_NODE" 2>/dev/null)" = "$1" ] && return 0
  chmod 0644 "$CHARGE_NODE" 2>/dev/null
  echo "$1" > "$CHARGE_NODE" 2>/dev/null
}

bypass_current() {
  _v=$(json_get bypass_current "")
  case "$_v" in ''|*[!0-9]*) _v=500000 ;; esac
  _orig=$(cat "$CONFDIR/rt_charge_orig" 2>/dev/null)
  case "$_orig" in ''|*[!0-9]*) ;; *) [ "$_v" -gt "$_orig" ] && _v="$_orig" ;; esac
  [ "$_v" -lt 500000 ] && _v=500000
  echo "$_v"
}

release_charge() {
  rm -f "$CONFDIR/bypass_active" 2>/dev/null
  power_restore
}

guard_apply() {
  _mode="$1"
  [ "$ENABLE_GUARD" = "1" ] || { echo "$_mode"; return 0; }
  if [ "${BAT_TEMP:-0}" -ge "$TEMP_GUARD" ]; then
    case "$_mode" in unlimited|level_1|level_2|default) printf '1' > "$CONFDIR/.guard"; echo "level_3"; return 0 ;; esac
  fi
  printf '0' > "$CONFDIR/.guard"
  echo "$_mode"
}

hitemp_guard() {
  _want=1
  if [ ! -f "$MODDIR/disable" ] && [ "$(json_get enabled true)" != "false" ] && [ "$(json_get no_hitemp_stop false)" = "true" ]; then
    _want=0
  fi
  _val=true; [ "$_want" = "0" ] && _val=false
  [ "$(getprop persist.vendor.high_temp_stop_charge 2>/dev/null)" = "$_val" ] && return 0
  _rp=""
  for _c in /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop /data/adb/ap/bin/resetprop; do
    [ -x "$_c" ] && { _rp="$_c"; break; }
  done
  [ -z "$_rp" ] && command -v resetprop >/dev/null 2>&1 && _rp="resetprop"
  [ -n "$_rp" ] && "$_rp" -n persist.vendor.high_temp_stop_charge "$_val" >/dev/null 2>&1
  setprop persist.vendor.high_temp_stop_charge "$_val" >/dev/null 2>&1
}

hyperos_keepalive() {
  [ "$(json_get hyperos4 false)" = "true" ] || return 0
  _now=$(date +%s); _last=$(cat "$CONFDIR/.hyp_ka" 2>/dev/null); [ -z "$_last" ] && _last=0
  [ $((_now - _last)) -lt 30 ] && return 0
  printf '%s' "$_now" > "$CONFDIR/.hyp_ka"
  _ok=1
  [ "$(getprop debug.game.video.support 2>/dev/null)" = "true" ] || _ok=0
  [ "$(getprop debug.game.video.speed 2>/dev/null)" = "true" ] || _ok=0
  [ "$_ok" = "1" ] && return 0
  _rp=""
  for _c in /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop /data/adb/ap/bin/resetprop; do
    [ -x "$_c" ] && { _rp="$_c"; break; }
  done
  [ -z "$_rp" ] && command -v resetprop >/dev/null 2>&1 && _rp="resetprop"
  for _k in debug.game.video.support debug.game.video.speed; do
    [ -n "$_rp" ] && "$_rp" -n "$_k" true >/dev/null 2>&1
    setprop "$_k" true >/dev/null 2>&1
  done
}

update_status() {
  _label=$(mode_label "$1")
  _t=$(cat /sys/class/power_supply/battery/temp 2>/dev/null)
  case "$_t" in ''|*[!0-9-]*) _t="" ;; esac
  if [ -n "$_t" ]; then
    [ "$_t" -lt 0 ] && _t=0
    _desc="运行中 ｜ ${_label} ｜ $((_t / 10)).$((_t % 10))℃"
  else
    _desc="运行中 ｜ ${_label} ｜ --.-℃"
  fi
  sed -i "s#^description=.*#description=$_desc#" "$MODDIR/module.prop" 2>/dev/null
}

write_status_json() {
  printf '{"temp":%s,"current":%s,"capacity":%s,"status":"%s","mode":"%s","voltage":%s,"bypass":"%s","build":"%s","ts":%s}\n' \
    "${BAT_TEMP:-0}" "${BAT_CUR:-0}" "${BAT_CAP:-0}" "${BAT_STATUS:-Unknown}" \
    "$(cat "$CONFDIR/current_mode" 2>/dev/null)" "${BAT_VOLT:-0}" \
    "$(cat "$CONFDIR/bypass_active" 2>/dev/null)" "$BUILD_VER" "$(date +%s)" > "$CONFDIR/status.json"
  write_app_status_json
}

# subscreencenter 在装与否变化极少，缓存 60 秒避免每拍 fork pm。
_subscreen_supported_cached() {
  _f="$STATE/subscreen_ok"; _ts="$STATE/subscreen_ok.ts"
  _now=$(date +%s); _last=$(cat "$_ts" 2>/dev/null)
  case "$_last" in ''|*[!0-9]*) _last=0 ;; esac
  if [ ! -s "$_f" ] || [ $((_now - _last)) -ge 60 ]; then
    pm path com.xiaomi.subscreencenter >/dev/null 2>&1 && echo 1 > "$_f" || echo 0 > "$_f"
    printf '%s' "$_now" > "$_ts"
  fi
  cat "$_f" 2>/dev/null
}

# App uid 仅重装才变，缓存 10 分钟避免每拍 fork dumpsys。
_app_uid_cached() {
  _f="$STATE/app_uid"; _ts="$STATE/app_uid.ts"
  _now=$(date +%s); _last=$(cat "$_ts" 2>/dev/null)
  case "$_last" in ''|*[!0-9]*) _last=0 ;; esac
  if [ ! -s "$_f" ] || [ $((_now - _last)) -ge 600 ]; then
    card_uid > "$_f" 2>/dev/null
    printf '%s' "$_now" > "$_ts"
  fi
  cat "$_f" 2>/dev/null
}

# App 免 root 直读的状态副本：键名必须与 xjctl status_json 一致（App 端解析不变）。
# 主路径与 rear.sh CARD_CONF_DIR 同款，目录不可用时退 /data/local/tmp/xj_status.json。
write_app_status_json() {
  _frag="$STATE/app_status.frag"
  : > "$_frag" 2>/dev/null || return 0
  data_read 2>/dev/null | while IFS= read -r _l; do
    _k=${_l%%=*}; _v=${_l#*=}
    js_line "$_k" "$_v" str >> "$_frag"
  done
  js_line thermal_mode "$(cat "$CONFDIR/current_mode" 2>/dev/null)" str >> "$_frag"
  js_line thermal_default "$(json_get default_mode default)" str >> "$_frag"
  js_line power_tier "$(power_tier)" str >> "$_frag"
  js_line power_applied "$(cat "$BT_STATE/power_applied" 2>/dev/null)" str >> "$_frag"
  js_line unlock "$(cfg_get unlock 1)" str >> "$_frag"
  js_line freepps "$(cfg_get freepps 0)" str >> "$_frag"
  js_line cloud_guard "$(cfg_get cloud_guard 1)" str >> "$_frag"
  js_line apl_enable "$(cfg_get apl_enable 0)" str >> "$_frag"
  js_line cycle_fake "$(cfg_get cycle_fake "")" str >> "$_frag"
  js_line fv_v "$(cfg_get fv_v "")" str >> "$_frag"
  js_line term_v "$(cfg_get term_v "")" str >> "$_frag"
  js_line fv_force "$(cfg_get fv_force 0)" str >> "$_frag"
  js_line bypass_manual "$([ -f "$CONFDIR/bypass_manual" ] && echo 1 || echo 0)" str >> "$_frag"
  js_line rearscreen "$(cfg_get rearscreen 0)" str >> "$_frag"
  js_line rearscreen_supported "$(_subscreen_supported_cached)" str >> "$_frag"
  js_line module_version "$(mod_version)" str >> "$_frag"
  js_line kernel "$(uname -r 2>/dev/null)" str >> "$_frag"
  js_line date "$(date +%F)" str >> "$_frag"
  _st="$STATE/sysinfo.txt"; _sts="$STATE/sysinfo.ts"
  _now=$(date +%s); _last=$(cat "$_sts" 2>/dev/null)
  case "$_last" in ''|*[!0-9]*) _last=0 ;; esac
  if [ ! -s "$_st" ] || [ $((_now - _last)) -ge 60 ]; then
    data_sysinfo > "$_st.tmp" 2>/dev/null && mv -f "$_st.tmp" "$_st"
    printf '%s' "$_now" > "$_sts"
  fi
  js_line device_name "$(sed -n 1p "$_st" 2>/dev/null)" str >> "$_frag"
  js_line device_code "$(sed -n 2p "$_st" 2>/dev/null)" str >> "$_frag"
  js_line os_version "$(sed -n 3p "$_st" 2>/dev/null)" str >> "$_frag"
  js_line base_firmware "$(sed -n 4p "$_st" 2>/dev/null)" str >> "$_frag"
  js_line ts "$_now" str >> "$_frag"

  _dst="/storage/emulated/0/Android/data/com.hyperos.xj/files/status.json"
  _dir=${_dst%/*}
  [ -d "$_dir" ] || mkdir -p "$_dir" 2>/dev/null
  _fb=0
  if [ ! -d "$_dir" ]; then
    _dst="/data/local/tmp/xj_status.json"
    _fb=1
  fi
  if js_emit "$_frag" > "$_dst.tmp" 2>/dev/null && mv -f "$_dst.tmp" "$_dst" 2>/dev/null; then
    if [ "$_fb" = "1" ]; then
      chmod 0644 "$_dst" 2>/dev/null
    else
      _uid=$(_app_uid_cached)
      [ -n "$_uid" ] && [ "$_uid" != "0" ] && chown "$_uid:$_uid" "$_dst" 2>/dev/null
      chmod 0660 "$_dst" 2>/dev/null
    fi
  fi
  _prev=$(cat "$STATE/app_status.dst" 2>/dev/null)
  [ "$_prev" != "$_dst" ] && log "App 状态副本目标：$_dst"
  printf '%s' "$_dst" > "$STATE/app_status.dst" 2>/dev/null
  rm -f "$_frag" "$_dst.tmp"
}
