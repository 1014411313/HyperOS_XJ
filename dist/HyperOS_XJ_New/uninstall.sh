MODDIR=${0%/*}
DATA_DIR=/data/adb/rezhale_toolbox
_ts=$(date +%Y%m%d_%H%M%S)

# ---- 卸载备份包：state/配置/日志打包到 /sdcard/Download（清理前的最后快照机会）----
# zip 可用优先 zip，否则用 tar（toybox/busybox 都自带）；备份失败继续还原并保留用户数据。
_backup_msg=""
_backup_ok=""
uninstall_pack_backup() {
  _backup_msg="未能备份（无 zip/tar 可用或打包失败）"
  [ -d "$DATA_DIR" ] || return 0
  [ -d /sdcard/Download ] || mkdir -p /sdcard/Download 2>/dev/null
  [ -d /sdcard/Download ] || { _backup_msg="未能备份（/sdcard/Download 不可用）"; return 0; }
  _out="/sdcard/Download/rezhale_toolbox_uninstall_${_ts}"
  # 成员路径都是本脚本内的固定常量（不含空格），zip/tar 两种工具用同一组
  _items=""
  for _d in config battery log cache state backup; do
    [ -e "$DATA_DIR/$_d" ] && _items="$_items $_d"
  done
  if [ -n "$_items" ]; then
    if command -v zip >/dev/null 2>&1; then
      if (cd "$DATA_DIR" && zip -qr "${_out}.zip" $_items 2>/dev/null) &&
          [ -f "${_out}.zip" ] && [ -s "${_out}.zip" ]; then
        _backup_ok=1
        _backup_msg="已备份: ${_out}.zip"; return 0
      fi
    fi
    if command -v tar >/dev/null 2>&1; then
      if tar -czf "${_out}.tar.gz" -C "$DATA_DIR" $_items 2>/dev/null &&
          [ -f "${_out}.tar.gz" ] && [ -s "${_out}.tar.gz" ]; then
        _backup_ok=1
        _backup_msg="已备份: ${_out}.tar.gz"; return 0
      fi
    fi
  fi
  return 0
}
uninstall_pack_backup
echo "$_backup_msg"

_restore_ok=""
_restore_failed=""
_recovery_dir=""
uninstall_keep_recovery() {
  local _stage _item
  [ ! -L "$DATA_DIR/recovery" ] || return 1
  (umask 077; mkdir -p "$DATA_DIR/recovery") || return 1
  chmod 700 "$DATA_DIR/recovery" || return 1
  [ "$(stat -c '%u:%a' "$DATA_DIR/recovery" 2>/dev/null)" = 0:700 ] || return 1
  _stage="$DATA_DIR/recovery/${_ts}_$$"
  (umask 077; mkdir "$_stage") || return 1
  [ -f "$MODDIR/engine/xjctl.sh" ] && [ -f "$MODDIR/engine/xjdaemon.sh" ] || return 1
  for _item in engine battery profiles thermal backup bin default_settings.json module.prop t_blank t_map t_bypass_0 t_bypass_1; do
    [ -e "$MODDIR/$_item" ] || continue
    cp -R "$MODDIR/$_item" "$_stage/$_item" || return 1
  done
  cat > "$_stage/restore.sh" <<'RECOVERY'
#!/system/bin/sh
DIR=$(cd "$(dirname "$0")" && pwd) || exit 1
rc=0
for step in 'rear off' 'battery unlock off' 'battery restore' 'power restore' 'battery pps restore' 'setting set enabled false' 'apply'; do
  sh "$DIR/engine/xjctl.sh" $step || rc=1
done
exit "$rc"
RECOVERY
  [ "$?" = 0 ] && chmod 700 "$_stage/restore.sh" || return 1
  _recovery_dir="$_stage"
}
if ! uninstall_keep_recovery; then
  _restore_failed="保存独立恢复工具"
fi
uninstall_restore() {
  _step="$1"
  shift
  if [ -f "$MODDIR/engine/xjctl.sh" ] && sh "$MODDIR/engine/xjctl.sh" "$@" >/dev/null 2>&1; then
    _restore_ok="${_restore_ok}${_restore_ok:+、}$_step"
  else
    _restore_failed="${_restore_failed}${_restore_failed:+、}$_step"
  fi
}
uninstall_restore "背屏叠加/时钟" rear off
uninstall_restore "BAA 挂载与 PSS" battery unlock off
uninstall_restore "smart_fv 与循环计数" battery restore
uninstall_restore "充电节点" power restore
# 禁用后再应用，避免充电/游戏场景覆盖默认温控并重新接管已还原的节点。
uninstall_restore "禁用引擎" setting set enabled false
uninstall_restore "温控默认" apply

# 停看护与守护：不使用 pkill/pgrep -f 按脚本名匹配（有误匹配风险且设备上行为不稳），
# 改为 /proc 扫描精确匹配本模块绝对路径后 kill。必须先杀看护 boot.sh 再杀 xjdaemon.sh，
# 否则看护会在 60s 内把 daemon 重新拉起。cmdline 读取套 timeout 防退出瞬间卡死（同 boot.sh 做法）。
_scan_kill='
for d in /proc/[0-9]*; do
  [ -r "$d/cmdline" ] || continue
  cl=$(tr "\0" " " < "$d/cmdline" 2>/dev/null) || continue
  case "$cl" in
    *rezhale_toolbox/engine/"$1".sh*) kill -9 "${d#/proc/}" 2>/dev/null ;;
  esac
done'
if command -v timeout >/dev/null 2>&1; then
  timeout 8 sh -c "$_scan_kill" sh boot 2>/dev/null
  timeout 8 sh -c "$_scan_kill" sh xjdaemon 2>/dev/null
else
  sh -c "$_scan_kill" sh boot 2>/dev/null
  sh -c "$_scan_kill" sh xjdaemon 2>/dev/null
fi
# 仍存活的守护负责还原，避免卸载进程与其同时写验证节点。
uninstall_restore "公版 PPS 验证节点" battery pps restore

# ---- 还原说明 + 残留报告：stdout 输出，并尽力留档 /sdcard/Download ----
# 必须在还原与清理动作完成后输出，才能如实列出「还原后还存在的」节点与文件。
_rep="/sdcard/Download/rezhale_toolbox_uninstall_${_ts}_report.txt"
command -v tee >/dev/null 2>&1 || _rep=""
_body=$( {
  echo "===== 还原说明 ====="
  [ -n "$_restore_ok" ] && echo "还原命令完成（节点值见下方）: $_restore_ok"
  [ -n "$_restore_failed" ] && echo "还原失败: $_restore_failed；已保留 $DATA_DIR 中的配置与备份"
  [ "$_backup_ok" != 1 ] && [ -d "$DATA_DIR" ] && echo "备份失败: $_backup_msg；已保留 $DATA_DIR 中的用户数据"
  echo "不自动回滚: DTBO 分区。如曾刷入模块镜像，请用备份刷回后重启:"
  echo "  备份目录: /sdcard/Download/dtbo_backup/（主）；$DATA_DIR/battery/state/dtbo_backup/（回退）"
  echo "  私有登记: $DATA_DIR/battery/state/dtbo_registry/；仅接受本机成功备份时建立的登记"
  echo "  manifest.txt 仅供查看，不能作为恢复信任依据"
  [ -n "$_recovery_dir" ] && echo "独立恢复工具: sh $_recovery_dir/restore.sh"
  echo "App 与其私有数据已保留"
  echo "本次备份包: ${_backup_msg:-未执行}"
  echo "建议: 重启一次，让所有运行时节点彻底回到系统默认"
  echo ""
  echo "===== 卸载残留报告 ====="
  echo "[节点]（还原后仍存在的值，供人工核对）"
  for _n in /sys/class/qcom-battery/pd_verifed \
            /sys/class/Charging_Adapter/pd_adapter/usbpd_verifed \
            /sys/class/xm_power/charger/smart_charge/smart_fv \
            /sys/class/xm_power/fg_master/cyclecount \
            /sys/class/power_supply/battery/cycle_count; do
    [ -e "$_n" ] && echo "  $_n = $(cat "$_n" 2>/dev/null)"
  done
  echo "[文件]"
  if [ -d "$DATA_DIR" ]; then echo "  $DATA_DIR 已保留（配置、备份、DTBO 私有登记与恢复材料）"; fi
  echo "  模块目录由模块管理器清理"
} 2>/dev/null )
printf '%s\n' "$_body"
[ -n "$_rep" ] && printf '%s\n' "$_body" > "$_rep" 2>/dev/null

[ -z "$_restore_failed" ]
