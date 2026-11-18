#!/system/bin/sh
SKIPUNZIP=0
MODPATH=${MODPATH:-/data/adb/modules_update/rezhale_toolbox}
MODDIR="$MODPATH"
DATA_DIR=/data/adb/rezhale_toolbox
OLD_DIR=/data/adb/modules/rezhale_toolbox
print() { ui_print "$@"; }

print " - 热炸了工具箱（新架构）"

# 旧模块 id（HyperOS_XJ）迁移：数据目录搬过来并清掉旧模块目录，避免两个模块并存
# 旧模块 id（HyperOS_XJ）迁移：先杀掉旧进程再搬数据/删目录——
# 旧 daemon 读不到新路径的配置、还会和新 daemon 抢充电节点（反复断充）。
# 读 cmdline 要访问目标进程内存，目标正在退出时可能阻塞，整体套 timeout（bug-121）
timeout 8 sh -c 'for p in /proc/[0-9]*/cmdline; do
    [ -r "$p" ] || continue
    case "$(tr "\0" " " < "$p" 2>/dev/null)" in
        *HyperOS_XJ/engine/*) kill "$(echo "$p" | cut -d/ -f3)" 2>/dev/null ;;
    esac
done' 2>/dev/null
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
[ -s "$DATA_DIR/battery/settings.conf" ] || \
    cp -f "$MODPATH/battery/config/settings.conf" "$DATA_DIR/battery/settings.conf" 2>/dev/null
if [ -n "$_dev" ] && [ -f "$DATA_DIR/battery/settings.conf" ] && ! grep -q '^dev_code=' "$DATA_DIR/battery/settings.conf" 2>/dev/null; then
    echo "dev_code=$_dev" >> "$DATA_DIR/battery/settings.conf" 2>/dev/null
fi

# 「修改满电电压」功能已移除：清掉旧配置键与运行时残留，避免遗留值继续误导（也避免超上限值）。
if [ -f "$DATA_DIR/battery/settings.conf" ] && grep -qE '^(fv_v|fv_force)=' "$DATA_DIR/battery/settings.conf" 2>/dev/null; then
    sed -i '/^fv_v=/d; /^fv_force=/d' "$DATA_DIR/battery/settings.conf" 2>/dev/null
    print " - 已清理旧配置键 fv_v / fv_force"
fi
rm -rf "$DATA_DIR/battery/state/fv_force.applied" "$DATA_DIR/battery/state/fv_force.tmp" \
       "$DATA_DIR/battery/state/fv_force_backup" 2>/dev/null

# 旧数据根 /data/adb/HyperOS_XJ 与旧版引擎路径混在一起（旧 daemon 会持续报路径不存在）；
# 数据已在 rezhale_toolbox 下，这里清掉旧运行目录（_backup/_data 历史备份保留）。
# 新数据目录已存在时，旧数据目录里可能有没迁移过去的用户数据：只提示不删，
# 不自动删除/覆盖旧目录（迁移只在全新安装时做）。
if [ -d "$DATA_DIR" ] && [ -d /data/adb/HyperOS_XJ ]; then
    print " [!] 检测到旧数据目录 /data/adb/HyperOS_XJ（新目录已存在）：已保留，未自动删除"
    print "     确认不需要时可手动删；需要迁移时先备份再手动处理"
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
[ -s "$DATA_DIR/config/settings.json" ] || \
    cp -f "$MODPATH/default_settings.json" "$DATA_DIR/config/settings.json" 2>/dev/null

_apk="$MODPATH/app/rezhale_toolbox.apk"
_apk_ok=0
if [ -f "$_apk" ]; then
    # -r 覆盖安装保留旧数据；失败不先卸载绕签名（那会丢数据，也可能被当成绕过签名限制）。
    _out=$(pm install -r "$_apk" 2>&1)
    _rc=$?
    if [ "$_rc" -eq 0 ]; then
        _apk_ok=1
    else
        print " [!] App 安装失败（pm install 退出码 $_rc）：$(printf '%s\n' "$_out" | sed -n '1,2p' | tr '\n' ' ')"
        print " [!] 常见原因：新旧包签名不一致、系统拒绝降级、空间不足"
        print "     手动处理（模块数据保留）：备份后卸载 com.hyperos.xj，再安装 $_apk"
    fi
else
    print " [!] 模块内缺少 APK：$_apk（包不完整，App 不会装上）"
fi

# 安装结果以「已装包 vs 模块 module.prop」核对；设备上不假设有 aapt，只用 dumpsys。
# 只有版本对得上才报成功，安装失败或包没被替换时给可执行信息。
_mod_vc=$(sed -n 's/^versionCode=//p' "$MODPATH/module.prop" 2>/dev/null | head -n 1)
_mod_vn=$(sed -n 's/^version=//p' "$MODPATH/module.prop" 2>/dev/null | head -n 1)
_inst_vc=$(dumpsys package com.hyperos.xj 2>/dev/null | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)
_inst_vn=$(dumpsys package com.hyperos.xj 2>/dev/null | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -n 1)
if [ -f "$_apk" ]; then
    if [ "$_apk_ok" != "1" ]; then
        print " [!] 本次没能装上 App（原因见上），模块其余功能照常。"
    elif ! pm path com.hyperos.xj >/dev/null 2>&1; then
        print " [!] pm 报告安装成功但查不到 com.hyperos.xj，请手动安装 $_apk"
    elif [ -z "$_inst_vc" ] || [ -z "$_inst_vn" ]; then
        print " [!] App 已安装，但读不到已装版本（dumpsys 不可用）：请到系统设置确认是 $_mod_vn ($_mod_vc)，否则手动安装 $_apk"
    elif [ "$_inst_vc" = "$_mod_vc" ] && [ "$_inst_vn" = "$_mod_vn" ]; then
        print " - App 已安装：$_inst_vn ($_inst_vc)，与模块版本一致（UI + 背屏 LSP 接口一体）"
        print " - 请在 LSPosed 中启用 com.hyperos.xj，scope 勾选 com.xiaomi.subscreencenter"
    else
        print " [!] App 版本与模块不一致：已装 $_inst_vn ($_inst_vc)，模块 $_mod_vn ($_mod_vc)"
        print "     多为系统没替换包（签名/降级限制）：手动安装 $_apk；仍失败则备份数据后卸载 com.hyperos.xj 再装"
    fi
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
