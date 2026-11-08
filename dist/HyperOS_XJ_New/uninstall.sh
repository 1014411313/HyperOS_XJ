#!/system/bin/sh
MODDIR=${0%/*}

# 先关闭背屏并恢复旧版可能禁用的原生时钟
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" rear off >/dev/null 2>&1
# 卸载 BAA、恢复 PSS
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" battery unlock off >/dev/null 2>&1
# 交回充电节点
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" power restore >/dev/null 2>&1
# 恢复温控默认配置
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" thermal set default >/dev/null 2>&1
[ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" apply >/dev/null 2>&1

pkill -f 'HyperOS_XJ/engine/xjdaemon\.sh' 2>/dev/null
pkill -f 'HyperOS_XJ/engine/boot\.sh' 2>/dev/null
rm -f "$MODDIR/state/daemon.pid" 2>/dev/null

pm uninstall --user 0 com.hyperos.xj >/dev/null 2>&1

case "$MODDIR" in
    /data/adb/modules/*|/data/adb/modules_update/*)
        find "$MODDIR" -mindepth 1 -maxdepth 1 ! -name 'uninstall.sh' -exec rm -rf {} + 2>/dev/null
        ( sleep 2; rm -rf "$MODDIR" ) >/dev/null 2>&1 &
        ;;
esac
exit 0
