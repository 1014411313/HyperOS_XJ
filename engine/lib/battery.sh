#!/system/bin/sh
# engine/lib/battery.sh — 新模块的电池解容核心。
# 只读取 battery/baa 与 battery/config 的数据，不调用旧模块脚本。

# BAT_DIR 只用于定位模块内只读资源（baa/dtbo/配置模板）；用户状态在 BT_STATE（DATA，模块目录外）。
BAT_DIR="$MODDIR/battery"
BAT_STATE="$BT_STATE"
BAA_LABEL="u:object_r:batteryantiaging_cfg_file:s0"
PSS_COMPONENT=com.miui.securitycenter/com.miui.powercenter.provider.PowerSaveService

[ "$XJ_READONLY" = "1" ] || mkdir -p "$BAT_STATE" 2>/dev/null

blog() { echo "$(date '+%F %T') [battery] $*" >> "$LOGDIR/battery.log" 2>/dev/null; }

# 支持的机型代号白名单；用白名单而非「取第一个非空属性」，
# 避免把 mivendor 这类非机型名当成 dev_code（本机 ro.product.vendor.device=mivendor）。
SUPPORTED_DEVS="annibale bixi byron dada haotian miro myron nezha onyx pandora popsicle pudding songyuan warsaw xuanyuan"

dev_code() {
  _d=$(cfg_get dev_code "")
  case " $SUPPORTED_DEVS " in
    *" $_d "*) printf '%s' "$_d"; return 0 ;;
  esac
  for _p in ro.product.device ro.product.vendor.device ro.product.odm.device ro.product.name; do
    _v=$(getprop "$_p" 2>/dev/null)
    case " $SUPPORTED_DEVS " in
      *" $_v "*) printf '%s' "$_v"; return 0 ;;
    esac
  done
  printf '%s' unknown
}

baa_src() {
  _d=$(dev_code)
  printf '%s' "$BAT_DIR/baa/payload_BAA_config_$_d.json"
}

# 云端下发（cloud push）会在运行期改写下发 BAA 策略，覆盖模块的解容设置。
# cloud_guard=1（默认）→ 用禁云端下发的载荷 normal_nocloud；=0 → 用 normal。
baa_cloud_variant() {
  [ "$(cfg_get cloud_guard 1)" = "1" ] && printf '%s' normal_nocloud || printf '%s' normal
}

# 原厂 BAA 分加密（BAA_ENC:）与明文两种，挂载格式必须与原厂一致，
# 否则 batteryantiaging 解析不到策略（dada 原厂就是明文 JSON）。
baa_fmt_use() {
  _fmt=$(cfg_get baa_fmt auto)
  case "$_fmt" in
    enc|plain) printf '%s' "$_fmt"; return 0 ;;
  esac
  _t=$(baa_tgt 2>/dev/null)
  [ -f "$_t" ] || { printf 'enc'; return 0; }
  case "$(head -c 8 "$_t" 2>/dev/null)" in
    BAA_ENC:) printf 'enc'; return 0 ;;
  esac
  if head -c 64 "$_t" 2>/dev/null | grep -q '{'; then printf 'plain'; else printf 'enc'; fi
}

# 选定载荷文件：优先机型专用的 <dev>.<变体>.json，其次通用 <变体>.json。
# 机型未识别（unknown）时不回退通用文件，避免把别的机型载荷挂到未知机型上。
# 明文变体缺失时回退加密载荷，不能因为少一个明文文件就整机不挂载。
baa_mode_file() {
  _m="$BAT_DIR/baa/modes"
  _d=$(dev_code)
  _v=$(baa_cloud_variant)
  if [ "$(baa_fmt_use)" = "plain" ]; then
    if [ "$_d" != "unknown" ] && [ -s "$_m/$_d.${_v}_plain.json" ]; then
      printf '%s' "$_m/$_d.${_v}_plain.json"; return 0
    fi
    if [ -s "$_m/${_v}_plain.json" ]; then
      printf '%s' "$_m/${_v}_plain.json"; return 0
    fi
  fi
  _f="$_m/$_d.$_v.json"
  [ -s "$_f" ] && { printf '%s' "$_f"; return 0; }
  [ "$_d" = "unknown" ] && return 1
  _f="$_m/$_v.json"
  [ -s "$_f" ] && { printf '%s' "$_f"; return 0; }
  return 1
}

# 把选中的变体载荷复制成挂载源（baa_src），返回所选文件名；
# 变体缺失时返回 1，调用方沿用当前载荷（不改变已有行为）。
baa_refresh_src() {
  _f=$(baa_mode_file) || { blog "BAA 载荷变体缺失（$(dev_code)/$(baa_cloud_variant)），沿用现有载荷"; return 1; }
  _src=$(baa_src)
  _a=$(sha256sum "$_f" 2>/dev/null | awk '{print $1}')
  _b=$(sha256sum "$_src" 2>/dev/null | awk '{print $1}')
  if [ -z "$_a" ] || [ "$_a" != "$_b" ]; then
    cp -f "$_f" "$_src" 2>/dev/null || { blog "BAA 载荷复制失败: $_f"; return 1; }
    chmod 0644 "$_src" 2>/dev/null
    blog "BAA 载荷: $(basename "$_f") -> $_src（cloud_guard=$(cfg_get cloud_guard 1)）"
  fi
  printf '%s' "$(basename "$_f")"
}

