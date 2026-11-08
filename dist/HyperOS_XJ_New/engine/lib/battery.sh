#!/system/bin/sh
# engine/lib/battery.sh — 新模块的电池解容核心。
# 只读取 battery/baa 与 battery/config 的数据，不调用旧模块脚本。

BAT_DIR="$MODDIR/battery"
BAT_CONF="$BAT_DIR/config/settings.conf"
BAT_STATE="$BAT_DIR/state"
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

# 选定载荷文件：优先机型专用的 <dev>.<变体>.json，其次通用 <变体>.json。
# 机型未识别（unknown）时不回退通用文件，避免把别的机型载荷挂到未知机型上。
baa_mode_file() {
  _m="$BAT_DIR/baa/modes"
  _d=$(dev_code)
  _v=$(baa_cloud_variant)
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

# ---------- 原厂上限强制覆盖（fv_force） ----------
# 载荷里 jeita-term-para 的 vterm 才是原厂满电上限；只写 constant_charge_voltage
# 节点压不住原厂策略，必须把载荷内所有 vterm 一起改写。
BAA_CRYPT="$MODDIR/bin/baa_crypt"

baa_crypt_bin() {
  [ -f "$BAA_CRYPT" ] || return 1
  # 安装器把模块内文件统一设成 0644，这里补可执行位。
  [ -x "$BAA_CRYPT" ] || chmod 0755 "$BAA_CRYPT" 2>/dev/null
  [ -x "$BAA_CRYPT" ] || return 1
  printf '%s' "$BAA_CRYPT"
  return 0
}

# fv_force=1：解密选中载荷 -> 所有 "vterm":<n> 改成 fv_v -> 用载荷自带 IV 重新加密 -> 写回 baa_src。
# 用 cat 原地覆盖而不替换文件，inode 不变，已 bind 挂载到 charger 目录的载荷才会跟着变。
baa_fv_force_apply() {
  [ "$(cfg_get fv_force 0)" = "1" ] || return 1
  _v=$(cfg_get fv_v "")
  case "$_v" in ''|*[!0-9]*) blog "fv_force=1 但 fv_v 非法（$_v），回退原载荷"; return 1 ;; esac
  _crypt=$(baa_crypt_bin) || { blog "fv_force=1 但 bin/baa_crypt 不可执行，回退原载荷"; return 1; }
  _src=$(baa_src)
  [ -s "$_src" ] || { blog "fv_force=1 但载荷不存在: $_src"; return 1; }
  case "$(head -c 8 "$_src" 2>/dev/null)" in
    BAA_ENC:*) ;;
    *) blog "fv_force=1 但载荷非 BAA_ENC: 密文，回退原载荷"; return 1 ;;
  esac
  _tmp="$BAT_STATE/fv_force.tmp"
  mkdir -p "$_tmp" 2>/dev/null
  _iv=$("$_crypt" iv "$_src" 2>/dev/null)
  "$_crypt" decrypt "$_src" "$_tmp/plain" 2>/dev/null || { blog "载荷解密失败，回退原载荷: $_src"; return 1; }
  _n=$(grep -o '"vterm":[0-9]*' "$_tmp/plain" 2>/dev/null | wc -l)
  case "$_n" in ''|*[!0-9]*) _n=0 ;; esac
  [ "$_n" -gt 0 ] || { blog "载荷内无 vterm 字段，回退原载荷: $_src"; return 1; }
  sed "s/\"vterm\":[0-9]*/\"vterm\":$_v/g" "$_tmp/plain" > "$_tmp/plain.new" 2>/dev/null || \
    { blog "vterm 替换失败，回退原载荷"; return 1; }
  "$_crypt" encrypt "$_tmp/plain.new" "$_tmp/enc" "$_iv" 2>/dev/null || { blog "载荷加密失败，回退原载荷"; return 1; }
  if ! "$_crypt" decrypt "$_tmp/enc" "$_tmp/check" 2>/dev/null || ! cmp -s "$_tmp/plain.new" "$_tmp/check"; then
    blog "载荷重加密校验失败，回退原载荷: $_src"
    rm -rf "$_tmp" 2>/dev/null
    return 1
  fi
  if ! cat "$_tmp/enc" > "$_src" 2>/dev/null; then
    blog "载荷写回失败，回退原载荷: $_src"
    rm -rf "$_tmp" 2>/dev/null
    return 1
  fi
  chmod 0644 "$_src" 2>/dev/null
  rm -rf "$_tmp" 2>/dev/null
  echo "fv=$_v" > "$BAT_STATE/fv_force.applied" 2>/dev/null
  blog "fv_force 生效：$_n 处 vterm -> ${_v}（$(basename "$_src")）"
  return 0
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
  baa_fv_force_apply >/dev/null 2>&1
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
