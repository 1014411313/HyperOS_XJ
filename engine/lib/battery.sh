#!/system/bin/sh
# engine/lib/battery.sh — 新模块的电池解容核心。
# 只读取 battery/baa 与 battery/config 的数据，不调用旧模块脚本。

# BAT_DIR 只用于定位模块内只读资源（baa/dtbo/配置模板）；用户状态在 BT_STATE（DATA，模块目录外）。
BAT_DIR="$MODDIR/battery"
BAT_STATE="$BT_STATE"
BAA_LABEL="u:object_r:batteryantiaging_cfg_file:s0"
PSS_COMPONENT=com.miui.securitycenter/com.miui.powercenter.provider.PowerSaveService

mkdir -p "$BAT_STATE" 2>/dev/null

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

# ---------- 公版 PPS 解锁 ----------
# 非小米认证充电头会被系统拒走 PPS：验证节点 pd_verifed 保持 0，PPS 契约不成立。
# freepps=1 时把验证节点锁在 1（关掉时还原 0）。纯 shell 写节点，不依赖外部二进制。
# 两个节点都要盖：qcom-battery 是策略侧，Charging_Adapter 是适配器侧。
PPS_NODES="/sys/class/qcom-battery/pd_verifed /sys/class/Charging_Adapter/pd_adapter/usbpd_verifed"

pps_apply() {
  _want="$1"
  for _n in $PPS_NODES; do
    [ -e "$_n" ] || continue
    [ "$(cat "$_n" 2>/dev/null)" = "$_want" ] && continue
    chmod 0664 "$_n" 2>/dev/null
    { echo "$_want" > "$_n"; } 2>/dev/null
    # 部分机型节点只读或驱动立即顶回，回读确认并记日志，避免「看着开了其实没生效」
    [ "$(cat "$_n" 2>/dev/null)" = "$_want" ] || blog "公版 PPS: $_n 写入 ${_want} 失败（回读 $(cat "$_n" 2>/dev/null)）"
  done
  return 0
}

pps_tick() { pps_apply "$(cfg_get freepps 0)"; }

pps_status() {
  echo "freepps=$(cfg_get freepps 0)"
  for _n in $PPS_NODES; do
    [ -e "$_n" ] || continue
    echo "$(basename "$(dirname "$_n")")_$(basename "$_n")=$(cat "$_n" 2>/dev/null)"
  done
}

battery_boot() {
  [ "$(cfg_get unlock 1)" = "1" ] && baa_mount >/dev/null 2>&1
  [ "$(cfg_get pss_disable 0)" = "1" ] && pss_disable
  return 0
}

battery_unlock_on() {
  cfg_set unlock 1
  baa_mount
  [ "$(cfg_get pss_disable 0)" = "1" ] && pss_disable
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
