#!/system/bin/sh
# engine/lib/diag_log.sh — 常驻诊断采样记录器（挂在 xjdaemon 主循环内，无独立进程）。
# 参照 v3.12.9 diag_log.sh 的采样思路，但改为守护内嵌：每拍累积一行轻量采样到内存
# 缓冲，满 DIAG_BUF_MAX 行或距上次落盘 ≥ DIAG_FLUSH_SEC 秒时整段落盘。
# 采样复用 sample() 的全局变量，按需读取充电节点。
# 超过 DIAG_MAX_BYTES 截尾保留末尾（tail -c），防止日志无限增长占满 DATA 分区；
# 按「只留最近 1 天」的裁剪由 lib/retention.sh 每天执行一次。

DIAG_LOG="$LOGDIR/diag_samples.log"
DIAG_NODE_WIRED_CURR=/sys/class/xm_power/charger/charger_thermal/wired_chg_curr
DIAG_USB_SUPPLY=/sys/class/power_supply/usb
DIAG_USB_IBUS=/sys/class/qcom-battery/ibus_total
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

DIAG_WIRELESS_SUPPLY=/sys/class/power_supply/wireless
DIAG_WIRELESS_LAST=""

diag_wireless_read() {
  _dw_value=-
  if [ -r "$1" ] && IFS= read -r _dw_raw < "$1"; then
    case "${_dw_raw#-}" in ''|*[!0-9]*) ;; *) _dw_value=$_dw_raw ;; esac
  fi
}

diag_usb_read_current() {
  DIAG_CI=$(cat "$DIAG_USB_SUPPLY/current_now" 2>/dev/null)
  case "$DIAG_CI" in ''|*[!0-9-]*) DIAG_CI=- ;; esac
  case "$DIAG_CI" in 0|-)
    _di=$(cat "$DIAG_USB_SUPPLY/input_current_now" 2>/dev/null)
    case "$_di" in ''|*[!0-9-]*|0) _di= ;; esac
    if [ -n "$_di" ]; then
      DIAG_CI=$_di
    elif [ -r "$DIAG_USB_IBUS" ]; then
      IFS= read -r _di < "$DIAG_USB_IBUS"
      case "$_di" in ''|*[!0-9]*) ;; *) [ "$_di" -gt 0 ] && DIAG_CI=$((_di * 1000)) ;; esac
    fi
    ;;
  esac
}

diag_wireless_tick() {
  diag_wireless_read "$DIAG_WIRELESS_SUPPLY/online"; _dw_online=$_dw_value
  diag_wireless_read "$DIAG_WIRELESS_SUPPLY/present"; _dw_present=$_dw_value
  case "$_dw_online" in 0|1) ;; *) _dw_online=- ;; esac
  case "$_dw_present" in 0|1) ;; *) _dw_present=- ;; esac
  if [ "$_dw_online:$_dw_present" = '-:-' ]; then
    [ -n "$DIAG_WIRELESS_LAST" ] || return 0
    _dw_key=""
  else
    _dw_key="$_dw_online:$_dw_present:$1:$IS_CHARGING"
  fi
  [ "$_dw_key" != "$DIAG_WIRELESS_LAST" ] || return 0
  diag_wireless_read "$DIAG_WIRELESS_SUPPLY/voltage_now"; _dw_voltage=$_dw_value
  diag_wireless_read "$DIAG_WIRELESS_SUPPLY/current_now"; _dw_current=$_dw_value
  diag_wireless_read "${WIRELESS_CURR:-}"; _dw_limit=$_dw_value
  diag_wireless_read "${WIRELESS_TH:-}"; _dw_thermal=$_dw_value
  diag_wireless_read "${WIRELESS_CTRL:-}"; _dw_control=$_dw_value
  _dw_log="$LOGDIR/diag_wireless.log"
  printf '%s online=%s present=%s charging=%s mode=%s voltage_now_raw=%s current_now_raw=%s wireless_chg_curr_raw=%s wireless_thermal_remove=%s wireless_ctrl_limit=%s\n' \
    "$(date '+%F %T')" "$_dw_online" "$_dw_present" "$IS_CHARGING" "$1" \
    "$_dw_voltage" "$_dw_current" "$_dw_limit" "$_dw_thermal" "$_dw_control" >> "$_dw_log" || return 0
  DIAG_WIRELESS_LAST=$_dw_key
  if [ "$(wc -c < "$_dw_log")" -gt 262144 ]; then
    tail -n 500 "$_dw_log" > "$_dw_log.cut.$$" && mv -f "$_dw_log.cut.$$" "$_dw_log" \
      || rm -f "$_dw_log.cut.$$"
  fi
  return 0
}

diag_log_tick() {
  # 功率 uW = mV × mA（uV/1000 × uA/1000），电量为负值（放电）时结果同为负。
  # 空值字段填 "-" 占位：空字符串会让空格分隔的列错位，离线分析没法对列。
  _uw=$(( (BAT_VOLT / 1000) * (BAT_CUR / 1000) ))
  _wc=$(cat "$DIAG_NODE_WIRED_CURR" 2>/dev/null); [ -n "$_wc" ] || _wc=-
  if [ $((DIAG_BUF_N % 10)) -eq 0 ]; then
    DIAG_CV=$(cat "$DIAG_USB_SUPPLY/voltage_now" 2>/dev/null); [ -n "$DIAG_CV" ] || DIAG_CV=-
    DIAG_CP=$(cat /sys/class/xm_power/charger/charger_common/power_max 2>/dev/null); [ -n "$DIAG_CP" ] || DIAG_CP=-
  fi
  diag_usb_read_current
  _m=$(cat "$CONFDIR/current_mode" 2>/dev/null); [ -n "$_m" ] || _m=-
  diag_wireless_tick "$_m"
  _pa=$(cat "$BT_STATE/power_applied" 2>/dev/null); [ -n "$_pa" ] || _pa=-
  _ps=$(cat "$BT_STATE/power_sig" 2>/dev/null); [ -n "$_ps" ] || _ps=-
  # 电池样本沿用原有列结构，段头记录落盘时间。
  DIAG_BUF="${DIAG_BUF}${BAT_CAP} ${BAT_CUR} ${BAT_VOLT} ${BAT_TEMP} ${_uw} ${IS_CHARGING} ${_wc} ${DIAG_CV} ${DIAG_CI} ${DIAG_CP} ${_m} ${_pa} ${_ps}
"
  DIAG_BUF_N=$((DIAG_BUF_N + 1))
  _due=0
  if [ "$DIAG_BUF_N" -ge "$DIAG_BUF_MAX" ]; then
    _due=1
  else
    _now=${XJ_NOW:-$(date +%s)}
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
  DIAG_LAST_FLUSH=${XJ_NOW:-$(date +%s)}
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
