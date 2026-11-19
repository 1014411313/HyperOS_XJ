#!/usr/bin/env sh
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"

STATE=$TMP/state
BT_STATE=$STATE
MODDIR=$TMP/module
mkdir -p "$STATE" "$MODDIR" "$TMP/usb"
XJ_READONLY=0
freepps=0
cfg_get() { printf '%s' "$freepps"; }
cfg_set() { freepps=$2; }
blog() { printf '%s\n' "$*" >> "$TMP/log"; }
. "$ROOT/engine/lib/pps.sh"
PPS_NODES="$TMP/qcom $TMP/mtk"
PPS_USB=$TMP/usb
PPS_BAT_STATUS=$TMP/battery
PPS_BOOT_ID=$TMP/boot
PPS_EXTERNAL=$TMP/FreePPS
PPS_IDLE_SEEN= PPS_ATTEMPT=

value() { test "$(cat "$1")" = "$2"; }
put() { printf '%s\n' "$2" > "$1"; }
plug() { put usb/present 1; put usb/online 0; put battery Discharging; }
unplug() { put usb/present 0; put usb/online 0; put battery Discharging; }
ticks() { pps_tick "$freepps"; pps_tick "$freepps"; }

put boot boot-a
put qcom 1
unplug
ticks
value qcom 1
test ! -e "$PPS_STATE"

put qcom 0
plug
pps_set 1
ticks
value qcom 0
unplug
pps_tick 1
value qcom 0
pps_tick 1
value qcom 1
value "$PPS_STATE" "boot-a $TMP/qcom 0"

# Unplug preparation is a single attempt per session, even if the driver changes the value.
put qcom 0
ticks
value qcom 0
put usb/present unknown
ticks
unplug
ticks
value qcom 0
plug
ticks
value qcom 0
unplug
ticks
value qcom 1

# Turning PPS off with the cable still present leaves the driver value alone.
plug
pps_set 0
ticks
value qcom 1
unplug
ticks
value qcom 0
test ! -e "$PPS_STATE"

# Unknown unplug data, ambiguous nodes and an enabled upstream module never get writes.
pps_set 1
put usb/present unknown
ticks
value qcom 0
unplug
put mtk 0
if pps_set 1 2>/dev/null; then exit 1; fi
ticks
value qcom 0
value mtk 0
rm mtk
plug
ticks
mkdir "$PPS_EXTERNAL"
if pps_set 1 2>/dev/null; then exit 1; fi
unplug
ticks
value qcom 0
rmdir "$PPS_EXTERNAL"

# Invalid node data fails closed, and reboot invalidates the old ownership snapshot.
plug
ticks
put qcom bad
unplug
ticks
value qcom bad
test ! -e "$PPS_STATE"
put qcom 1
put "$PPS_STATE" "boot-a $TMP/qcom 0"
put boot boot-b
pps_set 0
ticks
value qcom 1
test ! -e "$PPS_STATE"

# Standalone restore cannot write before unplug or while the daemon still owns the node.
put "$PPS_STATE" "boot-b $TMP/qcom 0"
plug
if pps_restore 2>/dev/null; then exit 1; fi
value qcom 1
unplug
mkdir "$STATE/daemon.lock.d"
put "$STATE/daemon.lock.d/pid" "$$"
if pps_restore 2>/dev/null; then exit 1; fi
value qcom 1
rm "$STATE/daemon.lock.d/pid"
pps_restore
value qcom 0
test ! -e "$PPS_STATE"

# The alternate upstream node follows the same unplug rule when it is the only candidate.
rm qcom
put mtk 0
pps_set 1
ticks
value mtk 1

printf '%s\n' 'PPS 隔离回归通过'
