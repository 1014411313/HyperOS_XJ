#!/system/bin/sh
# engine/lib/dtbo.sh — DTBO 预检、备份、刷入、恢复与镜像库。
# 由 xjctl.sh source；所有外部内容均按数据解析，不 source/eval 远端元数据。

DTBO_DIR="${DTBO_DIR:-$MODDIR/battery/dtbo}"
DTBO_BACKUP_PRIMARY="${DTBO_BACKUP_PRIMARY:-/sdcard/Download/dtbo_backup}"
DTBO_BACKUP_FALLBACK="${DTBO_BACKUP_FALLBACK:-$BT_STATE/dtbo_backup}"
DTBO_IDENTITY_FILE="${DTBO_IDENTITY_FILE:-$DTBO_DIR/identity.tsv}"
DTBO_RUNTIME_DIR="${DTBO_RUNTIME_DIR:-$BT_STATE/dtbo_runtime}"
DTBO_LOCK_DIR="${DTBO_LOCK_DIR:-$BT_STATE/dtbo.lock}"
DTBO_TOKEN_TTL=300
DTBO_MIN_IMAGE_SIZE=1

# 这些包装函数是模拟回归的注入边界；生产实现只调用系统工具。
dtbo_getprop() { getprop "$1" 2>/dev/null; }
dtbo_blockdev_size() { blockdev --getsize64 "$1" 2>/dev/null; }
dtbo_dd() { dd "$@" 2>/dev/null; }
dtbo_pad_file() { truncate -s "$2" "$1" 2>/dev/null; }
dtbo_sha_file() { sha256sum "$1" 2>/dev/null | awk 'NF >= 1 {print $1; exit}'; }
dtbo_curl() { curl "$@"; }

dtbo_need_root() {
  [ "$(id -u 2>/dev/null)" = "0" ] && return 0
  echo "已拒绝: 需要 root 才能执行 DTBO 操作"
  return 1
}

dtbo_valid_dec() {
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  [ "$1" -gt 0 ] 2>/dev/null
}

dtbo_active_slot() {
  _suffix=$(dtbo_getprop ro.boot.slot_suffix)
  _slot=$(dtbo_getprop ro.boot.slot)
  case "$_suffix" in _a) _a=a ;; _b) _a=b ;; '') _a='' ;; *) return 1 ;; esac
  case "$_slot" in a) _b=a ;; b) _b=b ;; '' ) _b='' ;; *) return 1 ;; esac
  # 真机常见只提供其中一个属性；两个都有时必须一致，只缺一个不算矛盾。
  if [ -n "$_a" ] && [ -n "$_b" ]; then
    [ "$_a" = "$_b" ] || return 1
    printf '%s' "$_a"
    return 0
  fi
  [ -n "$_a" ] && { printf '%s' "$_a"; return 0; }
  [ -n "$_b" ] && { printf '%s' "$_b"; return 0; }
  return 1
}

dtbo_slot_of() {
  case "$(basename "$1")" in dtbo_a) echo a ;; dtbo_b) echo b ;; *) echo unknown ;; esac
}

dtbo_dev() {
  _slot=$(dtbo_active_slot) || return 1
  for _base in /dev/block/by-name /dev/block/bootdevice/by-name; do
    _p="$_base/dtbo_$_slot"
    if [ -e "$_p" ]; then
      _real=$(readlink -f "$_p" 2>/dev/null)
      [ -n "$_real" ] && [ -b "$_real" ] || continue
      printf '%s' "$_real"
      return 0
    fi
  done
  return 1
}

dtbo_size() {
  _dev="$1"
  _sz=$(dtbo_blockdev_size "$_dev")
  if dtbo_valid_dec "$_sz"; then printf '%s' "$_sz"; return 0; fi
  _real=$(readlink -f "$_dev" 2>/dev/null)
  [ -n "$_real" ] || _real="$_dev"
  _node="/sys/class/block/$(basename "$_real")/size"
  _sec=$(cat "$_node" 2>/dev/null | tr -d '[:space:]')
  if dtbo_valid_dec "$_sec"; then
    _bytes=$(awk -v s="$_sec" 'BEGIN { printf "%.0f", s * 512 }')
    dtbo_valid_dec "$_bytes" && { printf '%s' "$_bytes"; return 0; }
  fi
  return 1
}

dtbo_human() {
  awk -v b="${1:-0}" 'BEGIN { if (b >= 1048576) printf "%.1fMB", b/1048576; else if (b >= 1024) printf "%.1fKB", b/1024; else printf "%dB", b }'
}

# Use awk for exact SHA validation; this is portable across Android sh variants.
dtbo_is_sha() {
  printf '%s\n' "$1" | awk 'length($0)==64 && $0 ~ /^[0-9A-Fa-f]+$/ {ok=1} END {exit(ok ? 0 : 1)}'
}

dtbo_stat_size() {
  _s=$(stat -c %s "$1" 2>/dev/null)
  dtbo_valid_dec "$_s" || return 1
  printf '%s' "$_s"
}

dtbo_hash_device() {
  _dev="$1"; _size="$2"; _out="$3"
  rm -f "$_out" 2>/dev/null
  dtbo_dd if="$_dev" of="$_out" bs=1M || return 1
  _got=$(dtbo_stat_size "$_out") || return 1
  [ "$_got" = "$_size" ] || return 1
  _sha=$(dtbo_sha_file "$_out")
  dtbo_is_sha "$_sha" || return 1
  printf '%s' "$_sha"
}

dtbo_copy_file() {
  local _src _dst
  _src="$1"; _dst="$2"
  rm -f "$_dst" 2>/dev/null
  cp -f "$_src" "$_dst" 2>/dev/null || return 1
  [ -f "$_dst" ] || return 1
}

dtbo_tmp_dir() {
  mkdir -p "$DTBO_RUNTIME_DIR" 2>/dev/null || return 1
  chmod 700 "$DTBO_RUNTIME_DIR" 2>/dev/null
  [ -d "$DTBO_RUNTIME_DIR" ] || return 1
}

dtbo_now() {
  _now=$(date +%s 2>/dev/null)
  dtbo_valid_dec "$_now" || return 1
  printf '%s' "$_now"
}

dtbo_token() {
  local _created _nonce _v _t
  _created=$(dtbo_now) || return 1
  _nonce=$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d '[:space:]')
  [ "${#_nonce}" -eq 32 ] || return 1
  _v="${1:-xj}-${2:-xj}-$_created-$$-$_nonce"
  _t=$(printf '%s' "$_v" | sha256sum 2>/dev/null | awk '{print $1}')
  dtbo_is_sha "$_t" || return 1
  printf '%s' "$_t"
}

dtbo_token_fresh() {
  local _meta _created _now
  _meta="$1"
  [ -f "$_meta" ] || return 1
  _created=$(dtbo_meta_get created "$_meta")
  _now=$(dtbo_now)
  dtbo_valid_dec "$_created" && dtbo_valid_dec "$_now" || return 1
  [ "$_now" -ge "$_created" ] || return 1
  [ $((_now - _created)) -le "$DTBO_TOKEN_TTL" ] 2>/dev/null
}

