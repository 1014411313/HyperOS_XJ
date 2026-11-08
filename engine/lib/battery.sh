#!/system/bin/sh
# engine/lib/battery.sh — 新模块的电池解容核心。
# 只读取 battery/baa 与 battery/config 的数据，不调用旧模块脚本。

BAT_DIR="$MODDIR/battery"
BAT_CONF="$BAT_DIR/config/settings.conf"
BAT_STATE="$BAT_DIR/state"
BAA_LABEL="u:object_r:batteryantiaging_cfg_file:s0"
PSS_COMPONENT=com.miui.securitycenter/com.miui.powercenter.provider.PowerSaveService

mkdir -p "$BAT_STATE" 2>/dev/null

blog() { echo "$(date '+%F %T') [battery] $*" >> "$LOGDIR/battery.log" 2>/dev/null; }

dev_code() {
  _d=$(cfg_get dev_code "")
  [ -n "$_d" ] || _d=$(getprop ro.product.vendor.device 2>/dev/null)
  [ -n "$_d" ] || _d=$(getprop ro.product.odm.device 2>/dev/null)
  [ -n "$_d" ] || _d=$(getprop ro.product.device 2>/dev/null)
  [ -n "$_d" ] || _d=unknown
  printf '%s' "$_d"
}

baa_src() {
  _d=$(dev_code)
  printf '%s' "$BAT_DIR/baa/payload_BAA_config_$_d.json"
}

baa_tgt() {
  _d=$(dev_code)
  _std="/odm/etc/charger/BAA_config_$_d.json"
  [ -f "$_std" ] && { printf '%s' "$_std"; return 0; }
  for _dir in /odm/etc/charger /vendor/etc/charger /my_product/etc/charger \
              /system/etc/charger /system_ext/etc/charger /product/etc/charger; do
    [ -d "$_dir" ] || continue
    [ -f "$_dir/BAA_config_$_d.json" ] && { printf '%s' "$_dir/BAA_config_$_d.json"; return 0; }
    _any=$(ls "$_dir"/BAA_config_*.json 2>/dev/null | head -n 1)
    [ -n "$_any" ] && { printf '%s' "$_any"; return 0; }
  done
  printf '%s' "$_std"
  return 1
}

baa_state() {
  _src=$(baa_src)
  _tgt=$(baa_tgt)
  _s=$(sha256sum "$_src" 2>/dev/null | awk '{print $1}')
  _t=$(sha256sum "$_tgt" 2>/dev/null | awk '{print $1}')
  _sl=$(stat -c '%C' "$_src" 2>/dev/null)
  _l=$(stat -c '%C' "$_tgt" 2>/dev/null)
  if [ -n "$_s" ] && [ "$_s" = "$_t" ] && [ -n "$_l" ] && \
     { [ "$_l" = "$_sl" ] || [ "$_l" = "$BAA_LABEL" ]; }; then
    echo active
  else
    echo inactive
  fi
}

# 必须在 PID1 mount namespace 执行；xjctl.sh 在 battery 命令入口会 re-exec 到 PID1。
baa_mount() {
  _src=$(baa_src)
  _tgt=$(baa_tgt)
  if [ ! -f "$_src" ]; then
    blog "BAA source missing: $_src"
    return 1
  fi
  if [ ! -f "$_tgt" ]; then
    blog "BAA target missing: $_tgt"
    return 2
  fi
  _ss=$(stat -c %s "$_src" 2>/dev/null)
  case "$_ss" in ''|*[!0-9]*) _ss=0 ;; esac
  if [ "$_ss" -le 8 ]; then
    blog "BAA source empty: $_src"
    return 1
  fi
  grep -qF " $_tgt " /proc/mounts 2>/dev/null && umount "$_tgt" 2>/dev/null
  _label=$(stat -c '%C' "$_tgt" 2>/dev/null)
  [ -n "$_label" ] || _label="$BAA_LABEL"
  chcon "$_label" "$_src" 2>/dev/null
  chmod 0644 "$_src" 2>/dev/null
  if ! mount -o bind "$_src" "$_tgt" 2>/dev/null; then
    blog "BAA mount failed: $_tgt"
    return 4
  fi
  _a=$(sha256sum "$_src" 2>/dev/null | awk '{print $1}')
  _b=$(sha256sum "$_tgt" 2>/dev/null | awk '{print $1}')
  if [ -z "$_a" ] || [ "$_a" != "$_b" ]; then
    umount "$_tgt" 2>/dev/null
    blog "BAA verify failed, rolled back"
    return 3
  fi
  blog "BAA mounted: $_tgt"
  return 0
}

baa_umount() {
  _tgt=$(baa_tgt)
  if grep -qF " $_tgt " /proc/mounts 2>/dev/null; then
    umount "$_tgt" 2>/dev/null
  fi
  setprop ctl.restart batteryantiaging 2>/dev/null
  blog "BAA umounted: $_tgt"
}

pss_state() {
  _d=$(dumpsys package com.miui.securitycenter 2>/dev/null)
  _seg=$(echo "$_d" | sed -n '/disabledComponents:/,/enabledComponents:/p')
  case "$_seg" in
    *PowerSaveService*) echo disabled ;;
    *) echo enabled ;;
  esac
}

pss_disable() {
  [ "$(pss_state)" = "disabled" ] && return 0
  pm disable "$PSS_COMPONENT" >/dev/null 2>&1
  printf '1' > "$BAT_STATE/pss_disabled_by_module" 2>/dev/null
  blog "PSS disabled"
}

pss_enable() {
  [ "$(pss_state)" = "enabled" ] && return 0
  pm enable "$PSS_COMPONENT" >/dev/null 2>&1
  rm -f "$BAT_STATE/pss_disabled_by_module" 2>/dev/null
  blog "PSS enabled"
}

battery_boot() {
  [ "$(cfg_get unlock 1)" = "1" ] && baa_mount >/dev/null 2>&1
  [ "$(cfg_get pss_disable 0)" = "1" ] && pss_disable
  return 0
}

battery_unlock_on() {
  cfg_set unlock 1
  baa_mount
  [ "$(cfg_get pss_disable 0)" = "1" ] && pss_disable
  setprop ctl.restart batteryantiaging 2>/dev/null
  blog "unlock ON"
}

battery_unlock_off() {
  cfg_set unlock 0
  baa_umount
  pss_enable
  setprop ctl.restart batteryantiaging 2>/dev/null
  blog "unlock OFF"
}
