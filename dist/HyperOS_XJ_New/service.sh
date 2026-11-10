#!/system/bin/sh
# 新模块启动入口：只拉起自研引擎，不再启动旧 thermal_core / battery service。
MODDIR=${0%/*}
DATA_DIR=/data/adb/rezhale_toolbox
LOG="$DATA_DIR/log/service.log"
mkdir -p "$DATA_DIR/log" "$DATA_DIR/config" "$DATA_DIR/state" "$DATA_DIR/battery/state" 2>/dev/null

i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    i=$((i + 1))
    [ "$i" -ge 180 ] && break
    sleep 1
done
echo "$(date '+%F %T') [SVC] boot_completed waited=${i}s" >> "$LOG"

# 日志轮转（参照 ref_battery/service.sh）：单文件 >2MiB 截尾保留末尾 10000 行。
# tail 写临时文件再 mv 原子替换，避免轮转途中读取方（诊断包/用户）读到半截日志；
# 无系统 tail 时回退 Magisk/KernelSU 自带 busybox 的 tail，再没有就记一行跳过。
svc_log_rotate() {
    _tailbin=""
    if command -v tail >/dev/null 2>&1; then
        _tailbin="tail"
    elif [ -x /data/adb/ksu/bin/busybox ]; then
        _tailbin="/data/adb/ksu/bin/busybox tail"
    elif [ -x /data/adb/magisk/busybox ]; then
        _tailbin="/data/adb/magisk/busybox tail"
    elif command -v busybox >/dev/null 2>&1; then
        _tailbin="busybox tail"
    fi
    if [ -z "$_tailbin" ]; then
        echo "$(date '+%F %T') [SVC] log rotate skip: no tail/busybox available" >> "$LOG"
        return 0
    fi
    for _lf in "$DATA_DIR"/log/*.log; do
        [ -f "$_lf" ] || continue
        _sz=$(wc -c < "$_lf" 2>/dev/null)
        case "$_sz" in ''|*[!0-9]*) continue ;; esac
        [ "$_sz" -gt 2097152 ] || continue
        if "$_tailbin" -n 10000 "$_lf" > "$_lf.rot" 2>/dev/null && mv -f "$_lf.rot" "$_lf" 2>/dev/null; then
            echo "$(date '+%F %T') [SVC] log rotated: $_lf (was $_sz bytes, keep last 10000 lines)" >> "$LOG"
        else
            rm -f "$_lf.rot" 2>/dev/null
            echo "$(date '+%F %T') [SVC] log rotate failed: $_lf" >> "$LOG"
        fi
    done
    return 0
}
svc_log_rotate

[ -s "$DATA_DIR/config/settings.json" ] || \
    cp -f "$MODDIR/default_settings.json" "$DATA_DIR/config/settings.json" 2>/dev/null

# 背屏默认背景（bug-070/074）：默认图与用户自选图分文件。
# 默认图按模块 versionCode 覆盖更新；用户自选（xj_rear_bg.gif/png）永不被触碰，
# 这样「恢复默认背景」才能真的回到内置动图，而不是回到被用户图覆盖的同名文件。
BG_SRC="$MODDIR/backdrop/default_bg.gif"
BG_ENGINE=/data/local/tmp/xj_rear_bg_default.gif
BG_APP_DIR=/storage/emulated/0/Android/data/com.hyperos.xj/files
BG_APP="$BG_APP_DIR/xj_rear_bg_default.gif"
BG_VER_FILE="$DATA_DIR/state/bg_default_ver"
_bg_ver=$(grep -a '^versionCode=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2)
if [ -f "$BG_SRC" ] && [ "$(cat "$BG_VER_FILE" 2>/dev/null)" != "$_bg_ver" ]; then
    if cp -f "$BG_SRC" "$BG_ENGINE" 2>/dev/null; then
        chmod 0644 "$BG_ENGINE" 2>/dev/null
        mkdir -p "$DATA_DIR/state" 2>/dev/null
        printf '%s\n' "$_bg_ver" > "$BG_VER_FILE" 2>/dev/null
    fi
    # 旧版把默认图放在 xj_rear_bg.gif、与用户自选图同名互盖：未自选背景时清掉残留。
    _bg_sel=$(grep -a '^rear_bg=' "$DATA_DIR/battery/settings.conf" 2>/dev/null | tail -n1 | cut -d= -f2-)
    if [ -z "$_bg_sel" ]; then
        rm -f /data/local/tmp/xj_rear_bg.gif "$BG_APP_DIR/xj_rear_bg.gif" 2>/dev/null
    fi
    # App 预览副本（App uid 读不了 /data/local/tmp）。
    # 开机早期 FUSE 尚未就绪、目录不可写会让 cp 静默失败：先每秒试写探针，最多等 60s，超时写日志跳过（bug-059）。
    if [ -f "$BG_ENGINE" ]; then
        _bg_wait=0
        while [ "$_bg_wait" -lt 60 ]; do
            if [ -d "$BG_APP_DIR" ] && touch "$BG_APP_DIR/.bg_probe" 2>/dev/null; then
                rm -f "$BG_APP_DIR/.bg_probe" 2>/dev/null
                break
            fi
            _bg_wait=$((_bg_wait + 1))
            sleep 1
        done
        if [ "$_bg_wait" -ge 60 ]; then
            echo "$(date '+%F %T') [SVC] bg copy skip: $BG_APP_DIR not writable after 60s" >> "$LOG"
        elif cp -f "$BG_ENGINE" "$BG_APP" 2>/dev/null; then
            chmod 0644 "$BG_APP" 2>/dev/null
            _bg_uid=$(stat -c %u "$BG_APP_DIR" 2>/dev/null)
            case "$_bg_uid" in ''|*[!0-9]*|0) ;; *) chown "$_bg_uid:$_bg_uid" "$BG_APP" 2>/dev/null ;; esac
        fi
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

# DTBO 开机自动备份：后台执行，同内容未备份过才备份一份；失败不影响开机。
if [ -f "$MODDIR/engine/lib/dtbo.sh" ]; then
    (
        . "$MODDIR/engine/lib/env.sh" 2>/dev/null
        . "$MODDIR/engine/lib/dtbo.sh" 2>/dev/null
        dtbo_autobackup
    ) >/dev/null 2>&1 &
fi

exit 0
