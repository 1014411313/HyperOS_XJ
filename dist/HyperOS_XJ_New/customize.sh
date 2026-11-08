#!/system/bin/sh
SKIPUNZIP=0
MODPATH=${MODPATH:-/data/adb/modules_update/HyperOS_XJ}
MODDIR="$MODPATH"
print() { ui_print "$@"; }

print " - HyperOS - 动态温控·解容（新架构）"
mkdir -p "$MODPATH/config" "$MODPATH/state" "$MODPATH/log" \
         "$MODPATH/battery/state" "$MODPATH/backup" 2>/dev/null

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

_plat=$(getprop ro.board.platform 2>/dev/null)
[ -n "$_plat" ] || _plat=$(getprop ro.hardware 2>/dev/null)
case "$_plat" in
    mt*|MT*)
        if [ -f "$MODPATH/default_settings.mtk.json" ]; then
            cp -f "$MODPATH/default_settings.json" "$MODPATH/default_settings.full.json" 2>/dev/null
            cp -f "$MODPATH/default_settings.mtk.json" "$MODPATH/default_settings.json" 2>/dev/null
        fi
        printf '1' > "$MODPATH/config/mtk_fix" ;;
    *)
        rm -f "$MODPATH/config/mtk_fix" 2>/dev/null ;;
esac

[ -s "$MODPATH/config/settings.json" ] || \
    cp -f "$MODPATH/default_settings.json" "$MODPATH/config/settings.json" 2>/dev/null

_apk="$MODPATH/app/HyperOS_XJ.apk"
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
    print " [!] App 安装失败，可手动安装 $MODPATH/app/HyperOS_XJ.apk"
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
set_perm "$MODPATH/app/HyperOS_XJ.apk" 0 0 0644 2>/dev/null

print " - 完成。首次开机后引擎会自动接管温控/功率/背屏。"
