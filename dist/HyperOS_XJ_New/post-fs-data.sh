#!/system/bin/sh
MODDIR=${0%/*}
mkdir -p "$MODDIR/config" "$MODDIR/state" "$MODDIR/log" "$MODDIR/battery/state" 2>/dev/null
[ -s "$MODDIR/config/settings.json" ] || cp -f "$MODDIR/default_settings.json" "$MODDIR/config/settings.json" 2>/dev/null
exit 0
