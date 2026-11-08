#!/system/bin/sh
# 新模块启动入口：只拉起自研引擎，不再启动旧 thermal_core / battery service。
MODDIR=${0%/*}
LOG="$MODDIR/log/service.log"
mkdir -p "$MODDIR/log" "$MODDIR/config" "$MODDIR/state" "$MODDIR/battery/state" 2>/dev/null

i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    i=$((i + 1))
    [ "$i" -ge 180 ] && break
    sleep 1
done
echo "$(date '+%F %T') [SVC] boot_completed waited=${i}s" >> "$LOG"

[ -s "$MODDIR/config/settings.json" ] || \
    cp -f "$MODDIR/default_settings.json" "$MODDIR/config/settings.json" 2>/dev/null

# BAA 挂载 / PSS 恢复（仅当配置开启时）
if [ -f "$MODDIR/engine/xjctl.sh" ]; then
    sh "$MODDIR/engine/xjctl.sh" battery boot >> "$LOG" 2>&1
fi

# 自研引擎常驻进程（温控 + 功率 + 背屏 + 数据）
if [ -f "$MODDIR/engine/boot.sh" ]; then
    ( setsid sh "$MODDIR/engine/boot.sh" >> "$LOG" 2>&1 < /dev/null & )
fi

exit 0