dtbo_token_cleanup() {
  local _now _f _mtime _base
  dtbo_tmp_dir || return 0
  _now=$(dtbo_now) || return 0
  for _f in "$DTBO_RUNTIME_DIR"/*.meta "$DTBO_RUNTIME_DIR"/*.img "$DTBO_RUNTIME_DIR"/*.meta.used "$DTBO_RUNTIME_DIR"/*.img.used; do
    [ -f "$_f" ] || continue
    _mtime=$(stat -c %Y "$_f" 2>/dev/null)
    dtbo_valid_dec "$_mtime" || continue
    [ "$_now" -ge "$_mtime" ] || continue
    [ $((_now - _mtime)) -gt "$DTBO_TOKEN_TTL" ] 2>/dev/null || continue
    _base=${_f%.meta.used}; _base=${_base%.img.used}; _base=${_base%.meta}; _base=${_base%.img}
    rm -f "$_base.meta" "$_base.img" "$_base.meta.used" "$_base.img.used" 2>/dev/null
  done
}

dtbo_consume_token() {
  local _meta _snap _used_meta _used_snap
  _meta="$1"; _snap="$2"
  dtbo_token_fresh "$_meta" || return 1
  _used_meta="$_meta.used"; _used_snap="$_snap.used"
  [ ! -e "$_used_meta" ] && [ ! -e "$_used_snap" ] || return 1
  mv "$_meta" "$_used_meta" 2>/dev/null || return 1
  mv "$_snap" "$_used_snap" 2>/dev/null || { mv "$_used_meta" "$_meta" 2>/dev/null; return 1; }
  DTBO_CONSUMED_META="$_used_meta"
  DTBO_CONSUMED_SNAP="$_used_snap"
  return 0
}

dtbo_meta_get() {
  _key="$1"; _file="$2"
  [ -f "$_file" ] || return 1
  sed -n "s/^${_key}=//p" "$_file" | head -n 1
}

dtbo_lock_acquire() {
  mkdir -p "$(dirname "$DTBO_LOCK_DIR")" 2>/dev/null
  if mkdir "$DTBO_LOCK_DIR" 2>/dev/null; then
    printf '%s\n' "$$" > "$DTBO_LOCK_DIR/pid"
    DTBO_LOCK_HELD=1
    return 0
  fi
  _pid=$(cat "$DTBO_LOCK_DIR/pid" 2>/dev/null)
  case "$_pid" in ''|*[!0-9]*) _stale=1 ;; *) kill -0 "$_pid" 2>/dev/null && _stale=0 || _stale=1 ;; esac
  if [ "$_stale" = "1" ] && mkdir "$DTBO_LOCK_DIR.reclaim.$$" 2>/dev/null; then
    rm -f "$DTBO_LOCK_DIR/pid" 2>/dev/null
    rmdir "$DTBO_LOCK_DIR" 2>/dev/null
    mv "$DTBO_LOCK_DIR.reclaim.$$" "$DTBO_LOCK_DIR" 2>/dev/null || { rmdir "$DTBO_LOCK_DIR.reclaim.$$" 2>/dev/null; return 1; }
    printf '%s\n' "$$" > "$DTBO_LOCK_DIR/pid"
    DTBO_LOCK_HELD=1
    return 0
  fi
  echo "已拒绝: DTBO 备份/刷入正在执行（锁冲突）"
  return 73
}

dtbo_lock_release() {
  [ "${DTBO_LOCK_HELD:-0}" = "1" ] || return 0
  rm -f "$DTBO_LOCK_DIR/pid" 2>/dev/null
  rmdir "$DTBO_LOCK_DIR" 2>/dev/null
  DTBO_LOCK_HELD=0
}

dtbo_backup_dir() {
  local _dir
  for _dir in "$DTBO_BACKUP_PRIMARY" "$DTBO_BACKUP_FALLBACK"; do
    mkdir -p "$_dir" 2>/dev/null || continue
    [ -d "$_dir" ] && [ -w "$_dir" ] && { printf '%s' "$_dir"; return 0; }
  done
  return 1
}

dtbo_identity_record() {
  local _code
  _code="$1"
  [ -f "$DTBO_IDENTITY_FILE" ] || return 1
  awk -F'|' -v code="$_code" '$1 == code {print; exit}' "$DTBO_IDENTITY_FILE" 2>/dev/null
}

dtbo_identity_for_file() {
  local _code _file _size _sha _line _c _name _want_size _want_sha _os _prov
  _code="$1"; _file="$2"; _size="$3"; _sha="$4"
  _line=$(dtbo_identity_record "$_code")
  [ -n "$_line" ] || return 1
  IFS='|' read -r _c _name _want_size _want_sha _os _prov <<EOF
$_line
EOF
  [ "$_c" = "$_code" ] && [ "$_want_size" = "$_size" ] && [ "$_want_sha" = "$_sha" ] || return 1
  [ -f "$_file" ] || return 1
  printf '%s|%s|%s|%s|%s|%s' "$_c" "$_name" "$_want_size" "$_want_sha" "$_os" "$_prov"
}

dtbo_model() {
  local _configured _device _odm _name _found
  _configured=$(cfg_get dev_code "")

  # ro.product.device 是 Android 标准设备代号，作为主判据；
  # ro.product.odm.device 只在主属性缺失时兜底，避免厂商名（如 mivendor）误拒。
  _device=$(dtbo_getprop ro.product.device)
  _odm=$(dtbo_getprop ro.product.odm.device)
  _name=$(dtbo_getprop ro.product.name)

  _found=""
  if [ -n "$_device" ]; then
    dtbo_safe_field "$_device" && [ -n "$(dtbo_identity_record "$_device")" ] || { printf '%s' unknown; return 0; }
    _found=$_device
    if [ -n "$_odm" ] && [ -n "$(dtbo_identity_record "$_odm")" ] && [ "$_odm" != "$_found" ]; then
      printf '%s' unknown
      return 0
    fi
  elif [ -n "$_odm" ]; then
    dtbo_safe_field "$_odm" && [ -n "$(dtbo_identity_record "$_odm")" ] || { printf '%s' unknown; return 0; }
    _found=$_odm
  elif [ -n "$_name" ]; then
    dtbo_safe_field "$_name" && [ -n "$(dtbo_identity_record "$_name")" ] || { printf '%s' unknown; return 0; }
    _found=$_name
  fi

  [ -n "$_found" ] || { printf '%s' unknown; return 0; }
  if [ -n "$_configured" ] && [ "$_configured" != "$_found" ]; then
    printf '%s' unknown
    return 0
  fi
  printf '%s' "$_found"
}

dtbo_os_state() {
  local _line _model _os _release _major _min _max
  _line="$1"; _model="$2"
  _os=$(printf '%s\n' "$_line" | awk -F'|' '{print $5}')
  _release=$(dtbo_getprop ro.build.version.release)
  _major=$(printf '%s' "$_release" | sed -n 's/^\([0-9][0-9]*\).*/\1/p')
  case "$_os" in ''|unknown) echo "unknown|未提供可信OS范围（机型=$_model，当前系统=$_release）"; return 0 ;; esac
  case "$_os" in incompatible|incompatible:*) echo "incompatible|身份清单声明不兼容（$_os）"; return 0 ;; esac
  _min=$(printf '%s' "$_os" | sed -n 's/.*min=\([0-9][0-9]*\).*/\1/p')
  _max=$(printf '%s' "$_os" | sed -n 's/.*max=\([0-9][0-9]*\).*/\1/p')
  if [ -n "$_min" ] || [ -n "$_max" ]; then
    dtbo_valid_dec "$_major" || { echo "unknown|当前系统OS版本不可解析（$_release）"; return 0; }
    [ -z "$_min" ] || [ "$_major" -ge "$_min" ] || { echo "incompatible|系统版本低于最小要求（$_os）"; return 0; }
    [ -z "$_max" ] || [ "$_major" -le "$_max" ] || { echo "incompatible|系统版本高于最大要求（$_os）"; return 0; }
    echo "compatible|清单OS范围=$_os，当前系统=$_release"
    return 0
  fi
  echo "unknown|OS范围字段不可验证（$_os）"
}

