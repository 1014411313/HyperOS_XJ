MODDIR=${0%/*}
DATA_DIR=/data/adb/rezhale_toolbox
_ts=$(date +%Y%m%d_%H%M%S)

# ---- 卸载备份包：state/配置/日志打包到 /sdcard/Download（清理前的最后快照机会）----
# zip 可用优先 zip，否则用 tar（toybox/busybox 都自带）；两者都不可用则打印未能备份并继续。
# 备份失败绝不中断卸载流程。
_backup_msg=""
uninstall_pack_backup() {
  _backup_msg="未能备份（无 zip/tar 可用或打包失败）"
  [ -d "$DATA_DIR" ] || return 0
  [ -d /sdcard/Download ] || mkdir -p /sdcard/Download 2>/dev/null
  [ -d /sdcard/Download ] || { _backup_msg="未能备份（/sdcard/Download 不可用）"; return 0; }
  _out="/sdcard/Download/rezhale_toolbox_uninstall_${_ts}"
  # 成员路径都是本脚本内的固定常量（不含空格），zip/tar 两种工具用同一组
  _items=""
  for _d in config battery log cache state; do
    [ -e "$DATA_DIR/$_d" ] && _items="$_items $_d"
  done
  if [ -n "$_items" ]; then
    if command -v zip >/dev/null 2>&1; then
      if (cd "$DATA_DIR" && zip -qr "${_out}.zip" $_items 2>/dev/null); then
        _backup_msg="已备份: ${_out}.zip"; return 0
      fi
    fi
    if command -v tar >/dev/null 2>&1; then
      if tar -czf "${_out}.tar.gz" -C "$DATA_DIR" $_items 2>/dev/null; then
        _backup_msg="已备份: ${_out}.tar.gz"; return 0
      fi
    fi
  fi
  return 0
}
uninstall_pack_backup
echo "$_backup_msg"

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

# 停看护与守护：不使用 pkill/pgrep -f 按脚本名匹配（有误匹配风险且设备上行为不稳），
# 改为 /proc 扫描精确匹配本模块绝对路径后 kill。必须先杀看护 boot.sh 再杀 xjdaemon.sh，
# 否则看护会在 60s 内把 daemon 重新拉起。cmdline 读取套 timeout 防退出瞬间卡死（同 boot.sh 做法）。
_dp=$(cat "$DATA_DIR/state/daemon.pid" 2>/dev/null)
case "$_dp" in ''|*[!0-9]*) _dp=$(cat "$DATA_DIR/state/daemon.lock.d/pid" 2>/dev/null) ;; esac
case "$_dp" in ''|*[!0-9]*) _dp="" ;; esac
[ -n "$_dp" ] && kill -9 "$_dp" 2>/dev/null
_scan_kill='
for d in /proc/[0-9]*; do
  [ -r "$d/cmdline" ] || continue
  cl=$(tr "\0" " " < "$d/cmdline" 2>/dev/null) || continue
  case "$cl" in
    *rezhale_toolbox/engine/boot.sh*|*rezhale_toolbox/engine/xjdaemon.sh*)
      kill -9 "${d#/proc/}" 2>/dev/null ;;
  esac
done'
if command -v timeout >/dev/null 2>&1; then
  timeout 8 sh -c "$_scan_kill" 2>/dev/null
else
  sh -c "$_scan_kill" 2>/dev/null
fi
rm -f "$DATA_DIR/state/daemon.pid" "$DATA_DIR/state/daemon.lock.d/pid" 2>/dev/null

pm uninstall --user 0 com.hyperos.xj >/dev/null 2>&1

# 卸载时清掉模块目录外的用户数据（精确路径）
rm -rf "$DATA_DIR" 2>/dev/null

case "$MODDIR" in
    /data/adb/modules/*|/data/adb/modules_update/*)
        find "$MODDIR" -mindepth 1 -maxdepth 1 ! -name 'uninstall.sh' -exec rm -rf {} + 2>/dev/null
        ( sleep 2; rm -rf "$MODDIR" ) >/dev/null 2>&1 &
        ;;
esac

# ---- 还原说明 + 残留报告：stdout 输出，并尽力留档 /sdcard/Download ----
# 必须在还原与清理动作完成后输出，才能如实列出「还原后还存在的」节点与文件。
_rep="/sdcard/Download/rezhale_toolbox_uninstall_${_ts}_report.txt"
command -v tee >/dev/null 2>&1 || _rep=""
_body=$( {
  echo "===== 还原说明 ====="
  echo "已还原: 背屏叠加/时钟、BAA 挂载与 PSS、smart_fv 与循环计数、公版 PPS 验证节点、充电节点、温控默认"
  echo "不自动回滚: DTBO 分区。如曾刷入模块镜像，请用备份刷回后重启:"
  echo "  备份目录: /sdcard/Download/dtbo_backup/（主；不可写时自动落在 $DATA_DIR/battery/state/dtbo_backup/，已随本次备份包带走）"
  echo "  其中 manifest.txt 记录每份备份的 sha256 与槽位，before_flash/auto_backup 行即刷入前的分区内容"
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
  if [ -d "$DATA_DIR" ]; then echo "  $DATA_DIR 仍存在（本次清理未删除成功）"; else echo "  $DATA_DIR 已清除"; fi
  if [ -e "$MODDIR/module.prop" ]; then echo "  $MODDIR 仍存在（由后台延迟删除）"; else echo "  $MODDIR 已清除"; fi
} 2>/dev/null )
printf '%s\n' "$_body"
[ -n "$_rep" ] && printf '%s\n' "$_body" > "$_rep" 2>/dev/null

exit 0