baa_tgt() {
  _d=$(dev_code)
  _std="/odm/etc/charger/BAA_config_$_d.json"
  [ -f "$_std" ] && { printf '%s' "$_std"; return 0; }
  for _dir in /odm/etc/charger /vendor/etc/charger /my_product/etc/charger \
              /system/etc/charger /system_ext/etc/charger /product/etc/charger; do
    [ -d "$_dir" ] || continue
    [ -f "$_dir/BAA_config_$_d.json" ] && { printf '%s' "$_dir/BAA_config_$_d.json"; return 0; }
    _any=$(ls "$_dir"/BAA_config_*.json 2>/dev/null | head -n 1)
    [ -n "$_any" ] && { printf '%s' "$_any"; return 0; }
  done
  printf '%s' "$_std"
  return 1
}

baa_state() {
  _src=$(baa_src)
  _tgt=$(baa_tgt)
  _s=$(sha256sum "$_src" 2>/dev/null | awk '{print $1}')
  _t=$(sha256sum "$_tgt" 2>/dev/null | awk '{print $1}')
  _sl=$(stat -c '%C' "$_src" 2>/dev/null)
  _l=$(stat -c '%C' "$_tgt" 2>/dev/null)
  if [ -n "$_s" ] && [ "$_s" = "$_t" ] && [ -n "$_l" ] && \
     { [ "$_l" = "$_sl" ] || [ "$_l" = "$BAA_LABEL" ]; }; then
    echo active
  else
    echo inactive
  fi
}

# 必须在 PID1 mount namespace 执行；xjctl.sh 在 battery 命令入口会 re-exec 到 PID1。
baa_mount() {
  baa_refresh_src >/dev/null 2>&1
  _src=$(baa_src)
  _tgt=$(baa_tgt)
  if [ ! -f "$_src" ]; then
    blog "BAA source missing: $_src"
    return 1
  fi
  if [ ! -f "$_tgt" ]; then
    blog "BAA target missing: $_tgt"
    return 2
  fi
  _ss=$(stat -c %s "$_src" 2>/dev/null)
  case "$_ss" in ''|*[!0-9]*) _ss=0 ;; esac
  if [ "$_ss" -le 8 ]; then
    blog "BAA source empty: $_src"
    return 1
  fi
  grep -qF " $_tgt " /proc/mounts 2>/dev/null && umount "$_tgt" 2>/dev/null
  _label=$(stat -c '%C' "$_tgt" 2>/dev/null)
  [ -n "$_label" ] || _label="$BAA_LABEL"
  chcon "$_label" "$_src" 2>/dev/null
  chmod 0644 "$_src" 2>/dev/null
  if ! mount -o bind "$_src" "$_tgt" 2>/dev/null; then
    blog "BAA mount failed: $_tgt"
    return 4
  fi
  _a=$(sha256sum "$_src" 2>/dev/null | awk '{print $1}')
  _b=$(sha256sum "$_tgt" 2>/dev/null | awk '{print $1}')
  if [ -z "$_a" ] || [ "$_a" != "$_b" ]; then
    umount "$_tgt" 2>/dev/null
    blog "BAA verify failed, rolled back"
    return 3
  fi
  blog "BAA mounted: $_tgt"
  return 0
}

baa_umount() {
  _tgt=$(baa_tgt)
  if grep -qF " $_tgt " /proc/mounts 2>/dev/null; then
    umount "$_tgt" 2>/dev/null
  fi
  setprop ctl.restart batteryantiaging 2>/dev/null
  blog "BAA umounted: $_tgt"
}

pss_state() {
  _d=$(dumpsys package com.miui.securitycenter 2>/dev/null)
  _seg=$(echo "$_d" | sed -n '/disabledComponents:/,/enabledComponents:/p')
  case "$_seg" in
    *PowerSaveService*) echo disabled ;;
    *) echo enabled ;;
  esac
}

pss_disable() {
  [ "$(pss_state)" = "disabled" ] && return 0
  pm disable "$PSS_COMPONENT" >/dev/null 2>&1
  printf '1' > "$BAT_STATE/pss_disabled_by_module" 2>/dev/null
  blog "PSS disabled"
}

pss_enable() {
  [ "$(pss_state)" = "enabled" ] && return 0
  pm enable "$PSS_COMPONENT" >/dev/null 2>&1
  rm -f "$BAT_STATE/pss_disabled_by_module" 2>/dev/null
  blog "PSS enabled"
}