dtbo_os_ack() {
  [ "${1:-}" = "--ack-os-unknown" ]
}

dtbo_safe_field() {
  case "$1" in *'|'*|*[[:cntrl:]]*) return 1 ;; esac
  return 0
}

dtbo_resolve_source() {
  local _source _model _line _c _name _size _sha _os _prov _file _actual_size _actual_sha
  _source="$1"; _model="$2"
  dtbo_safe_field "$_source" || { echo "已拒绝: 镜像路径含有不支持的元数据分隔符"; return 2; }
  _line=$(dtbo_identity_record "$_model")
  [ -n "$_line" ] || { echo "已拒绝: 当前机型没有可信 DTBO 身份映射 ($_model)"; return 2; }
  IFS='|' read -r _c _name _size _sha _os _prov <<EOF
$_line
EOF
  case "$_source" in
    builtin) _file="$DTBO_DIR/$_name" ;;
    local) _file="$DTBO_DIR/local.img" ;;
    /*) _file="$_source" ;;
    *) _file="$_source" ;;
  esac
  [ -f "$_file" ] || { echo "已拒绝: 镜像文件不存在 ($_file)"; return 2; }
  _actual_size=$(dtbo_stat_size "$_file") || { echo "已拒绝: 无法读取镜像大小"; return 2; }
  _actual_sha=$(dtbo_sha_file "$_file")
  dtbo_is_sha "$_actual_sha" || { echo "已拒绝: 无法读取镜像 SHA256"; return 2; }
  [ "$_actual_size" = "$_size" ] && [ "$_actual_sha" = "$_sha" ] || {
    echo "已拒绝: 镜像未通过当前机型可信大小/SHA映射 ($_model)"; return 2;
  }
  printf '%s|%s|%s|%s|%s|%s|%s' "$_file" "$_c" "$_name" "$_size" "$_sha" "$_os" "$_prov"
}

dtbo_backup_find() {
  local _dir _dev _slot _size _sha _model _man _v _kind _rmodel _rslot _rdev _rsz _rsha _file
  _dir="$1"; _dev="$2"; _slot="$3"; _size="$4"; _sha="$5"; _model="$6"
  _man="$_dir/manifest.txt"
  [ -f "$_man" ] || return 1
  while IFS='|' read -r _v _kind _rmodel _rslot _rdev _rsz _rsha _file; do
    [ "$_v" = v2 ] || continue
    case "$_file" in /*) ;; *) continue ;; esac
    case "$_file" in "$_dir"/*) ;; *) continue ;; esac
    dtbo_safe_field "$_file" && dtbo_is_sha "$_rsha" || continue
    [ "$(dirname "$_file")" = "$_dir" ] || continue
    [ "$_file" = "$(readlink -f "$_file" 2>/dev/null)" ] || continue
    [ "$_rmodel" = "$_model" ] && [ "$_rslot" = "$_slot" ] && [ "$_rdev" = "$_dev" ] && [ "$_rsz" = "$_size" ] && [ "$_rsha" = "$_sha" ] || continue
    [ -f "$_file" ] || continue
    [ "$(dtbo_stat_size "$_file" 2>/dev/null)" = "$_size" ] || continue
    [ "$(dtbo_sha_file "$_file")" = "$_sha" ] || continue
    printf '%s' "$_file"
    return 0
  done < "$_man"
  return 1
}

dtbo_backup_create() {
  local _dir _dev _slot _size _sha _model _old _candidate_dir _tag _tmp _out
  _dir="$1"; _dev="$2"; _slot="$3"; _size="$4"; _sha="$5"; _model="$6"
  _old=""
  for _candidate_dir in "$_dir" "$DTBO_BACKUP_PRIMARY" "$DTBO_BACKUP_FALLBACK"; do
    [ -n "$_candidate_dir" ] || continue
    _old=$(dtbo_backup_find "$_candidate_dir" "$_dev" "$_slot" "$_size" "$_sha" "$_model")
    [ -n "$_old" ] && break
  done
  if [ -n "$_old" ]; then printf '%s' "$_old"; return 0; fi
  _tag="$(date +%Y%m%d-%H%M%S 2>/dev/null)-$$-$(od -An -N8 -tx1 /dev/urandom 2>/dev/null | tr -d '[:space:]')"
  _tmp="$_dir/.dtbo_backup.$_tag.tmp"
  _out="$_dir/$(basename "$_dev")_before_${_tag}.img"
  dtbo_dd if="$_dev" of="$_tmp" bs=1M || { rm -f "$_tmp"; return 1; }
  [ "$(dtbo_stat_size "$_tmp" 2>/dev/null)" = "$_size" ] || { rm -f "$_tmp"; return 1; }
  [ "$(dtbo_sha_file "$_tmp")" = "$_sha" ] || { rm -f "$_tmp"; return 1; }
  mv "$_tmp" "$_out" 2>/dev/null || { rm -f "$_tmp"; return 1; }
  printf 'v2|before_flash|%s|%s|%s|%s|%s|%s\n' "$_model" "$_slot" "$_dev" "$_size" "$_sha" "$_out" >> "$_dir/manifest.txt" || { rm -f "$_out"; return 1; }
  printf '%s' "$_out"
}

dtbo_backup_list() {
  local _slot _model _dev _size _dir _v _kind _rmodel _rslot _rdev _rsz _rsha _file
  dtbo_need_root || return 1
  _slot=$(dtbo_active_slot) || return 2
  _model=$(dtbo_model)
  [ "$_model" != unknown ] || return 2
  _dev=$(dtbo_dev) || return 2
  _size=$(dtbo_size "$_dev") || return 2
  for _dir in "$DTBO_BACKUP_PRIMARY" "$DTBO_BACKUP_FALLBACK"; do
    [ -f "$_dir/manifest.txt" ] || continue
    while IFS='|' read -r _v _kind _rmodel _rslot _rdev _rsz _rsha _file; do
      case "$_file" in "$_dir"/*) ;; *) continue ;; esac
      dtbo_safe_field "$_file" && dtbo_is_sha "$_rsha" || continue
      [ "$_v" = v2 ] && [ "$_rmodel" = "$_model" ] && [ "$_rslot" = "$_slot" ] && [ "$_rdev" = "$_dev" ] && [ "$_rsz" = "$_size" ] || continue
      [ -f "$_file" ] && [ "$(dtbo_stat_size "$_file" 2>/dev/null)" = "$_size" ] && [ "$(dtbo_sha_file "$_file")" = "$_rsha" ] || continue
      printf '%s|%s|%s\n' "$_file" "$_size" "$_rsha"
    done < "$_dir/manifest.txt"
  done
}

dtbo_backup() {
  local _requested _slot _model _dev _size _tmp _sha _dir _backup
  dtbo_need_root || return 1
  _requested="${1:-}"
  dtbo_safe_field "$_requested" || return 2
  dtbo_lock_acquire || return $?
  _slot=$(dtbo_active_slot) || { dtbo_lock_release; echo "已拒绝: ro.boot.slot_suffix/slot 缺失或矛盾"; return 2; }
  _model=$(dtbo_model); [ "$_model" != unknown ] || { dtbo_lock_release; echo "已拒绝: 当前机型身份无法确认"; return 2; }
  _dev=$(dtbo_dev) || { dtbo_lock_release; echo "已拒绝: 未找到当前槽位 canonical DTBO block device"; return 2; }
  _size=$(dtbo_size "$_dev") || { dtbo_lock_release; echo "已拒绝: DTBO 分区大小未知或非正数"; return 2; }
  dtbo_tmp_dir || { dtbo_lock_release; return 1; }
  _tmp="$DTBO_RUNTIME_DIR/backup_current.$$.tmp"
  _sha=$(dtbo_hash_device "$_dev" "$_size" "$_tmp")
  rm -f "$_tmp" 2>/dev/null
  dtbo_is_sha "$_sha" || { dtbo_lock_release; echo "已拒绝: 无法完整读取当前 DTBO 分区"; return 2; }
  if [ -n "$_requested" ]; then
    case "$_requested" in /*) _dir="$_requested" ;; *) dtbo_lock_release; echo "已拒绝: 备份目录必须是绝对路径"; return 2 ;; esac
    mkdir -p "$_dir" 2>/dev/null || { dtbo_lock_release; echo "已拒绝: 备份目录不可用"; return 1; }
  else
    _dir=$(dtbo_backup_dir) || { dtbo_lock_release; echo "已拒绝: 主/回退备份目录均不可用"; return 1; }
  fi
  _backup=$(dtbo_backup_create "$_dir" "$_dev" "$_slot" "$_size" "$_sha" "$_model") || { dtbo_lock_release; echo "备份失败"; return 1; }
  dtbo_lock_release
  echo "backup=$_backup"
  echo "slot=$_slot dev=$_dev size=$_size sha256=$_sha"
  return 0
}

dtbo_snapshot_image() {
  local _source _size _sha _token _snap
  _source="$1"; _size="$2"; _sha="$3"; _token="$4"
  _snap="$DTBO_RUNTIME_DIR/$_token.img"
  dtbo_copy_file "$_source" "$_snap" || return 1
  [ "$(dtbo_stat_size "$_snap")" = "$_size" ] || { rm -f "$_snap"; return 1; }
  [ "$(dtbo_sha_file "$_snap")" = "$_sha" ] || { rm -f "$_snap"; return 1; }
  printf '%s' "$_snap"
}

dtbo_write_and_verify() {
  local _dev _size _snap _sha _backup _slot _model _expected_current_sha _slot_now _dev_now _size_now _model_now _current _current_sha _check _got
  _dev="$1"; _size="$2"; _snap="$3"; _sha="$4"; _backup="$5"; _slot="$6"; _model="$7"; _expected_current_sha="$8"
  _slot_now=$(dtbo_active_slot) && _dev_now=$(dtbo_dev) && _size_now=$(dtbo_size "$_dev_now") && _model_now=$(dtbo_model) || { echo "已拒绝: 写入前设备身份无法读取，分区未写入"; return 2; }
  [ "$_slot_now" = "$_slot" ] && [ "$_dev_now" = "$_dev" ] && [ "$_size_now" = "$_size" ] && [ "$_model_now" = "$_model" ] || { echo "已拒绝: 写入前槽位/设备/大小/机型身份已变化，分区未写入"; return 2; }
  _current="$DTBO_RUNTIME_DIR/write_guard.$$.tmp"
  _current_sha=$(dtbo_hash_device "$_dev_now" "$_size_now" "$_current")
  rm -f "$_current" 2>/dev/null
  [ -n "$_current_sha" ] && [ "$_current_sha" = "$_expected_current_sha" ] || { echo "已拒绝: 写入前目标分区内容已变化，分区未写入"; return 2; }
  dtbo_dd if="$_snap" of="$_dev" bs=1M || { echo "写入失败，准备自动回滚"; dtbo_rollback "$_dev" "$_size" "$_backup" "$_expected_current_sha" "$_slot" "$_model"; return 1; }
  sync
  _check="$DTBO_RUNTIME_DIR/readback.$$.tmp"
  _got=$(dtbo_hash_device "$_dev" "$_size" "$_check")
  rm -f "$_check" 2>/dev/null
  if [ -n "$_got" ] && [ "$_got" = "$_sha" ]; then
    echo "全分区回读校验通过: $_got"
    return 0
  fi
  echo "全分区回读校验失败: got=${_got:-读取失败} want=$_sha，准备自动回滚"
  dtbo_rollback "$_dev" "$_size" "$_backup" "$_expected_current_sha" "$_slot" "$_model"
  return 1
}

dtbo_rollback() {
  local _dev _size _backup _sha _check _got _expected _slot _model _slot_now _dev_now _size_now _model_now
  _dev="$1"; _size="$2"; _backup="$3"; _expected="$4"; _slot="$5"; _model="$6"
  [ -f "$_backup" ] || { echo "严重错误: 已验证备份不存在，无法回滚: $_backup"; return 1; }
  [ "$(dtbo_stat_size "$_backup")" = "$_size" ] || { echo "严重错误: 回滚备份大小异常，保留备份: $_backup"; return 1; }
  _sha=$(dtbo_sha_file "$_backup")
  dtbo_is_sha "$_sha" && [ "$_sha" = "$_expected" ] || { echo "严重错误: 回滚备份 SHA 不匹配，保留备份: $_backup"; return 1; }
  _slot_now=$(dtbo_active_slot) && _dev_now=$(dtbo_dev) && _size_now=$(dtbo_size "$_dev_now") && _model_now=$(dtbo_model) || { echo "严重错误: 回滚目标身份无法读取，保留备份: $_backup"; return 1; }
  [ "$_slot_now" = "$_slot" ] && [ "$_dev_now" = "$_dev" ] && [ "$_size_now" = "$_size" ] && [ "$_model_now" = "$_model" ] || { echo "严重错误: 回滚前槽位/设备/大小/机型已变化，停止写入并保留备份: $_backup"; return 1; }
  dtbo_dd if="$_backup" of="$_dev" bs=1M || { echo "严重错误: 回滚写入失败，保留备份: $_backup"; return 1; }
  sync
  _check="$DTBO_RUNTIME_DIR/rollback_readback.$$.tmp"
  _got=$(dtbo_hash_device "$_dev" "$_size" "$_check")
  rm -f "$_check" 2>/dev/null
  [ "$_got" = "$_sha" ] || { echo "严重错误: 回滚后全分区校验失败，保留备份: $_backup"; return 1; }
  echo "自动回滚成功，已回读校验: $_backup"
  return 0
}

dtbo_preflight() {
  local _restore _source _ack _slot _model _dev _size _curtmp _cursha _backup _isize _isha _line _resolved _ic _name _ios _prov _identity _os_pair _os_state _os_reason _bdir _token _snap _meta _found _rc
  dtbo_need_root || return 1
  _restore=0; _source=""; _ack=""
  if [ "${1:-}" = "--restore" ]; then
    [ "$#" -ge 2 ] && [ "$#" -le 3 ] || return 9
    _restore=1; _source="$2"; _ack="${3:-}"
  else
    [ "$#" -ge 1 ] && [ "$#" -le 2 ] || return 9
    _source="$1"; _ack="${2:-}"
  fi
  [ -n "$_source" ] || { echo "用法: dtbo preflight <builtin|local|镜像路径> [--ack-os-unknown] 或 --restore <备份路径> [--ack-os-unknown]"; return 9; }
  [ -z "$_ack" ] || dtbo_os_ack "$_ack" || return 9
  dtbo_safe_field "$_source" || return 2
  dtbo_lock_acquire || return $?
  dtbo_tmp_dir || { dtbo_lock_release; echo "已拒绝: 私有临时目录不可用"; return 1; }
  _slot=$(dtbo_active_slot) || { dtbo_lock_release; echo "已拒绝: ro.boot.slot_suffix/slot 缺失或矛盾"; return 2; }
  _dev=$(dtbo_dev) || { dtbo_lock_release; echo "已拒绝: 未找到当前槽位 canonical DTBO block device"; return 2; }
  _size=$(dtbo_size "$_dev") || { dtbo_lock_release; echo "已拒绝: DTBO 分区大小未知或非正数"; return 2; }
  _model=$(dtbo_model)
  [ -n "$_model" ] && [ "$_model" != unknown ] || { dtbo_lock_release; echo "已拒绝: 当前机型身份无法确认"; return 2; }
  dtbo_token_cleanup
  _curtmp="$DTBO_RUNTIME_DIR/current.$$.tmp"
  _cursha=$(dtbo_hash_device "$_dev" "$_size" "$_curtmp")
  rm -f "$_curtmp" 2>/dev/null
  dtbo_is_sha "$_cursha" || { dtbo_lock_release; echo "已拒绝: 无法完整读取当前 DTBO 分区"; return 2; }

  if [ "$_restore" = "1" ]; then
    _backup="$_source"
    [ "${_backup#/}" != "$_backup" ] || { dtbo_lock_release; echo "已拒绝: restore 必须使用明确绝对备份路径"; return 2; }
    _isize=$(dtbo_stat_size "$_backup") || { dtbo_lock_release; echo "已拒绝: 备份大小无法读取"; return 2; }
    _isha=$(dtbo_sha_file "$_backup")
    dtbo_is_sha "$_isha" || { dtbo_lock_release; echo "已拒绝: 备份大小/SHA 无法读取"; return 2; }
    _found=$(dtbo_backup_find "$(dirname "$_backup")" "$_dev" "$_slot" "$_isize" "$_isha" "$_model")
    [ "$_found" = "$_backup" ] && [ "$_isize" = "$_size" ] || { dtbo_lock_release; echo "已拒绝: 备份未通过机型/槽位/设备/大小/SHA校验"; return 2; }
    _line="restore|$_backup|$_backup|$_isize|$_isha|$_slot|$_dev|$_size|$_cursha|$_model"
    _os_state="compatible"; _os_reason="恢复已验证的本机槽位备份"
  else
    _resolved=$(dtbo_resolve_source "$_source" "$_model") || { _rc=$?; dtbo_lock_release; return "$_rc"; }
    IFS='|' read -r _image _ic _name _isize _isha _ios _prov <<EOF
$_resolved
EOF
    _identity=$(dtbo_identity_record "$_model") || { dtbo_lock_release; return 2; }
    _os_pair=$(dtbo_os_state "$_identity" "$_model"); _os_state=${_os_pair%%|*}; _os_reason=${_os_pair#*|}
    [ "$_os_state" = incompatible ] && { dtbo_lock_release; echo "已拒绝: $_os_reason"; return 2; }
    if [ "$_os_state" = unknown ] && [ -z "$_ack" ]; then
      _bdir=$(dtbo_backup_dir 2>/dev/null || printf '%s' "$DTBO_BACKUP_PRIMARY")
      echo "ok=0"; echo "model=$_model"; echo "slot=$_slot"; echo "dev=$_dev"; echo "partition=$_dev"; echo "partition_size=$_size"; echo "partition_sha256=$_cursha"
      echo "image=$_image"; echo "source=$_source"; echo "image_size=$_isize"; echo "image_sha256=$_isha"; echo "backup_dir=$_bdir"; echo "os_state=unknown"; echo "os_reason=$_os_reason"; echo "requires_os_ack=1"
      echo "preflight_action=flash"
      echo "summary=model=$_model slot=$_slot partition=$_dev partition_size=$_size image_size=$_isize image_sha256=$_isha backup_dir=$_bdir os_state=unknown"
      dtbo_lock_release; echo "已拒绝: OS范围未知；需要显式 --ack-os-unknown"; return 2
    fi
    [ "$_isize" -le "$_size" ] || { dtbo_lock_release; echo "已拒绝: 镜像大小超过 DTBO 分区"; return 2; }
    _token=$(dtbo_token "$_image" "$_cursha") || { dtbo_lock_release; return 1; }
    _snap=$(dtbo_snapshot_image "$_image" "$_isize" "$_isha" "$_token") || { dtbo_lock_release; echo "已拒绝: 镜像快照或 SHA 校验失败"; return 1; }
    _line="flash|$_image|$_snap|$_isize|$_isha|$_slot|$_dev|$_size|$_cursha|$_model|$_os_state|$_os_reason"
  fi
  if [ "$_restore" = "1" ]; then
    _token=$(dtbo_token "$_source" "$_cursha") || { dtbo_lock_release; return 1; }
    _snap=$(dtbo_snapshot_image "$_source" "$_isize" "$_isha" "$_token") || { dtbo_lock_release; return 1; }
    _meta="$DTBO_RUNTIME_DIR/$_token.meta"
    printf '%s\ncreated=%s\n' "$_line" "$(dtbo_now)" > "$_meta" || { rm -f "$_meta" "$_snap"; dtbo_lock_release; return 1; }
  else
    _meta="$DTBO_RUNTIME_DIR/$_token.meta"
    printf '%s\ncreated=%s\n' "$_line" "$(dtbo_now)" > "$_meta" || { rm -f "$_meta" "$_snap"; dtbo_lock_release; return 1; }
  fi
  chmod 600 "$_meta" "$_snap" 2>/dev/null || { rm -f "$_meta" "$_snap"; dtbo_lock_release; return 1; }
  echo "ok=1"
  echo "model=$_model"
  echo "slot=$_slot"
  echo "dev=$_dev"
  echo "partition=$_dev"
  echo "partition_size=$_size"
  echo "partition_sha256=$_cursha"
  if [ "$_restore" = "1" ]; then echo "image=$_source"; echo "source=verified_backup"; else echo "image=$_image"; echo "source=$_source"; fi
  echo "image_size=$_isize"
  echo "image_sha256=$_isha"
  _bdir=$(dtbo_backup_dir 2>/dev/null || printf '%s' "$DTBO_BACKUP_PRIMARY")
  echo "backup_dir=$_bdir"
  echo "os_state=$_os_state"
  echo "os_reason=$_os_reason"
  echo "snapshot_token=$_token"
  echo "preflight_action=$([ "$_restore" = "1" ] && echo restore || echo flash)"
  echo "summary=model=$_model slot=$_slot partition=$_dev partition_size=$_size image_size=$_isize image_sha256=$_isha backup_dir=$_bdir os_state=$_os_state"
  dtbo_lock_release
  return 0
}

dtbo_flash() {
  local _source _token _ack _meta _snap _kind _tag _orig _snap_record _isize _isha _slot _dev_record _size _cursha _model _os_state _os_reason _resolved_now _image_now _ic_now _name_now _isize_now _isha_now _ios_now _prov_now _slot_now _dev_now _size_now _model_now _check _now_sha _bdir _backup _write _write_sha _rc
  dtbo_need_root || return 1
  _source="${1:-}"; _token=""; _ack=""
  dtbo_safe_field "$_source" || { echo "已拒绝: 镜像路径含有不支持的元数据分隔符"; return 2; }
  [ -n "$_source" ] || { echo "用法: dtbo flash <builtin|local|镜像路径> --confirm <snapshot_token> [--ack-os-unknown]"; return 9; }
  shift 1 2>/dev/null || true
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --confirm)
        [ "$#" -ge 2 ] && [ -n "$2" ] || { echo "已拒绝: --confirm 缺少 snapshot token"; return 9; }
        [ -z "$_token" ] || { echo "已拒绝: 重复 --confirm"; return 9; }
        _token="$2"; shift 2 ;;
      --ack-os-unknown)
        [ -z "$_ack" ] || { echo "已拒绝: 重复 --ack-os-unknown"; return 9; }
        _ack="$1"; shift ;;
      *) echo "已拒绝: 未知 flash 参数"; return 9 ;;
    esac
  done
  [ -n "$_source" ] && [ -n "$_token" ] || { echo "用法: dtbo flash <builtin|local|镜像路径> --confirm <snapshot_token> [--ack-os-unknown]"; return 9; }
  dtbo_os_ack "$_ack" || _ack=""
  dtbo_is_sha "$_token" || { echo "已拒绝: snapshot token 无效"; return 2; }
  dtbo_lock_acquire || return $?
  _meta="$DTBO_RUNTIME_DIR/$_token.meta"; _snap="$DTBO_RUNTIME_DIR/$_token.img"
  dtbo_token_cleanup
  [ -f "$_meta" ] && [ -f "$_snap" ] && dtbo_token_fresh "$_meta" || { dtbo_lock_release; echo "已拒绝: snapshot token 不存在、过期或已失效"; return 2; }
  _kind=$(sed -n '1s/^flash|.*$/flash/p' "$_meta" | head -n1)
  IFS='|' read -r _tag _orig _snap_record _isize _isha _slot _dev_record _size _cursha _model _os_state _os_reason <<EOF
$(sed -n '1p' "$_meta" 2>/dev/null)
EOF
  [ "$_kind" = flash ] && [ "$_tag" = flash ] && dtbo_safe_field "$_orig" && dtbo_safe_field "$_snap_record" && dtbo_is_sha "$_isha" && dtbo_is_sha "$_cursha" || { dtbo_lock_release; echo "已拒绝: snapshot metadata无效"; return 2; }
  dtbo_consume_token "$_meta" "$_snap" || { dtbo_lock_release; echo "已拒绝: snapshot token 已消费或过期"; return 2; }
  _meta="$DTBO_CONSUMED_META"; _snap="$DTBO_CONSUMED_SNAP"
  _resolved_now=$(dtbo_resolve_source "$_source" "$_model") || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: 当前 source 未通过可信身份映射"; return 2; }
  IFS='|' read -r _image_now _ic_now _name_now _isize_now _isha_now _ios_now _prov_now <<EOF
$_resolved_now
EOF
  [ "$_image_now" = "$_orig" ] && [ "$_isize_now" = "$_isize" ] && [ "$_isha_now" = "$_isha" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: 当前 source 与预检固定镜像不一致"; return 2; }
  [ "$_os_state" != incompatible ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; return 2; }
  [ "$_os_state" != unknown ] || dtbo_os_ack "$_ack" || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: OS范围未知；需要显式 --ack-os-unknown"; return 2; }
  _slot_now=$(dtbo_active_slot); _dev_now=$(dtbo_dev); _size_now=$(dtbo_size "$_dev_now")
  [ "$_slot_now" = "$_slot" ] && [ "$_dev_now" = "$_dev_record" ] && [ "$_size_now" = "$_size" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: 确认后活动槽/目标分区/分区大小已变化"; return 2; }
  _model_now=$(dtbo_model); [ "$_model_now" = "$_model" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: 确认后机型已变化"; return 2; }
  [ "$(dtbo_stat_size "$_snap")" = "$_isize" ] && [ "$(dtbo_sha_file "$_snap")" = "$_isha" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: 私有镜像快照校验失败"; return 2; }
  _check="$DTBO_RUNTIME_DIR/current.$$.tmp"; _now_sha=$(dtbo_hash_device "$_dev_now" "$_size_now" "$_check"); rm -f "$_check" 2>/dev/null
  [ "$_now_sha" = "$_cursha" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: 确认后目标分区内容已变化"; return 2; }
  [ "$_isize" -le "$_size" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: 镜像大小超过 DTBO 分区"; return 2; }
  _bdir=$(dtbo_backup_dir) || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已中止: 主/回退备份目录均不可用，分区未被改动"; return 1; }
  _backup=$(dtbo_backup_create "$_bdir" "$_dev_now" "$_slot" "$_size" "$_cursha" "$_model") || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已中止: 完整备份失败，分区未被改动"; return 1; }
  echo "备份=$_backup"
  _write="$DTBO_RUNTIME_DIR/write.$$.tmp"
  dtbo_copy_file "$_snap" "$_write" || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已中止: 写入快照准备失败"; return 1; }
  if [ "$_isize" -lt "$_size" ]; then
    dtbo_pad_file "$_write" "$_size" || { rm -f "$_write" "$_meta" "$_snap"; dtbo_lock_release; echo "已中止: 补零失败，分区未被改动"; return 1; }
  fi
  [ "$(dtbo_stat_size "$_write")" = "$_size" ] || { rm -f "$_write" "$_meta" "$_snap"; dtbo_lock_release; echo "已中止: 精确尺寸补零失败，分区未被改动"; return 1; }
  _write_sha=$(dtbo_sha_file "$_write"); dtbo_is_sha "$_write_sha" || { rm -f "$_write" "$_meta" "$_snap"; dtbo_lock_release; return 1; }
  echo "写入目标=$_dev_now 大小=$_size 镜像SHA=$_isha 分区SHA=$_write_sha"
  dtbo_write_and_verify "$_dev_now" "$_size" "$_write" "$_write_sha" "$_backup" "$_slot" "$_model" "$_cursha"
  _rc=$?
  rm -f "$_write" "$_meta" "$_snap" 2>/dev/null
  dtbo_lock_release
  [ "$_rc" = "0" ] && { echo "flash=success"; echo "重启不会自动执行"; return 0; }
  echo "flash=failed backup=$_backup"
  return 1
}

dtbo_restore() {
  local _backup _token _meta _snap _tag _record _snap_record _isize _isha _slot _dev_record _size _cursha _model _slot_now _dev_now _size_now _model_now _check _now_sha _bdir _before _rc _created
  dtbo_need_root || return 1
  _backup="${1:-}"; _token=""; shift 1 2>/dev/null || true
  dtbo_safe_field "$_backup" || { echo "已拒绝: 备份路径含有不支持的元数据分隔符"; return 2; }
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --confirm) [ "$#" -eq 2 ] && [ -z "$_token" ] && [ -n "$2" ] || return 9; _token="$2"; shift 2 ;;
      *) echo "已拒绝: 未知 restore 参数"; return 9 ;;
    esac
  done
  [ -n "$_backup" ] && [ -n "$_token" ] || { echo "用法: dtbo restore <明确备份路径> --confirm <restore snapshot_token>"; return 9; }
  dtbo_is_sha "$_token" || { echo "已拒绝: restore snapshot token 无效"; return 2; }
  dtbo_lock_acquire || return $?
  _meta="$DTBO_RUNTIME_DIR/$_token.meta"; _snap="$DTBO_RUNTIME_DIR/$_token.img"
  dtbo_token_cleanup
  [ -f "$_meta" ] && [ -f "$_snap" ] && dtbo_token_fresh "$_meta" || { dtbo_lock_release; echo "已拒绝: restore token 不存在、过期或已失效"; return 2; }
  IFS='|' read -r _tag _record _snap_record _isize _isha _slot _dev_record _size _cursha _model <<EOF
$(sed -n '1p' "$_meta" 2>/dev/null)
EOF
  [ "$_tag" = restore ] && [ "$_record" = "$_backup" ] && [ "$_snap_record" = "$_backup" ] && dtbo_safe_field "$_record" && dtbo_is_sha "$_isha" && dtbo_is_sha "$_cursha" || { dtbo_lock_release; echo "已拒绝: restore metadata无效或与所选备份不一致"; return 2; }
  _created=$(dtbo_meta_get created "$_meta")
  dtbo_valid_dec "$_created" || { dtbo_lock_release; echo "已拒绝: restore metadata缺少创建时间"; return 2; }
  dtbo_consume_token "$_meta" "$_snap" || { dtbo_lock_release; echo "已拒绝: restore token 已消费或过期"; return 2; }
  _meta="$DTBO_CONSUMED_META"; _snap="$DTBO_CONSUMED_SNAP"
  _slot_now=$(dtbo_active_slot); _dev_now=$(dtbo_dev); _size_now=$(dtbo_size "$_dev_now")
  [ "$_slot_now" = "$_slot" ] && [ "$_dev_now" = "$_dev_record" ] && [ "$_size_now" = "$_size" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: restore 确认后槽位/目标/大小变化"; return 2; }
  _model_now=$(dtbo_model); [ "$_model_now" = "$_model" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: restore 确认后机型变化"; return 2; }
  [ "$(dtbo_backup_find "$(dirname "$_backup")" "$_dev_now" "$_slot" "$_size" "$_isha" "$_model")" = "$_backup" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: 选择的备份身份已变化"; return 2; }
  [ "$(dtbo_stat_size "$_snap")" = "$_isize" ] && [ "$(dtbo_sha_file "$_snap")" = "$_isha" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: restore 私有备份快照损坏"; return 2; }
  _check="$DTBO_RUNTIME_DIR/current.$$.tmp"; _now_sha=$(dtbo_hash_device "$_dev_now" "$_size_now" "$_check"); rm -f "$_check" 2>/dev/null
  [ "$_now_sha" = "$_cursha" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: restore 确认后目标内容已变化"; return 2; }
  _bdir=$(dtbo_backup_dir) || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已中止: 无法创建恢复前备份"; return 1; }
  _before=$(dtbo_backup_create "$_bdir" "$_dev_now" "$_slot" "$_size" "$_cursha" "$_model") || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已中止: 恢复前完整备份失败"; return 1; }
  _model_now=$(dtbo_model); [ "$_model_now" = "$_model" ] || { rm -f "$_meta" "$_snap"; dtbo_lock_release; echo "已拒绝: restore 确认后机型已变化"; return 2; }
  dtbo_write_and_verify "$_dev_now" "$_size" "$_snap" "$_isha" "$_before" "$_slot" "$_model" "$_cursha"; _rc=$?
  rm -f "$_meta" "$_snap" 2>/dev/null
  dtbo_lock_release
  [ "$_rc" = "0" ] && { echo "restore=success"; echo "重启不会自动执行"; return 0; }
  echo "restore=failed backup=$_before"; return 1
}

dtbo_status() {
  _suffix=$(dtbo_getprop ro.boot.slot_suffix)
  _raw=$(dtbo_getprop ro.boot.slot)
  echo "槽位属性: slot_suffix=${_suffix:-空} slot=${_raw:-空}"
  _slot=$(dtbo_active_slot 2>/dev/null) || { echo "槽位: 无法确认（两个属性都为空或格式非法）"; return 1; }
  _dev=$(dtbo_dev 2>/dev/null) || { echo "DTBO分区: 当前槽 canonical block device 未找到"; return 1; }
  _size=$(dtbo_size "$_dev" 2>/dev/null || true)
  _model=$(dtbo_model)
  echo "槽位=$_slot"
  echo "分区=$_dev"
  echo "分区大小=${_size:-未知}"
  echo "机型=$_model"
  echo "镜像映射=$(dtbo_identity_record "$_model" | cut -d'|' -f2-4)"
  return 0
}

dtbo_local_img() { printf '%s' "$DTBO_DIR/local.img"; }
dtbo_haslocal() { [ -f "$(dtbo_local_img)" ] && echo yes || echo no; }

dtbo_resolve() {
  _model=$(dtbo_model)
  _r=$(dtbo_resolve_source local "$_model" 2>/dev/null || true)
  [ -n "$_r" ] || _r=$(dtbo_resolve_source builtin "$_model" 2>/dev/null || true)
  if [ -n "$_r" ]; then
    IFS='|' read -r _p _c _n _s _h _o _pr <<EOF
$_r
EOF
    echo "img=$_p"; echo "name=$(basename "$_p")"; echo "size=$_s"; echo "sha256=$_h"; echo "source=$([ "$_p" = "$DTBO_DIR/local.img" ] && echo local || echo builtin)"; echo "hint=已通过当前机型可信大小/SHA映射"
    return 0
  fi
  echo "img="; echo "name="; echo "size=0"; echo "sha256="; echo "source=none"; echo "hint=没有通过可信身份映射的 DTBO 镜像"
  return 1
}

dtbo_flashlocal() {
  echo "旧 flashlocal 入口已拒绝: 必须先 preflight，再用 snapshot token 确认"
  return 9
}

dtbo_download() {
  dtbo_need_root || return 1
  _ack="${1:-}"; _model=$(dtbo_model)
  dtbo_os_ack "$_ack" || _ack=""
  _line=$(dtbo_identity_record "$_model") || { echo "已拒绝: 当前机型没有可信 DTBO 身份映射"; return 2; }
  IFS='|' read -r _c _name _size _sha _os _prov <<EOF
$_line
EOF
  _pair=$(dtbo_os_state "$_line" "$_model"); _state=${_pair%%|*}; _reason=${_pair#*|}
  [ "$_state" = incompatible ] && { echo "已拒绝: $_reason"; return 2; }
  if [ "$_state" = unknown ] && ! dtbo_os_ack "$_ack"; then
    echo "ok=0"; echo "model=$_model"; echo "os_state=unknown"; echo "os_reason=$_reason"; echo "requires_os_ack=1"
    echo "summary=model=$_model os_state=unknown os_reason=$_reason"
    echo "已拒绝: OS范围未知；需要显式 --ack-os-unknown"
    return 2
  fi
  _base=$(cfg_get dtbo_base "https://gitee.com/xiaomirezhale/miwkjr-dtbo/raw/master"); _base=${_base%/}
  [ -n "$_base" ] || { echo "已拒绝: 未配置 DTBO 下载源"; return 1; }
  dtbo_tmp_dir || return 1
  _idx="$DTBO_RUNTIME_DIR/index.$$.json"; _img="$DTBO_RUNTIME_DIR/download.$$.img"
  dtbo_curl -fsSL --connect-timeout 20 --max-time 60 -o "$_idx" "$_base/dtbo/index.json" 2>/dev/null || { rm -f "$_idx"; echo "下载失败: index"; return 1; }
  _compact=$(tr -d '[:space:]' < "$_idx" 2>/dev/null)
  _obj=$(printf '%s' "$_compact" | sed -n "s/.*\"$_model\":{\([^{}]*\)}.*/\1/p")
  [ -n "$_obj" ] || { rm -f "$_idx"; echo "已拒绝: index 缺少当前机型对象"; return 2; }
  _rf=$(printf '%s' "$_obj" | sed -n 's/.*"file":"\([^"]*\)".*/\1/p'); _rs=$(printf '%s' "$_obj" | sed -n 's/.*"size":\([0-9][0-9]*\).*/\1/p'); _rh=$(printf '%s' "$_obj" | sed -n 's/.*"sha256":"\([0-9A-Fa-f]\{64\}\)".*/\1/p')
  [ "$_rf" = "$_name" ] && [ "$_rs" = "$_size" ] && [ "$_rh" = "$_sha" ] || { rm -f "$_idx"; echo "已拒绝: 远端 index 身份与本地可信映射不一致"; return 2; }
  case "$_rf" in ''|/*|*..*|*\?*|*\&*|*\;*) rm -f "$_idx"; echo "已拒绝: index file 路径不安全"; return 2 ;; esac
  dtbo_curl -fsSL --connect-timeout 20 --max-time 600 -o "$_img" "$_base/dtbo/$_rf" 2>/dev/null || { rm -f "$_idx" "$_img"; echo "下载失败: 镜像"; return 1; }
  [ "$(dtbo_stat_size "$_img")" = "$_size" ] && [ "$(dtbo_sha_file "$_img")" = "$_sha" ] || { rm -f "$_idx" "$_img"; echo "已拒绝: 下载镜像大小/SHA校验失败"; return 2; }
  mkdir -p "$DTBO_DIR" 2>/dev/null || { rm -f "$_idx" "$_img"; return 1; }
  _dst=$(dtbo_local_img); _tmp="$DTBO_DIR/.local.img.$$.tmp"
  cp -f "$_img" "$_tmp" 2>/dev/null && mv -f "$_tmp" "$_dst" 2>/dev/null || { rm -f "$_idx" "$_img" "$_tmp"; echo "写入 local.img 失败"; return 1; }
  rm -f "$_idx" "$_img"
  echo "已保存: $_dst size=$_size sha256=$_sha model=$_model os_state=$_state"
  return 0
}

DTBO_EDIRS_FILE="${DTBO_EDIRS_FILE:-$STATE/extra_dirs.txt}"
dtbo_dirs_show() {
  echo "[扫描目录]"
  if [ -s "$DTBO_EDIRS_FILE" ]; then while IFS= read -r _d; do [ -n "$_d" ] || continue; [ -d "$_d" ] && echo "  $_d" || echo "  $_d (目录不存在)"; done < "$DTBO_EDIRS_FILE"; else echo "  (无)"; fi
  return 0
}
dtbo_dirs_add() {
  _p=$(printf '%s' "$1" | sed 's:/*$::'); case "$_p" in /*) ;; *) echo "请使用绝对路径"; return 2 ;; esac
  [ -d "$_p" ] || { echo "目录不存在: $_p"; return 2; }; mkdir -p "$STATE" 2>/dev/null; touch "$DTBO_EDIRS_FILE" 2>/dev/null
  grep -Fxq "$_p" "$DTBO_EDIRS_FILE" 2>/dev/null && { echo "已在列表: $_p"; return 0; }; printf '%s\n' "$_p" >> "$DTBO_EDIRS_FILE"; echo "已添加: $_p"
}
dtbo_dirs_del() {
  _p=$(printf '%s' "$1" | sed 's:/*$::'); [ -s "$DTBO_EDIRS_FILE" ] || return 0; grep -Fxq "$_p" "$DTBO_EDIRS_FILE" 2>/dev/null || return 2
  _tmp="$DTBO_EDIRS_FILE.$$.tmp"
  grep -Fxv "$_p" "$DTBO_EDIRS_FILE" > "$_tmp" 2>/dev/null || { rm -f "$_tmp"; return 1; }
  mv "$_tmp" "$DTBO_EDIRS_FILE" 2>/dev/null || { rm -f "$_tmp"; return 1; }
  echo "已移除: $_p"
}
dtbo_scan_list() {
  _i=0
  if [ -s "$DTBO_EDIRS_FILE" ]; then while IFS= read -r _d; do [ -d "$_d" ] || continue; for _f in "$_d"/*.img; do [ -f "$_f" ] || continue; _i=$((_i+1)); _s=$(dtbo_stat_size "$_f" 2>/dev/null || echo 0); _h=$(dtbo_sha_file "$_f" | cut -c1-12); printf '%s|%s|%s|%s\n' "$_i" "$_f" "$_s" "$_h"; done; done < "$DTBO_EDIRS_FILE"; fi
  [ "$_i" = 0 ] && echo "(未找到 .img)"
}

dtbo_autobackup() {
  [ "$(id -u 2>/dev/null)" = 0 ] || return 0
  dtbo_lock_acquire || return 0
  _model=$(dtbo_model)
  [ -n "$_model" ] && [ "$_model" != unknown ] || { dtbo_lock_release; return 0; }
  _slot=$(dtbo_active_slot 2>/dev/null) || { dtbo_lock_release; return 0; }; _dev=$(dtbo_dev 2>/dev/null) || { dtbo_lock_release; return 0; }; _size=$(dtbo_size "$_dev" 2>/dev/null) || { dtbo_lock_release; return 0; }
  dtbo_tmp_dir || { dtbo_lock_release; return 0; }; _tmp="$DTBO_RUNTIME_DIR/auto_current.$$.tmp"; _sha=$(dtbo_hash_device "$_dev" "$_size" "$_tmp"); rm -f "$_tmp"; dtbo_is_sha "$_sha" || { dtbo_lock_release; return 0; }
  _dir=$(dtbo_backup_dir 2>/dev/null) || { dtbo_lock_release; return 0; }; _f=$(dtbo_backup_create "$_dir" "$_dev" "$_slot" "$_size" "$_sha" "$_model"); [ -n "$_f" ] && log "dtbo: autobackup $_f"; dtbo_lock_release; return 0
}
