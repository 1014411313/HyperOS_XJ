#!/system/bin/sh
# engine/lib/env.sh — 引擎公共环境：路径、日志、配置读写、JSON 片段。
# 本文件只被 source，不单独执行；root 授权只在 xjctl / xjdaemon 入口发生一次。

MODDIR="/data/adb/modules/rezhale_toolbox"
[ -d "$MODDIR" ] || MODDIR="$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)"

# 用户可变数据根放在模块目录外：Magisk 升级会整体删除模块目录，数据在内会丢（bug-045）。
DATA_DIR="/data/adb/rezhale_toolbox"
CONFDIR="$DATA_DIR/config"
CACHE="$DATA_DIR/cache"
STATE="$DATA_DIR/state"
LOGDIR="$DATA_DIR/log"
BT_CONF="$DATA_DIR/battery/settings.conf"
BT_STATE="$DATA_DIR/battery/state"

# 只读开关：XJ_READONLY=1 时本文件与各 lib 不做任何落盘（selfcheck 只读自检用）。
# 未设该变量时行为与原来完全一致。
[ "$XJ_READONLY" = "1" ] || mkdir -p "$CONFDIR" "$CACHE" "$STATE" "$LOGDIR" "$BT_STATE" 2>/dev/null

log() { echo "$(date '+%F %T') [engine] $*" >> "$LOGDIR/engine.log" 2>/dev/null; }

mod_version() {
  grep -m1 '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2 | tr -d '\r'
}

# ---------- 首次 seed：配置从模块内模板复制（settings.conf 模板含机型 dev_code） ----------
seed_settings() {
  [ "$XJ_READONLY" = "1" ] && return 0
  [ -s "$CONFDIR/settings.json" ] && return 0
  mkdir -p "$CONFDIR" 2>/dev/null
  cp -f "$MODDIR/default_settings.json" "$CONFDIR/settings.json" 2>/dev/null \
    || printf '{}\n' > "$CONFDIR/settings.json"
}

seed_bt_conf() {
  [ "$XJ_READONLY" = "1" ] && return 0
  [ -s "$BT_CONF" ] && return 0
  mkdir -p "$(dirname "$BT_CONF")" 2>/dev/null
  cp -f "$MODDIR/battery/config/settings.conf" "$BT_CONF" 2>/dev/null
}
seed_settings
seed_bt_conf

