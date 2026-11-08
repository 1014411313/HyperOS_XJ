#!/system/bin/sh
MODDIR=${0%/*}
DATA_DIR=/data/adb/HyperOS_XJ
mkdir -p "$DATA_DIR/config" "$DATA_DIR/cache" "$DATA_DIR/state" "$DATA_DIR/log" \
         "$DATA_DIR/battery/state" "$DATA_DIR/backup" 2>/dev/null
[ -s "$DATA_DIR/config/settings.json" ] || cp -f "$MODDIR/default_settings.json" "$DATA_DIR/config/settings.json" 2>/dev/null
[ -s "$DATA_DIR/battery/settings.conf" ] || cp -f "$MODDIR/battery/config/settings.conf" "$DATA_DIR/battery/settings.conf" 2>/dev/null
exit 0