# ---------- PSS 动态模式（参照 v3.12.9 common.sh pss_guard_tick） ----------
# 矛盾：解容要禁 PSS 防低电量锁容，但 PSS 禁用后「电池健康度」不显示。
# 解法：只在低电量禁用。SOC ≤ pss_low_soc 禁用；≥ pss_low_soc + pss_low_hyst 启用；
# 区间内保持不变（滞回，避免在阈值附近反复 pm enable/disable 抖动）。
# pss_low_soc=0 默认关闭，行为与旧版一致（始终禁用）。
# 轻量：用 pss_dynamic 状态文件记录上次动作，不调 dumpsys（pss_state 很贵）。
# 拆出 pss_guard_soc，供守护 tick 用已采样 SOC 调用，避免每拍重复读节点/配置。
pss_guard_soc() {
  local low=$1 hys=$2 soc=$3 pd
  case "$soc" in ''|*[!0-9]*) return 0 ;; esac
  [ "$soc" -le 100 ] || return 0
  pd=$(cat "$BAT_STATE/pss_dynamic" 2>/dev/null)
  if [ "$soc" -le "$low" ]; then
    # 低电区 → 确保禁用（防锁容）；状态一致时不重复 pm 调用
    [ "$pd" = "disabled" ] && return 0
    pm disable "$PSS_COMPONENT" >/dev/null 2>&1
    printf '1' > "$BAT_STATE/pss_disabled_by_module" 2>/dev/null
    printf 'disabled' > "$BAT_STATE/pss_dynamic" 2>/dev/null
    blog "pss_dynamic: SOC=${soc}% <= ${low}% → PSS 禁用（防低电量锁容）"
  elif [ "$soc" -ge "$((low + hys))" ]; then
    # 安全区 → 确保启用（恢复健康度显示）
    [ "$pd" = "enabled" ] && return 0
    pm enable "$PSS_COMPONENT" >/dev/null 2>&1
    rm -f "$BAT_STATE/pss_disabled_by_module" 2>/dev/null
    printf 'enabled' > "$BAT_STATE/pss_dynamic" 2>/dev/null
    blog "pss_dynamic: SOC=${soc}% >= $((low + hys))% → PSS 启用（恢复电池健康度显示）"
  fi
  return 0
}

pss_guard_tick() {
  local low hys soc
  [ "$(cfg_get pss_disable 1)" = "1" ] || return 0
  low=$(cfg_get pss_low_soc 0)
  case "$low" in ''|*[!0-9]*) return 0 ;; esac
  [ "$low" -gt 0 ] 2>/dev/null || return 0
  hys=$(cfg_get pss_low_hyst 5)
  case "$hys" in ''|*[!0-9]*) hys=5 ;; esac
  soc=${BAT_CAP:-}
  case "$soc" in ''|*[!0-9]*) soc=$(cat "$(bgu_batt_dir)/capacity" 2>/dev/null) ;; esac
  pss_guard_soc "$low" "$hys" "$soc"
}

battery_boot() {
  [ "$(cfg_get unlock 1)" = "1" ] && baa_mount >/dev/null 2>&1
  if [ "$(cfg_get pss_disable 1)" = "1" ]; then
    # pss_low_soc>0（动态模式）时开机不立即禁用：交给 pss_guard_tick 按当前电量决定，
    # 否则「开解容立刻禁用」会和动态模式「只在低电禁用」打架，下一拍又被启用回来。
    _pls=$(cfg_get pss_low_soc 0)
    case "$_pls" in ''|*[!0-9]*) _pls=0 ;; esac
    [ "$_pls" -gt 0 ] 2>/dev/null || pss_disable
  fi
  return 0
}

battery_unlock_on() {
  cfg_set unlock 1
  baa_mount
  if [ "$(cfg_get pss_disable 1)" = "1" ]; then
    # 同 battery_boot：动态模式开启时交给 pss_guard_tick 按电量决定。
    _pls=$(cfg_get pss_low_soc 0)
    case "$_pls" in ''|*[!0-9]*) _pls=0 ;; esac
    [ "$_pls" -gt 0 ] 2>/dev/null || pss_disable
  fi
  setprop ctl.restart batteryantiaging 2>/dev/null
  blog "unlock ON"
}

battery_unlock_off() {
  cfg_set unlock 0
  baa_umount
  pss_enable
  setprop ctl.restart batteryantiaging 2>/dev/null
  blog "unlock OFF"
}

# ---------- 云控屏蔽：云端下发目录隔离 ----------
# 云端（云控）会把 BAA 配置写进下面两个目录，batteryantiaging 读到后覆盖模块策略。
# 命中即 mv 到隔离区（带「隔离文件名|原路径」映射），关闭开关时按映射归还。
CLOUD_DIRS="/data/vendor/batteryantiaging/cloud_config /mnt/vendor/persist/charger/batteryantiaging/cloud_config"
CLOUD_QDIR="$BAT_STATE/cloud_quarantine"
CLOUD_STATE="$BAT_STATE/cloud_guard.state"

