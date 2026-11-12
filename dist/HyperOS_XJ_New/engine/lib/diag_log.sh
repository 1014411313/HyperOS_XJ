#!/system/bin/sh
# engine/lib/diag_log.sh — 常驻诊断采样记录器（挂在 xjdaemon 主循环内，无独立进程）。
# 参照 v3.12.9 diag_log.sh 的采样思路，但改为守护内嵌：每拍累积一行轻量采样到内存
# 缓冲，满 DIAG_BUF_MAX 行或距上次落盘 ≥ DIAG_FLUSH_SEC 秒时整段落盘。
# 采样只读 sample() 已有的全局变量 + 一个功率节点，不 fork dumpsys。
# 超过 DIAG_MAX_BYTES 截尾保留末尾（tail -c），防止日志无限增长占满 DATA 分区；
# 按「只留最近 1 天」的裁剪由 lib/retention.sh 每天执行一次。

DIAG_LOG="$LOGDIR/diag_samples.log"
DIAG_NODE_WIRED_CURR=/sys/class/xm_power/charger/charger_thermal/wired_chg_curr
DIAG_BUF_MAX=60          # 缓冲行数上限（3s/拍约 3 分钟、充电 1s/拍约 1 分钟必落一次）
DIAG_FLUSH_SEC=30        # 两次落盘的最小间隔（秒）
DIAG_MAX_BYTES=$((4 * 1024 * 1024))   # 体积上限（约容纳 1 天采样；按天裁剪由 retention.sh 负责）
DIAG_TAIL_BYTES=$((2 * 1024 * 1024))  # 截尾后保留的末尾字节数

DIAG_BUF=""
DIAG_BUF_N=0
DIAG_LAST_FLUSH=0
DIAG_HEADER_DONE=0
# 充电器侧缓列（电压/电流/标称功率）：每 10 拍刷一次，避免每拍 3 次额外 fork
DIAG_CV="-"; DIAG_CI="-"; DIAG_CP="-"

diag_log_tick() {
  # 功率 uW = mV × mA（uV/1000 × uA/1000），电量为负值（放电）时结果同为负。
  # 空值字段填 "-" 占位：空字符串会让空格分隔的列错位，离线分析没法对列。
  _uw=$(( (BAT_VOLT / 1000) * (BAT_CUR / 1000) ))
  _wc=$(cat "$DIAG_NODE_WIRED_CURR" 2>/dev/null); [ -n "$_wc" ] || _wc=-
  if [ $((DIAG_BUF_N % 10)) -eq 0 ]; then
    DIAG_CV=$(cat /sys/class/power_supply/usb/voltage_now 2>/dev/null); [ -n "$DIAG_CV" ] || DIAG_CV=-
    DIAG_CI=$(cat /sys/class/power_supply/usb/current_now 2>/dev/null); [ -n "$DIAG_CI" ] || DIAG_CI=-
    DIAG_CP=$(cat /sys/class/xm_power/charger/charger_common/power_max 2>/dev/null); [ -n "$DIAG_CP" ] || DIAG_CP=-
  fi
  _m=$(cat "$CONFDIR/current_mode" 2>/dev/null); [ -n "$_m" ] || _m=-
  _pa=$(cat "$BT_STATE/power_applied" 2>/dev/null); [ -n "$_pa" ] || _pa=-
  _ps=$(cat "$BT_STATE/power_sig" 2>/dev/null); [ -n "$_ps" ] || _ps=-
  # 每拍只做变量拼接与一次节点 cat；date 仅在缓冲检查点调用（约每 10 拍一次）
  DIAG_BUF="${DIAG_BUF}${BAT_CAP} ${BAT_CUR} ${BAT_VOLT} ${BAT_TEMP} ${_uw} ${IS_CHARGING} ${_wc} ${DIAG_CV} ${DIAG_CI} ${DIAG_CP} ${_m} ${_pa} ${_ps}
"
  DIAG_BUF_N=$((DIAG_BUF_N + 1))
  _due=0
  if [ "$DIAG_BUF_N" -ge "$DIAG_BUF_MAX" ]; then
    _due=1
  elif [ $((DIAG_BUF_N % 10)) -eq 0 ]; then
    _now=$(date +%s)
    case "$DIAG_LAST_FLUSH" in ''|*[!0-9]*) DIAG_LAST_FLUSH=0 ;; esac
    [ $((_now - DIAG_LAST_FLUSH)) -ge "$DIAG_FLUSH_SEC" ] && _due=1
  fi
  [ "$_due" = "1" ] || return 0
  {
    # 段头带落盘时间；首次落盘补一行表头，方便离线看列含义
    [ "$DIAG_HEADER_DONE" = "1" ] || {
      printf '# soc cur_uA volt_uV temp_0.1C power_uW charging wired_chg_curr charger_v_uV usb_cur_uA charger_pmax mode applied power_sig\n'
      DIAG_HEADER_DONE=1
    }
    printf '# %s\n' "$(date '+%F %T')"
    printf '%s' "$DIAG_BUF"
  } >> "$DIAG_LOG" 2>/dev/null
  DIAG_BUF=""
  DIAG_BUF_N=0
  DIAG_LAST_FLUSH=$(date +%s)
  # 体积控制：超限截尾保留末尾（wc/tail 各一次 fork，仅落盘时发生）
  _sz=$(wc -c < "$DIAG_LOG" 2>/dev/null)
  case "$_sz" in ''|*[!0-9]*) return 0 ;; esac
  if [ "$_sz" -gt "$DIAG_MAX_BYTES" ]; then
    _tmp="$DIAG_LOG.cut.$$"
    tail -c "$DIAG_TAIL_BYTES" "$DIAG_LOG" > "$_tmp" 2>/dev/null \
      && mv -f "$_tmp" "$DIAG_LOG" \
      || rm -f "$_tmp" 2>/dev/null
  fi
  return 0
}