runtime_config_refresh() {
  local sig
  sig=$(stat -c '%y:%s:%i' "$CONFDIR/settings.json" "$BT_CONF" "$STATE/config.changed" 2>/dev/null)
  XJ_CONFIG_CHANGED=0
  [ "$sig" != "${XJ_CONFIG_SIGNATURE:-}" ] || return 0
  XJ_CONFIG_SIGNATURE=$sig
  XJ_JSON_CACHE=$(awk '
    /^[[:space:]]*"[A-Za-z_][A-Za-z0-9_]*"[[:space:]]*:/ {
      line=$0; sub(/^[[:space:]]*"/, "", line)
      key=line; sub(/".*/, "", key)
      sub(/^[^"]*"[[:space:]]*:[[:space:]]*/, "", line)
      if (line ~ /^\{/ || line ~ /^\[/) next
      sub(/[[:space:]]*,?[[:space:]]*$/, "", line)
      if (line ~ /^"/) { sub(/^"/, "", line); sub(/".*$/, "", line) }
      print key "=" line
    }' "$CONFDIR/settings.json" 2>/dev/null)
  XJ_BT_CACHE=$(cat "$BT_CONF" 2>/dev/null)
  XJ_CONFIG_CACHED=1
  XJ_CONFIG_CHANGED=1
}

runtime_config_value() {
  local line key=$1 fallback=$2 value= found=0
  while IFS= read -r line; do
    case "$line" in "$key="*) value=${line#*=}; found=1 ;; esac
  done <<EOF
$3
EOF
  if [ "$found" = 1 ] && [ -n "$value" ]; then printf '%s' "$value"; else printf '%s' "$fallback"; fi
}

config_changed() {
  [ "$XJ_READONLY" = 1 ] || touch "$STATE/config.changed" 2>/dev/null
}

json_get() {
  if [ "${XJ_CONFIG_CACHED:-0}" = 1 ]; then
    runtime_config_value "$1" "${2:-}" "$XJ_JSON_CACHE"
    return
  fi
  _f="$CONFDIR/settings.json"; _k="$1"; _d="${2:-}"
  seed_settings
  [ -s "$_f" ] || { printf '%s' "$_d"; return 0; }
  _v=$(awk -v k="\"$_k\"" '
    {
      if (match($0, k "[ \t]*:[ \t]*")) {
        v = substr($0, RSTART + RLENGTH)
        sub(/[ \t]*,?[ \t]*$/, "", v)
        if (v ~ /^"/) { sub(/^"/, "", v); sub(/".*$/, "", v) }
        else { sub(/[ \t].*/, "", v) }
        print v; exit
      }
    }' "$_f" 2>/dev/null)
  [ -n "$_v" ] && printf '%s' "$_v" || printf '%s' "$_d"
}

json_set() {
  local _f _k _v _t _jv _esc _rc _tmp
  _f="$CONFDIR/settings.json"; _k="$1"; _v="$2"; _t="${3:-str}"
  seed_settings
  _tmp="$_f.new.$(date +%s).$$"
  case "$_t" in
    num) _jv="$_v" ;;
    bool) case "$_v" in true|1|on|yes) _jv=true ;; *) _jv=false ;; esac ;;
    *) _esc=$(printf '%s' "$_v" | sed 's/\\/\\\\/g; s/"/\\"/g'); _jv="\"$_esc\"" ;;
  esac
  awk -v k="$_k" -v jv="$_jv" '
    {
      line = $0
      if (match(line, /^[[:space:]]*"[^"]+"[[:space:]]*:[[:space:]]*/)) {
        key = substr(line, RSTART, RLENGTH)
        sub(/^[[:space:]]*"/, "", key)
        sub(/"[[:space:]]*:[[:space:]]*$/, "", key)
        if (key == k) {
          prefix = substr(line, 1, RSTART + RLENGTH - 1)
          rest = substr(line, RSTART + RLENGTH)
          comma = (rest ~ /,[[:space:]]*$/) ? "," : ""
          line = prefix jv comma
        }
      }
      print line
    }
  ' "$_f" > "$_tmp" 2>/dev/null && mv -f "$_tmp" "$_f" 2>/dev/null
  _rc=$?
  rm -f "$_tmp" 2>/dev/null
  [ "$_rc" -eq 0 ] || return "$_rc"
  config_changed
}

# ---------- settings.conf（电池/解容配置，KEY=value） ----------
cfg_get() {
  if [ "${XJ_CONFIG_CACHED:-0}" = 1 ]; then
    runtime_config_value "$1" "${2:-}" "$XJ_BT_CACHE"
    return
  fi
  _k="$1"; _d="${2:-}"
  [ -s "$BT_CONF" ] || { printf '%s' "$_d"; return 0; }
  _v=$(grep -aE "^${_k}=" "$BT_CONF" 2>/dev/null | tail -n1 | cut -d= -f2-)
  [ -n "$_v" ] && printf '%s' "$_v" || printf '%s' "$_d"
}

cfg_set() {
  _k="$1"; _v="$2"
  mkdir -p "$(dirname "$BT_CONF")" 2>/dev/null
  touch "$BT_CONF" 2>/dev/null
  # 末行无换行时先补一个换行：否则下方 >> 追加会与末行粘连，把键名拼进上一行的值里
  # 写坏配置（同款事故见 ref_battery/scripts/common.sh 的 cfg_set 修复）
  if [ -s "$BT_CONF" ] && [ -n "$(tail -c 1 "$BT_CONF" 2>/dev/null)" ]; then
    printf '\n' >> "$BT_CONF"
  fi
  if grep -aqE "^${_k}=" "$BT_CONF" 2>/dev/null; then
    # awk 按键前缀（index==1）精确替换，不依赖任何分隔符：值里含 |/ @/ 等任意字符都原样输出。
    # 键值经环境变量传入而非 -v：awk 的 -v 会对值做反斜杠转义解释，ENVIRON 完全字面。
    # 所有同名键行都替换，与 cfg_get 的 tail -n1（读最后一行）语义对齐，顺带自愈历史重复键。
    # 原子替换：原来用 cat 原地写回，写入被打断会把 settings.conf 截断（实测事故：键值丢失/文件截尾）。
    _cfg_tmp="$BT_CONF.tmp.$$"
    if _cfg_k="$_k" _cfg_v="$_v" awk '
      index($0, ENVIRON["_cfg_k"] "=") == 1 { print ENVIRON["_cfg_k"] "=" ENVIRON["_cfg_v"]; next }
      { print }
    ' "$BT_CONF" > "$_cfg_tmp" 2>/dev/null; then
      mv -f "$_cfg_tmp" "$BT_CONF" 2>/dev/null || rm -f "$_cfg_tmp" 2>/dev/null
    else
      rm -f "$_cfg_tmp" 2>/dev/null
    fi
  else
    printf '%s=%s\n' "$_k" "$_v" >> "$BT_CONF"
  fi
  config_changed
}

# ---------- 功率档（inf/fast/mid/slow，或 5~119 的自定义瓦数） ----------
norm_power() {
  case "$1" in
    inf|fast|mid|slow) printf '%s' "$1" ;;
    ''|*[!0-9]*) printf inf ;;
    *)
      _n=$1
      if [ "$_n" -ge 120 ]; then printf inf; else
        [ "$_n" -lt 5 ] && _n=5
        printf '%s' "$_n"
      fi ;;
  esac
}
power_tier() { norm_power "$(cfg_get power_limit inf)"; }
power_rank() {
  case "$1" in
    inf) echo 999 ;; fast) echo 40 ;; mid) echo 25 ;; slow) echo 10 ;;
    ''|*[!0-9]*) echo 999 ;; *) echo "$1" ;;
  esac
}

# ---------- JSON 片段（status 输出统一组装） ----------
js_line() { printf '%s\t%s\t%s\n' "$1" "$2" "$3"; }

js_emit() {
  # 无参数时 awk 从 stdin 读：只读 status 不落 frag 文件，片段走管道直接组装
  awk -F'\t' '
    {
      if (NR > 1) printf ","
      v = $2
      if ($3 != "num") { gsub(/\\/, "\\\\", v); gsub(/"/, "\\\"", v); v = "\"" v "\"" }
      printf "\"%s\":%s", $1, v
    }
    END { printf "\n" }
  ' ${1:+"$1"} | sed 's/^/{/; s/$/}/'
}
