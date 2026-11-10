#!/system/bin/sh
MODDIR=${0%/*}
DATA_DIR=/data/adb/rezhale_toolbox

# 先关闭背屏并恢复旧版可能禁用的原生时钟
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" rear off >/dev/null 2>&1
# 卸载 BAA、恢复 PSS
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" battery unlock off >/dev/null 2>&1
# 还原 smart_fv 与循环计数（写回模块接管前的值）
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" battery restore >/dev/null 2>&1
# 归还公版 PPS 验证节点（否则会停在 1）
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" battery pps off >/dev/null 2>&1
# 交回充电节点
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" power restore >/dev/null 2>&1
# 恢复温控默认配置
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" thermal set default >/dev/null 2>&1
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" apply >/dev/null 2>&1

pkill -f 'rezhale_toolbox/engine/xjdaemon\.sh' 2>/dev/null
pkill -f 'rezhale_toolbox/engine/boot\.sh' 2>/dev/null
rm -f "$DATA_DIR/state/daemon.pid" 2>/dev/null

pm uninstall --user 0 com.hyperos.xj >/dev/null 2>&1

# 卸载时清掉模块目录外的用户数据（精确路径）
rm -rf "$DATA_DIR" 2>/dev/null

case "$MODDIR" in
    /data/adb/modules/*|/data/adb/modules_update/*)
        find "$MODDIR" -mindepth 1 -maxdepth 1 ! -name 'uninstall.sh' -exec rm -rf {} + 2>/dev/null
        ( sleep 2; rm -rf "$MODDIR" ) >/dev/null 2>&1 &
        ;;
esac
exit 0
