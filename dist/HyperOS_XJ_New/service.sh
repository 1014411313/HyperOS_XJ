#!/system/bin/sh
# 新模块启动入口：只拉起自研引擎，不再启动旧 thermal_core / battery service。
MODDIR=${0%/*}
DATA_DIR=/data/adb/HyperOS_XJ
LOG="$DATA_DIR/log/service.log"
mkdir -p "$DATA_DIR/log" "$DATA_DIR/config" "$DATA_DIR/state" "$DATA_DIR/battery/state" 2>/dev/null

i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    i=$((i + 1))
    [ "$i" -ge 180 ] && break
    sleep 1
done
echo "$(date '+%F %T') [SVC] boot_completed waited=${i}s" >> "$LOG"

[ -s "$DATA_DIR/config/settings.json" ] || \
    cp -f "$MODDIR/default_settings.json" "$DATA_DIR/config/settings.json" 2>/dev/null

# 背屏默认背景（bug-055）：只补缺不覆盖，用户自选背景写的是同名文件。
BG_SRC="$MODDIR/backdrop/default_bg.gif"
BG_ENGINE=/data/local/tmp/xj_rear_bg.gif
BG_APP_DIR=/storage/emulated/0/Android/data/com.hyperos.xj/files
BG_APP="$BG_APP_DIR/xj_rear_bg.gif"
if [ -f "$BG_SRC" ] && [ ! -f "$BG_ENGINE" ]; then
    cp -f "$BG_SRC" "$BG_ENGINE" 2>/dev/null
    chmod 0644 "$BG_ENGINE" 2>/dev/null
fi
# App 预览副本缺失时从引擎侧镜像（App uid 读不了 /data/local/tmp）；目录未建或取不到 uid 就跳过。
if [ -d "$BG_APP_DIR" ] && [ ! -f "$BG_APP" ] && [ -f "$BG_ENGINE" ]; then
    if cp -f "$BG_ENGINE" "$BG_APP" 2>/dev/null; then
        chmod 0644 "$BG_APP" 2>/dev/null
        _bg_uid=$(stat -c %u "$BG_APP_DIR" 2>/dev/null)
        case "$_bg_uid" in ''|*[!0-9]*|0) ;; *) chown "$_bg_uid:$_bg_uid" "$BG_APP" 2>/dev/null ;; esac
    fi
fi

# BAA 挂载 / PSS 恢复（仅当配置开启时）
if [ -f "$MODDIR/engine/xjctl.sh" ]; then
    sh "$MODDIR/engine/xjctl.sh" battery boot >> "$LOG" 2>&1
fi

# 自研引擎常驻进程（温控 + 功率 + 背屏 + 数据）
if [ -f "$MODDIR/engine/boot.sh" ]; then
    ( setsid sh "$MODDIR/engine/boot.sh" >> "$LOG" 2>&1 < /dev/null & )
fi

exit 0
