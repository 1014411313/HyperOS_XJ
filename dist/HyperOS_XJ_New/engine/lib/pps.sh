#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# FreePPS v1.7.0 — Copyright (c) 2026 Seyud, https://github.com/Seyud/FreePPS
# 2026-11-19：HyperOS XJ 的 shell 移植，按 USB 拔线状态准备 PPS，充电时交给内核协商。

PPS_NODES="/sys/class/qcom-battery/pd_verifed /sys/class/Charging_Adapter/pd_adapter/usbpd_verifed"
PPS_USB=/sys/class/power_supply/usb
PPS_BAT_STATUS=/sys/class/power_supply/battery/status
PPS_BOOT_ID=/proc/sys/kernel/random/boot_id
PPS_EXTERNAL=/data/adb/modules/FreePPS
PPS_STATE="$BT_STATE/pps_original"

pps_target() {
  local node target
  if { [ -d "$PPS_EXTERNAL" ] && [ ! -e "$PPS_EXTERNAL/disable" ]; } ||
      { [ -f "$MODDIR/battery/scripts/fv_guard.sh" ] && [ -f "$MODDIR/battery/service.sh" ]; }; then
    echo '公版 PPS：检测到独立充电守护进程，请停用后重启' >&2
    return 1
  fi
  target=
  for node in $PPS_NODES; do
    [ -e "$node" ] || continue
    if [ -n "$target" ]; then
      echo '公版 PPS：检测到多个验证节点' >&2
      return 1
    fi
    target=$node
  done
  if [ -z "$target" ] || [ ! -r "$target" ] || [ ! -w "$target" ]; then
    echo '公版 PPS：未找到可读写的验证节点' >&2
    return 1
  fi
  printf '%s\n' "$target"
}

pps_unplugged() {
  [ "$(cat "$PPS_BAT_STATUS" 2>/dev/null)" = Discharging ] || return 1
  if [ -e "$PPS_USB/online" ]; then
    [ "$(cat "$PPS_USB/online" 2>/dev/null)" = 0 ] || return 1
  fi
  # online 在重新协商时也可能为 0，必须确认物理拔线。
  [ "$(cat "$PPS_USB/present" 2>/dev/null)" = 0 ]
}

pps_apply_idle() {
  local want="$1" boot saved_boot saved_node saved_value node current output snapshot_new=0
  [ "$XJ_READONLY" != 1 ] || return 1
  [ "$want" = 1 ] || [ -f "$PPS_STATE" ] || return 0
  boot=$(cat "$PPS_BOOT_ID" 2>/dev/null) && [ -n "$boot" ] || return 1
  if [ -f "$PPS_STATE" ]; then
    read -r saved_boot saved_node saved_value < "$PPS_STATE" || return 1
    if [ "$saved_boot" != "$boot" ]; then
      rm -f "$PPS_STATE" || return 1
    fi
  fi
  [ "$want" = 1 ] || [ -f "$PPS_STATE" ] || return 0

  node=$(pps_target) || return 1
  current=$(cat "$node" 2>/dev/null) || return 1
  case "$current" in 0|1) ;; *) echo '公版 PPS：验证节点值异常' >&2; return 1 ;; esac
  output=1
  if [ -f "$PPS_STATE" ]; then
    [ "$saved_node" = "$node" ] && [ "$saved_value" = 0 ] || return 1
    [ "$want" = 1 ] || output=$saved_value
  fi
  if [ "$current" != "$output" ]; then
    pps_unplugged || return 1
    if [ ! -f "$PPS_STATE" ]; then
      printf '%s %s %s\n' "$boot" "$node" "$current" > "$PPS_STATE.tmp" &&
        mv -f "$PPS_STATE.tmp" "$PPS_STATE" || return 1
      snapshot_new=1
    fi
    if ! pps_unplugged || [ "$(cat "$node" 2>/dev/null)" != "$current" ]; then
      [ "$snapshot_new" != 1 ] || rm -f "$PPS_STATE"
      return 1
    fi
    if ! { printf '%s\n' "$output" > "$node"; } 2>/dev/null ||
        [ "$(cat "$node" 2>/dev/null)" != "$output" ]; then
      echo '公版 PPS：写入验证节点失败或回读不符' >&2
      return 1
    fi
    blog "公版 PPS: $node=$output（USB 已拔出）"
  fi
  [ "$want" = 1 ] || rm -f "$PPS_STATE"
}

pps_tick() {
  local want="$1" error
  : "${PPS_IDLE_SEEN:=0}" "${PPS_ATTEMPT:=}"
  if [ "$want" != 1 ] && [ ! -f "$PPS_STATE" ]; then
    PPS_IDLE_SEEN= PPS_ATTEMPT=
    return 0
  fi
  if ! pps_unplugged; then
    PPS_IDLE_SEEN=
    [ "$(cat "$PPS_USB/present" 2>/dev/null)" != 1 ] || PPS_ATTEMPT=
    return 0
  fi
  if [ "$PPS_IDLE_SEEN" != 1 ]; then
    PPS_IDLE_SEEN=1
    return 0
  fi
  [ "$PPS_ATTEMPT" != "$want" ] || return 0
  PPS_ATTEMPT=$want
  error=$(pps_apply_idle "$want" 2>&1) || blog "${error:-公版 PPS：节点状态变化或无法读写，已跳过}"
}

pps_set() {
  local want="$1"
  if [ "$want" = 1 ]; then
    pps_target >/dev/null || return 1
    if [ ! -r "$PPS_USB/present" ] || [ ! -r "$PPS_BAT_STATUS" ]; then
      echo '公版 PPS：无法可靠检测 USB 拔线状态' >&2
      return 1
    fi
  fi
  cfg_set freepps "$want" && [ "$(cfg_get freepps '')" = "$want" ]
}

pps_restore() {
  local pid
  pps_set 0 || return 1
  [ -f "$PPS_STATE" ] || return 0
  pid=$(cat "$STATE/daemon.lock.d/pid" 2>/dev/null) || pid=
  case "$pid" in
    ''|0|*[!0-9]*) ;;
    *)
      if kill -0 "$pid" 2>/dev/null; then
        echo '公版 PPS：守护仍在运行，等待其在拔线后恢复' >&2
        return 1
      fi ;;
  esac
  pps_unplugged || { echo '公版 PPS：尚未确认拔线，重启后恢复系统默认' >&2; return 1; }
  pps_apply_idle 0
}

pps_status() {
  local node
  echo "freepps=$(cfg_get freepps 0)"
  for node in $PPS_NODES; do
    [ -e "$node" ] || continue
    echo "$(basename "$(dirname "$node")")_$(basename "$node")=$(cat "$node" 2>/dev/null)"
  done
  if ! pps_target >/dev/null; then
    echo 'pps_available=0'
  else
    echo 'pps_available=1'
  fi
}
