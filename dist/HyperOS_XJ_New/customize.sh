#!/system/bin/sh
SKIPUNZIP=0
MODPATH=${MODPATH:-/data/adb/modules_update/rezhale_toolbox}
MODDIR="$MODPATH"
DATA_DIR=/data/adb/rezhale_toolbox
OLD_DIR=/data/adb/modules/rezhale_toolbox
print() { ui_print "$@"; }

print " - 热炸了工具箱（新架构）"

# 旧模块 id（HyperOS_XJ）迁移：数据目录搬过来并清掉旧模块目录，避免两个模块并存
if [ -d /data/adb/HyperOS_XJ ] && [ ! -d "$DATA_DIR" ]; then
    mv /data/adb/HyperOS_XJ "$DATA_DIR" 2>/dev/null && print " - 已迁移旧数据目录（HyperOS_XJ → rezhale_toolbox）"
fi
if [ -d /data/adb/modules/HyperOS_XJ ]; then
    rm -rf /data/adb/modules/HyperOS_XJ 2>/dev/null && print " - 已移除旧模块目录（HyperOS_XJ）"
fi

_sup="annibale bixi byron dada haotian miro myron nezha onyx pandora popsicle pudding songyuan warsaw xuanyuan"
_dev=""
for _p in ro.product.device ro.product.vendor.device ro.product.odm.device ro.product.name; do
    _v=$(getprop $_p 2>/dev/null)
    case " $_sup " in *" $_v "*) _dev=$_v; break ;; esac
done
_conf="$MODPATH/battery/config/settings.conf"
if [ -n "$_dev" ] && [ -f "$_conf" ]; then
    if grep -q '^dev_code=' "$_conf"; then
        sed -i "s/^dev_code=.*/dev_code=$_dev/" "$_conf"
    else
        echo "dev_code=$_dev" >> "$_conf"
    fi
    print " - 机型代号：$_dev"
else
    print " - [!] 未识别到受支持机型，解容/DTBO 将跳过（dev_code 留空）"
fi

# ---- 一次性迁移（bug-045）：用户可变数据搬到 /data/adb/rezhale_toolbox ----
# 此刻旧模块目录仍在（Magisk 到下次重启合并时才删除），把旧设置迁出；全新安装只 seed。
mkdir -p "$DATA_DIR/config" "$DATA_DIR/cache" "$DATA_DIR/state" "$DATA_DIR/log" \
         "$DATA_DIR/battery/state" "$DATA_DIR/backup" 2>/dev/null
if [ ! -s "$DATA_DIR/config/settings.json" ]; then
    [ -d "$OLD_DIR/config" ] && cp -af "$OLD_DIR/config/." "$DATA_DIR/config/" 2>/dev/null
    [ -d "$OLD_DIR/state" ] && cp -af "$OLD_DIR/state/." "$DATA_DIR/state/" 2>/dev/null
    [ -d "$OLD_DIR/battery/state" ] && cp -af "$OLD_DIR/battery/state/." "$DATA_DIR/battery/state/" 2>/dev/null
    [ -f "$OLD_DIR/battery/config/settings.conf" ] && [ ! -s "$DATA_DIR/battery/settings.conf" ] && \
        cp -af "$OLD_DIR/battery/config/settings.conf" "$DATA_DIR/battery/settings.conf" 2>/dev/null
    [ -d "$OLD_DIR/backup/thermal-config" ] && [ ! -e "$DATA_DIR/backup/thermal-config" ] && \
        cp -af "$OLD_DIR/backup/thermal-config" "$DATA_DIR/backup/thermal-config" 2>/dev/null
fi
# 迁移来的 state/ 可能带旧 daemon.pid（bug-056）：安装后必重启、旧 pid 已无意义，
# 清掉避免人工排查时误信；存活判定已改为按真实进程（boot.sh daemon_alive）。
rm -f "$DATA_DIR/state"/*.pid 2>/dev/null
[ -s "$DATA_DIR/config/settings.json" ] || \
    cp -f "$MODPATH/default_settings.json" "$DATA_DIR/config/settings.json" 2>/dev/null
[ -s "$DATA_DIR/battery/settings.conf" ] || \
    cp -f "$MODPATH/battery/config/settings.conf" "$DATA_DIR/battery/settings.conf" 2>/dev/null
if [ -n "$_dev" ] && [ -f "$DATA_DIR/battery/settings.conf" ] && ! grep -q '^dev_code=' "$DATA_DIR/battery/settings.conf" 2>/dev/null; then
    echo "dev_code=$_dev" >> "$DATA_DIR/battery/settings.conf" 2>/dev/null
fi

_plat=$(getprop ro.board.platform 2>/dev/null)
[ -n "$_plat" ] || _plat=$(getprop ro.hardware 2>/dev/null)
case "$_plat" in
    mt*|MT*)
        if [ -f "$MODPATH/default_settings.mtk.json" ]; then
            cp -f "$MODPATH/default_settings.json" "$MODPATH/default_settings.full.json" 2>/dev/null
            cp -f "$MODPATH/default_settings.mtk.json" "$MODPATH/default_settings.json" 2>/dev/null
        fi
        printf '1' > "$DATA_DIR/config/mtk_fix" ;;
    *)
        rm -f "$DATA_DIR/config/mtk_fix" 2>/dev/null ;;
esac

_apk="$MODPATH/app/rezhale_toolbox.apk"
if [ -f "$_apk" ]; then
    if ! pm install -r "$_apk" >/dev/null 2>&1; then
        pm uninstall --user 0 com.hyperos.xj >/dev/null 2>&1
        pm install "$_apk" >/dev/null 2>&1
    fi
fi
if pm path com.hyperos.xj >/dev/null 2>&1; then
    print " - App 已安装（UI + 背屏 LSP 接口一体）"
    print " - 请在 LSPosed 中启用 com.hyperos.xj，scope 勾选 com.xiaomi.subscreencenter"
else
    print " [!] App 安装失败，可手动安装 $MODPATH/app/rezhale_toolbox.apk"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644 2>/dev/null
for _s in "$MODPATH/service.sh" "$MODPATH/post-fs-data.sh" "$MODPATH/customize.sh" \
          "$MODPATH/uninstall.sh" "$MODPATH/action.sh" "$MODPATH"/engine/*.sh; do
    [ -f "$_s" ] && set_perm "$_s" 0 0 0755 2>/dev/null
done
for _s in "$MODPATH"/engine/lib/*.sh; do
    [ -f "$_s" ] && set_perm "$_s" 0 0 0644 2>/dev/null
done
set_perm "$MODPATH/module.prop" 0 0 0644 2>/dev/null
set_perm "$MODPATH/app/rezhale_toolbox.apk" 0 0 0644 2>/dev/null

print " - 完成。首次开机后引擎会自动接管温控/功率/背屏。"