cloud_count() {
  _n=0
  for _d in $CLOUD_DIRS; do
    [ -d "$_d" ] || continue
    for _f in "$_d"/*; do [ -e "$_f" ] && _n=$((_n + 1)); done
  done
  echo "$_n"
}

cloud_blocked_total() {
  _v=$(grep '^blocked_total=' "$CLOUD_STATE" 2>/dev/null | tail -n1 | cut -d= -f2)
  case "$_v" in ''|*[!0-9]*) echo 0 ;; *) echo "$_v" ;; esac
}

cloud_state_write() {
  mkdir -p "$BAT_STATE" 2>/dev/null
  {
    echo "last_check=$(date '+%F %T')"
    echo "last_result=$1"
    echo "blocked_total=$2"
  } > "$CLOUD_STATE" 2>/dev/null
}

cloud_quarantine() {
  _n=0
  for _d in $CLOUD_DIRS; do
    [ -d "$_d" ] || continue
    for _f in "$_d"/*; do
      [ -e "$_f" ] || continue
      mkdir -p "$CLOUD_QDIR" 2>/dev/null
      # 隔离文件名内嵌自增序号，同一秒内多个文件不重名（旧版仅秒级时间戳会互相覆盖）。
      _ts=$(date '+%Y%m%d_%H%M%S')
      _b=$(basename "$_f")
      _dst="$CLOUD_QDIR/${_ts}__${_b}"
      _k=1
      while [ -e "$_dst" ]; do _dst="$CLOUD_QDIR/${_ts}__${_k}__${_b}"; _k=$((_k + 1)); done
      if mv "$_f" "$_dst" 2>/dev/null; then
        _n=$((_n + 1))
      elif cp -af "$_f" "$_dst" 2>/dev/null && rm -f "$_f" 2>/dev/null; then
        _n=$((_n + 1))
      else
        blog "云控隔离失败: $_f"
        continue
      fi
      printf '%s|%s\n' "$(basename "$_dst")" "$_f" >> "$CLOUD_QDIR/_sources.log" 2>/dev/null
      blog "云控隔离: $_f -> $_dst"
    done
  done
  echo "$_n"
}

# 关闭云控屏蔽时归还隔离区文件（按 _sources.log 精确回原路径，回不去则放回云端目录）。
cloud_restore() {
  [ -d "$CLOUD_QDIR" ] || { echo 0; return 0; }
  _n=0
  for _f in "$CLOUD_QDIR"/*; do
    [ -e "$_f" ] || continue
    _b=$(basename "$_f")
    [ "$_b" = "_sources.log" ] && continue
    _src=$(grep -F "$_b|" "$CLOUD_QDIR/_sources.log" 2>/dev/null | tail -n1 | cut -d'|' -f2-)
    # 去掉 `<ts>__` 或 `<ts>__<n>__` 前缀还原原文件名
    _orig=$_b
    case "$_orig" in
      [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]_[0-9][0-9][0-9][0-9][0-9][0-9]__*) _orig=${_orig#*__} ;;
    esac
    case "$_orig" in
      [0-9]*__*) _orig=${_orig#*__} ;;
    esac
    if [ -n "$_src" ] && [ -d "$(dirname "$_src")" ] && [ ! -e "$_src" ]; then
      mv "$_f" "$_src" 2>/dev/null && { _n=$((_n + 1)); continue; }
    fi
    for _d in $CLOUD_DIRS; do
      [ -d "$_d" ] || continue
      mv "$_f" "$_d/$_orig" 2>/dev/null && { _n=$((_n + 1)); break; }
    done
  done
  blog "云控归还: $_n 个"
  echo "$_n"
}

# 主循环巡检触点：cloud_guard=1 才隔离，=0 只更新状态（归还由 xjctl cloud off 触发）。
cloud_tick() {
  if [ "$(cfg_get cloud_guard 1)" != "1" ]; then
    cloud_state_write off "$(cloud_blocked_total)"
    return 0
  fi
  _tot=$(cloud_count)
  if [ "$_tot" = "0" ]; then
    cloud_state_write clean "$(cloud_blocked_total)"
    return 0
  fi
  _m=$(cloud_quarantine)
  _bt=$(( $(cloud_blocked_total) + _m ))
  cloud_state_write blocked "$_bt"
  blog "云控巡检: 命中 $_tot 隔离 $_m 累计 $_bt"
}

cloud_status() {
  echo "cloud_guard=$(cfg_get cloud_guard 1)"
  echo "载荷=$(basename "$(baa_src)") 变体=$(baa_cloud_variant)"
  echo "云端目录待检=$(cloud_count) 隔离区=$(ls -1 "$CLOUD_QDIR" 2>/dev/null | grep -vc '^_sources.log$')"
  grep -E '^last_|^blocked_total=' "$CLOUD_STATE" 2>/dev/null
}

# ---------- 守护 tick：低频调度、低电保护与拔线恢复 ----------
# 由主循环每拍调用；采样值全部通过环境变量传入，避免重复读节点：
#   BAT_TEMP / BAT_VOLT / BAT_CAP / IS_CHARGING
#   XJ_NOW（单调秒）/ XJ_CONFIG_CHANGED(0/1) / XJ_CONNECTION_CHANGED(0/1)
# 配置只在首次与 XJ_CONFIG_CHANGED=1 时解析，其余拍用缓存。
BGU_LOW_FILE="$BAT_STATE/low_batt"
BGU_FV_DEFAULT="$BAT_STATE/fv_default"
BGU_FV_HOLD="$BAT_STATE/fv_hold"
BGU_FV_PENDING="$BAT_STATE/fv_restore_pending"
BGU_FV_CHECK="$BAT_STATE/fv_check_at"
BGU_CYCLE_REAL="$BAT_STATE/cycle_real"
BGU_SMART_FV="${BGU_SMART_FV:-/sys/class/xm_power/charger/smart_charge/smart_fv}"
BGU_FV_DOWN="${BGU_FV_DOWN:-/sys/class/qcom-battery/fv_down}"
BGU_QMAX_NODE="${BGU_QMAX_NODE:-/sys/class/xm_power/fg_master/qmax_cyclecount}"
BGU_CYCLE_REAL_FALLBACK="${BGU_CYCLE_REAL_FALLBACK:-/sys/class/xm_power/fg_master/cyclecount}"
BGU_CYCLE_NODES="${BGU_CYCLE_NODES:-/sys/class/xm_power/fg_master/cyclecount /sys/class/power_supply/battery/cycle_count}"
BGU_BAA_INTERVAL="${BGU_BAA_INTERVAL:-300}"
BGU_CLOUD_INTERVAL="${BGU_CLOUD_INTERVAL:-60}"
BGU_FV_INTERVAL="${BGU_FV_INTERVAL:-300}"
# 关机电压合法区间与 App 页面约定一致（2800~3500mV），越界按未配置处理。
BGU_TERM_MIN="${BGU_TERM_MIN:-2800}"
BGU_TERM_MAX="${BGU_TERM_MAX:-3500}"

BGU_INIT=0
BGU_CFG_LOADED=0
BGU_LAST_CHG=""
BGU_UNLOCK_PREV=""
BGU_LAST_BAA=-1
BGU_CLOUD_MODE=""
BGU_LAST_CLOUD=-1

bgu_batt_dir() { printf '%s' "${BGU_BATT_DIR:-${BAT:-/sys/class/power_supply/battery}}"; }

bgu_now() {
  local n=${XJ_NOW:-}
  case "$n" in ''|*[!0-9]*) n=$(cut -d. -f1 /proc/uptime 2>/dev/null) ;; esac
  case "$n" in ''|*[!0-9]*) n=$(date +%s 2>/dev/null) ;; esac
  printf '%s' "${n:-0}"
}

# 取整数配置；空/非数字/越界一律回退默认值。
bgu_int_or() {
  local v=$1 d=$2 lo=$3 hi=$4
  case "$v" in ''|*[!0-9]*) v=$d ;; esac
  if [ "$v" -lt "$lo" ] || [ "$v" -gt "$hi" ]; then v=$d; fi
  printf '%s' "$v"
}

bgu_cfg_load() {
  BGU_CFG_CYCLE=$(cfg_get cycle_fake "")
  BGU_CFG_TERM_V=$(cfg_get term_v "")
  case "$BGU_CFG_TERM_V" in
    ''|*[!0-9]*) BGU_CFG_TERM_V="" ;;
    *)
      if [ "$BGU_CFG_TERM_V" -lt "$BGU_TERM_MIN" ] || [ "$BGU_CFG_TERM_V" -gt "$BGU_TERM_MAX" ]; then
        blog "term_v=${BGU_CFG_TERM_V}mV 超出 ${BGU_TERM_MIN}~${BGU_TERM_MAX}mV，按未配置处理"
        BGU_CFG_TERM_V=""
      fi ;;
  esac
  BGU_CFG_UNLOCK=$(cfg_get unlock 1)
  [ "$BGU_CFG_UNLOCK" = "1" ] || BGU_CFG_UNLOCK=0
  BGU_CFG_PSS_DISABLE=$(cfg_get pss_disable 1)
  [ "$BGU_CFG_PSS_DISABLE" = "1" ] || BGU_CFG_PSS_DISABLE=0
  BGU_CFG_PSS_LOW=$(bgu_int_or "$(cfg_get pss_low_soc 0)" 0 0 100)
  BGU_CFG_PSS_HYST=$(bgu_int_or "$(cfg_get pss_low_hyst 5)" 5 0 100)
  BGU_CFG_CLOUD=$(cfg_get cloud_guard 1)
  [ "$BGU_CFG_CLOUD" = "1" ] || BGU_CFG_CLOUD=0
  BGU_CFG_SHUT_SOC=$(bgu_int_or "$(cfg_get shutdown_check_soc 20)" 20 0 100)
  BGU_CFG_SHUT_EXIT=$(bgu_int_or "$(cfg_get shutdown_exit_soc 25)" 25 0 100)
  # 退出阈值必须高于进入阈值，否则会在门槛上抖动。
  if [ "$BGU_CFG_SHUT_EXIT" -le "$BGU_CFG_SHUT_SOC" ]; then
    BGU_CFG_SHUT_EXIT=$((BGU_CFG_SHUT_SOC + 5))
    [ "$BGU_CFG_SHUT_EXIT" -le 100 ] || BGU_CFG_SHUT_EXIT=100
    [ "$BGU_CFG_SHUT_EXIT" -gt "$BGU_CFG_SHUT_SOC" ] || BGU_CFG_SHUT_SOC=$((BGU_CFG_SHUT_EXIT - 1))
  fi
  BGU_CFG_SHUT_INT=$(bgu_int_or "$(cfg_get shutdown_check_interval 2)" 2 1 60)
  BGU_CFG_LOADED=1
}

bgu_cycle_tick() {
  local cf cr n
  [ "$XJ_READONLY" = "1" ] && return 0
  cf=$BGU_CFG_CYCLE
  [ -n "$cf" ] || return 0
  # 首次伪装前锁存真实值，卸载时 battery_restore 靠它写回。
  if [ ! -f "$BGU_CYCLE_REAL" ]; then
    cr=$(cat "$BGU_QMAX_NODE" 2>/dev/null)
    case "$cr" in ''|*[!0-9]*) cr=$(cat "$BGU_CYCLE_REAL_FALLBACK" 2>/dev/null) ;; esac
    case "$cr" in
      ''|*[!0-9]*) ;;
      *)
        if [ "$cr" != "$cf" ]; then
          mkdir -p "$BAT_STATE" 2>/dev/null
          printf '%s' "$cr" > "$BGU_CYCLE_REAL" 2>/dev/null
        fi ;;
    esac
  fi
  for n in $BGU_CYCLE_NODES; do
    [ -f "$n" ] && [ -w "$n" ] || continue
    [ "$(cat "$n" 2>/dev/null)" = "$cf" ] || { chmod 0666 "$n" 2>/dev/null; printf '%s\n' "$cf" > "$n" 2>/dev/null; }
  done
}

bgu_pss_tick() {
  local soc
  [ "$BGU_CFG_PSS_DISABLE" = "1" ] || return 0
  # 静态模式（pss_low_soc=0）由 battery_boot / 开关一次性处理，不在每拍动作。
  [ "$BGU_CFG_PSS_LOW" -gt 0 ] 2>/dev/null || return 0
  soc=${BAT_CAP:-}
  case "$soc" in ''|*[!0-9]*) return 0 ;; esac
  pss_guard_soc "$BGU_CFG_PSS_LOW" "$BGU_CFG_PSS_HYST" "$soc"
}

bgu_write_node() {
  local p=$1 v=$2
  [ -e "$p" ] || return 1
  chmod 0664 "$p" 2>/dev/null
  printf '%s\n' "$v" > "$p" 2>/dev/null || return 1
  [ "$(cat "$p" 2>/dev/null)" = "$v" ]
}

bgu_fv_tick() {
  local force=$1 now=$2 last ok n cur pending held def changed
  [ "$BGU_CFG_UNLOCK" = "1" ] || return 0
  if [ "${IS_CHARGING:-0}" = "1" ]; then
    # 已压过 0 且未到低频复查点：不读不写，避免每拍访问节点。
    if [ "$force" != "1" ] && [ -f "$BGU_FV_HOLD" ]; then
      last=$(cat "$BGU_FV_CHECK" 2>/dev/null)
      case "$last" in ''|*[!0-9]*) last=-1 ;; esac
      [ "$((now - last))" -lt "$BGU_FV_INTERVAL" ] && return 0
    fi
    ok=1
    for n in "$BGU_SMART_FV" "$BGU_FV_DOWN"; do
      [ -e "$n" ] || continue
      cur=$(cat "$n" 2>/dev/null)
      [ "$cur" = "0" ] && continue
      # 快照只在读到非 0 时落盘：升级/旧版残留的 0 不能当成原值。
      if [ "$n" = "$BGU_SMART_FV" ]; then
        case "$cur" in
          ''|0|*[!0-9]*) ;;
          *) [ -f "$BGU_FV_DEFAULT" ] || printf '%s' "$cur" > "$BGU_FV_DEFAULT" 2>/dev/null ;;
        esac
      fi
      bgu_write_node "$n" 0 || ok=0
    done
    if [ "$ok" = "1" ]; then
      printf '1' > "$BGU_FV_HOLD" 2>/dev/null
      printf '%s' "$now" > "$BGU_FV_CHECK" 2>/dev/null
      rm -f "$BGU_FV_PENDING" 2>/dev/null
    else
      printf '1' > "$BGU_FV_PENDING" 2>/dev/null
    fi
    return 0
  fi

  # 非充电：只有存在待恢复标记、有快照且到低频复查点，或连接/配置变化时才碰节点。
  pending=0; [ -f "$BGU_FV_PENDING" ] && pending=1
  held=0; [ -f "$BGU_FV_HOLD" ] && held=1
  if [ "$force" != "1" ] && [ "$pending" = 0 ] && [ "$held" = 0 ]; then
    [ -f "$BGU_FV_DEFAULT" ] || return 0
    last=$(cat "$BGU_FV_CHECK" 2>/dev/null)
    case "$last" in ''|*[!0-9]*) last=-1 ;; esac
    [ "$((now - last))" -lt "$BGU_FV_INTERVAL" ] && return 0
  fi
  def=$(cat "$BGU_FV_DEFAULT" 2>/dev/null)
  case "$def" in
    ''|0|*[!0-9]*) rm -f "$BGU_FV_HOLD" "$BGU_FV_PENDING" 2>/dev/null; return 0 ;;
  esac
  ok=1; changed=0
  [ -e "$BGU_SMART_FV" ] || { rm -f "$BGU_FV_HOLD" "$BGU_FV_PENDING" 2>/dev/null; return 0; }
  cur=$(cat "$BGU_SMART_FV" 2>/dev/null)
  if [ "$cur" != "$def" ]; then
    changed=1
    bgu_write_node "$BGU_SMART_FV" "$def" || ok=0
  fi
  if [ "$ok" = "1" ]; then
    rm -f "$BGU_FV_HOLD" "$BGU_FV_PENDING" 2>/dev/null
    printf '%s' "$now" > "$BGU_FV_CHECK" 2>/dev/null
    [ "$changed" = "1" ] && blog "smart_fv 已恢复原值 $def"
  else
    # 失败只记一次，后续每拍安静重试，避免日志刷屏。
    [ -f "$BGU_FV_PENDING" ] || blog "smart_fv 恢复失败，稍后重试（目标 $def）"
    printf '1' > "$BGU_FV_PENDING" 2>/dev/null
  fi
  return 0
}

bgu_baa_tick() {
  local force=$1 now=$2
  if [ "$BGU_CFG_UNLOCK" != "1" ]; then
    BGU_UNLOCK_PREV=0
    return 0
  fi
  # 开机、配置变更、重新开启解容时立即巡检一次。
  [ "$BGU_UNLOCK_PREV" = "1" ] || force=1
  if [ "$force" != "1" ] && [ "$BGU_LAST_BAA" != -1 ] && [ "$((now - BGU_LAST_BAA))" -lt "$BGU_BAA_INTERVAL" ]; then
    BGU_UNLOCK_PREV=1
    return 0
  fi
  BGU_UNLOCK_PREV=1
  BGU_LAST_BAA=$now
  [ "$(baa_state)" != "active" ] || return 0
  blog "BAA inactive detected, remounting"
  baa_refresh_src >/dev/null 2>&1
  baa_mount >/dev/null 2>&1
  setprop ctl.restart batteryantiaging 2>/dev/null
  if [ "$(baa_state)" = "active" ]; then
    blog "BAA remounted OK"
  else
    blog "BAA remount failed（仍 inactive，可能缺 PID1 namespace）"
  fi
}

bgu_cloud_tick() {
  local now=$1
  if [ "$BGU_CFG_CLOUD" = "1" ]; then
    if [ "$BGU_CLOUD_MODE" != "1" ] || [ "$BGU_LAST_CLOUD" = -1 ] || [ "$((now - BGU_LAST_CLOUD))" -ge "$BGU_CLOUD_INTERVAL" ]; then
      cloud_tick
      BGU_LAST_CLOUD=$now
    fi
    BGU_CLOUD_MODE=1
  else
    # 关闭时只在「从开到关」写一次 off，之后不再反复调用。
    if [ "$BGU_CLOUD_MODE" != "0" ]; then
      cloud_tick
      BGU_LAST_CLOUD=$now
    fi
    BGU_CLOUD_MODE=0
  fi
}

bgu_volt_uv() {
  local v
  v=$(cat "$(bgu_batt_dir)/voltage_now" 2>/dev/null)
  case "$v" in ''|*[!0-9]*) v=${BAT_VOLT:-} ;; esac
  printf '%s' "$v"
}

bgu_volt_ok() {
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  # 0 与超出手机电池合理范围（1~10V）的读数都视为无效，不据此关机。
  [ "$1" -ge 1000000 ] && [ "$1" -le 10000000 ] || return 1
  return 0
}

bgu_low_set() {
  local soc=$1
  [ -f "$BGU_LOW_FILE" ] && return 0
  mkdir -p "$BAT_STATE" 2>/dev/null
  printf '1' > "$BGU_LOW_FILE" 2>/dev/null
  blog "低电监测进入：SOC=${soc:-未知}% ≤ ${BGU_CFG_SHUT_SOC}%，每 ${BGU_CFG_SHUT_INT}s 检查电压（阈值 ${BGU_CFG_TERM_V}mV）"
}

bgu_low_clear() {
  [ -f "$BGU_LOW_FILE" ] || return 0
  rm -f "$BGU_LOW_FILE" 2>/dev/null
  blog "低电监测退出"
}

bgu_shutdown_check() {
  local mode=$1 uv mv
  if [ "$mode" = "active" ]; then
    uv=$(bgu_volt_uv)
  else
    uv=${BAT_VOLT:-}
  fi
  bgu_volt_ok "$uv" || return 0
  mv=$((uv / 1000))
  [ "$mv" -le "$BGU_CFG_TERM_V" ] || return 0
  log "电压 ${mv}mV ≤ 关机阈值 ${BGU_CFG_TERM_V}mV，执行关机"
  reboot -p 2>/dev/null || svc power shutdown 2>/dev/null
}

bgu_low_tick() {
  local soc low
  if [ -z "$BGU_CFG_TERM_V" ]; then
    bgu_low_clear
    return 0
  fi
  if [ "${IS_CHARGING:-0}" = "1" ]; then
    bgu_low_clear
    return 0
  fi
  soc=${BAT_CAP:-}
  low=0
  case "$soc" in
    ''|*[!0-9]*) low=1 ;;
    *)
      if [ "$soc" -gt 100 ]; then
        low=1
      elif [ -f "$BGU_LOW_FILE" ]; then
        [ "$soc" -ge "$BGU_CFG_SHUT_EXIT" ] || low=1
      else
        [ "$soc" -le "$BGU_CFG_SHUT_SOC" ] && low=1
      fi ;;
  esac
  if [ "$low" = "1" ]; then
    bgu_low_set "$soc"
    # 低电时允许主动读电压。
    bgu_shutdown_check active
  else
    bgu_low_clear
    # 正常电量不额外读节点，但仍复用已采 BAT_VOLT 做电压保护。
    bgu_shutdown_check sampled
  fi
}

battery_guard_tick() {
  local cfg conn chg now force
  [ "$XJ_READONLY" = "1" ] && return 0
  cfg=${XJ_CONFIG_CHANGED:-0}
  conn=${XJ_CONNECTION_CHANGED:-0}
  chg=${IS_CHARGING:-0}
  now=$(bgu_now)
  force=0
  if [ "$BGU_CFG_LOADED" != "1" ] || [ "$cfg" = "1" ]; then
    bgu_cfg_load
    force=1
  fi
  if [ "$BGU_INIT" != "1" ] || [ "$conn" = "1" ] || [ "$BGU_LAST_CHG" != "$chg" ]; then
    force=1
  fi
  bgu_cycle_tick
  bgu_pss_tick
  bgu_fv_tick "$force" "$now"
  bgu_baa_tick "$force" "$now"
  bgu_cloud_tick "$now"
  bgu_low_tick
  BGU_LAST_CHG=$chg
  BGU_INIT=1
  return 0
}

# 低电时主循环应使用的 sleep 秒数；正常返回空，由主循环沿用原间隔。
battery_poll_interval() {
  local tv interval
  [ -f "$BGU_LOW_FILE" ] || return 0
  tv=$(cfg_get term_v "")
  case "$tv" in ''|*[!0-9]*) return 0 ;; esac
  [ "$tv" -ge "$BGU_TERM_MIN" ] && [ "$tv" -le "$BGU_TERM_MAX" ] || return 0
  interval=$(cfg_get shutdown_check_interval 2)
  case "$interval" in ''|*[!0-9]*) interval=2 ;; esac
  [ "$interval" -ge 1 ] && [ "$interval" -le 60 ] || interval=2
  printf '%s' "$interval"
}

# ---------- 卸载/关闭时的节点还原 ----------
# uninstall.sh 在 baa_umount 之后调用：BAA 已交回原厂，这里把 smart_fv 与
# 循环计数写回模块接管前的值，否则模块删除后节点停在 0 / 伪装值。
battery_restore() {
  _sf=/sys/class/xm_power/charger/smart_charge/smart_fv
  if [ -e "$_sf" ]; then
    _def=$(cat "$BAT_STATE/fv_default" 2>/dev/null)
    case "$_def" in
      ''|0|*[!0-9]*) blog "restore: 无 smart_fv 原值快照，保持系统自管" ;;
      *)
        chmod 0664 "$_sf" 2>/dev/null
        echo "$_def" > "$_sf" 2>/dev/null
        if [ "$(cat "$_sf" 2>/dev/null)" = "$_def" ]; then
          blog "restore: smart_fv -> $_def"
        else
          blog "restore: smart_fv 写入失败（wanted $_def）"
        fi ;;
    esac
  fi
  _real=$(cat /sys/class/xm_power/fg_master/qmax_cyclecount 2>/dev/null)
  case "$_real" in ''|*[!0-9]*) _real=$(cat "$BAT_STATE/cycle_real" 2>/dev/null) ;; esac
  case "$_real" in
    ''|*[!0-9]*) blog "restore: 无真实循环值，跳过循环还原" ;;
    *)
      for _n in /sys/class/xm_power/fg_master/cyclecount \
                /sys/class/power_supply/battery/cycle_count; do
        [ -e "$_n" ] || continue
        [ "$(cat "$_n" 2>/dev/null)" = "$_real" ] && continue
        chmod 0666 "$_n" 2>/dev/null
        echo "$_real" > "$_n" 2>/dev/null
        if [ "$(cat "$_n" 2>/dev/null)" = "$_real" ]; then
          blog "restore: $_n -> $_real"
        else
          blog "restore: $_n 写入失败（wanted $_real）"
        fi
      done ;;
  esac
  return 0
}
