#!/system/bin/sh
# engine/lib/env.sh — 引擎公共环境：路径、日志、配置读写、JSON 片段。
# 本文件只被 source，不单独执行；root 授权只在 xjctl / xjdaemon 入口发生一次。

MODDIR="/data/adb/modules/HyperOS_XJ"
[ -d "$MODDIR" ] || MODDIR="$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)"

# 用户可变数据根放在模块目录外：Magisk 升级会整体删除模块目录，数据在内会丢（bug-045）。
DATA_DIR="/data/adb/HyperOS_XJ"
CONFDIR="$DATA_DIR/config"
CACHE="$DATA_DIR/cache"
STATE="$DATA_DIR/state"
LOGDIR="$DATA_DIR/log"
BT_CONF="$DATA_DIR/battery/settings.conf"
BT_STATE="$DATA_DIR/battery/state"

mkdir -p "$CONFDIR" "$CACHE" "$STATE" "$LOGDIR" "$BT_STATE" 2>/dev/null

log() { echo "$(date '+%F %T') [engine] $*" >> "$LOGDIR/engine.log" 2>/dev/null; }

mod_version() {
  grep -m1 '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2 | tr -d '\r'
}

# ---------- 首次 seed：配置从模块内模板复制（settings.conf 模板含机型 dev_code） ----------
seed_settings() {
  [ -s "$CONFDIR/settings.json" ] && return 0
  mkdir -p "$CONFDIR" 2>/dev/null
  cp -f "$MODDIR/default_settings.json" "$CONFDIR/settings.json" 2>/dev/null \
    || printf '{}\n' > "$CONFDIR/settings.json"
}

seed_bt_conf() {
  [ -s "$BT_CONF" ] && return 0
  mkdir -p "$(dirname "$BT_CONF")" 2>/dev/null
  cp -f "$MODDIR/battery/config/settings.conf" "$BT_CONF" 2>/dev/null
}
seed_settings
seed_bt_conf

json_get() {
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
  _f="$CONFDIR/settings.json"; _k="$1"; _v="$2"; _t="${3:-str}"
  seed_settings
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
  ' "$_f" > "$_f.new" 2>/dev/null && cat "$_f.new" > "$_f" && rm -f "$_f.new"
}

# ---------- settings.conf（电池/解容配置，KEY=value） ----------
cfg_get() {
  _k="$1"; _d="${2:-}"
  [ -s "$BT_CONF" ] || { printf '%s' "$_d"; return 0; }
  _v=$(grep -aE "^${_k}=" "$BT_CONF" 2>/dev/null | tail -n1 | cut -d= -f2-)
  [ -n "$_v" ] && printf '%s' "$_v" || printf '%s' "$_d"
}

cfg_set() {
  _k="$1"; _v="$2"
  mkdir -p "$(dirname "$BT_CONF")" 2>/dev/null
  if grep -aqE "^${_k}=" "$BT_CONF" 2>/dev/null; then
    sed -i "s|^${_k}=.*|${_k}=${_v}|" "$BT_CONF" 2>/dev/null
  else
    printf '%s=%s\n' "$_k" "$_v" >> "$BT_CONF"
  fi
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
  awk -F'\t' '
    {
      if (NR > 1) printf ","
      v = $2
      if ($3 != "num") { gsub(/\\/, "\\\\", v); gsub(/"/, "\\\"", v); v = "\"" v "\"" }
      printf "\"%s\":%s", $1, v
    }
    END { printf "\n" }
  ' "$1" | sed 's/^/{/; s/$/}/'
}
