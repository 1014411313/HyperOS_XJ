#!/system/bin/sh
# engine/lib/rear.sh — 自研背屏电池卡片（LSPosed overlay）。
# 模块 hook com.xiaomi.subscreencenter.SubScreenLauncher.onResume，在原生时钟上
# 叠加 PanelView；不禁用 SubScreenLauncher，也不推 Display #1 任务。

RS_APK="$MODDIR/app/HyperOS_XJ.apk"
RS_LOG="$LOGDIR/rearscreen.log"
RS_STATE="$STATE/rear"
CARD_PKG=com.hyperos.xj
CARD_SERVICE="$CARD_PKG/com.hyperos.xj.rear.RearService"
CARD_RECEIVER="$CARD_PKG/com.hyperos.xj.rear.ControlReceiver"
CARD_CONF_DIR="/storage/emulated/0/Android/data/com.hyperos.xj/files"
CARD_CONF="$CARD_CONF_DIR/rear.conf"
SUB_PKG=com.xiaomi.subscreencenter

mkdir -p "$RS_STATE" 2>/dev/null

say() { echo "$(date '+%F %T') $*" >> "$RS_LOG" 2>/dev/null; }

has_subscreen() {
  pm path "$SUB_PKG" >/dev/null 2>&1 && return 0
  dumpsys display 2>/dev/null | grep -q 'Display #1'
}

apk_ok() { pm path "$CARD_PKG" >/dev/null 2>&1; }

card_uid() {
  _u=$(dumpsys package "$CARD_PKG" 2>/dev/null | grep -m1 'userId=' | sed 's/.*userId=//')
  case "$_u" in ''|*[!0-9]*) _u=0 ;; esac
  echo "$_u"
}

# 找到原生背屏时钟组件，仅用于清理旧版本遗留的 pm disable。
sub_launcher_component() {
  _c=$(dumpsys package "$SUB_PKG" 2>/dev/null | grep -i 'SubScreenLauncher' \
    | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.]*SubScreenLauncher[A-Za-z0-9_.]*' | head -1)
  [ -n "$_c" ] && { echo "$_c"; return 0; }
  echo "$SUB_PKG/.SubScreenLauncher"
}

enable_clock() {
  [ -f "$RS_STATE/clock_disabled" ] || return 0
  _c=$(cat "$RS_STATE/clock_disabled" 2>/dev/null)
  [ -n "$_c" ] || return 0
  pm enable --user 0 "$_c" >/dev/null 2>&1
  rm -f "$RS_STATE/clock_disabled" 2>/dev/null
  say "native clock restored: $_c"
}

# 开机恢复：清掉旧版（禁时钟+推任务）可能留下的持久禁用状态。
rear_boot_recover() {
  _c=$(cat "$RS_STATE/clock_disabled" 2>/dev/null)
  [ -n "$_c" ] || _c=$(sub_launcher_component)
  [ -n "$_c" ] && pm enable --user 0 "$_c" >/dev/null 2>&1
  rm -f "$RS_STATE/clock_disabled" "$RS_STATE/on" "$RS_STATE/push_fail" 2>/dev/null
  say "boot recover: native clock restored ($_c)"
}

ensure_data_dir() {
  [ -d "$CARD_CONF_DIR" ] && return 0
  # 让 App 自己创建外部目录，FUSE 属主才正确。
  am start-foreground-service -n "$CARD_SERVICE" >/dev/null 2>&1
  _i=0
  while [ "$_i" -lt 5 ]; do
    [ -d "$CARD_CONF_DIR" ] && return 0
    sleep 1
    _i=$((_i + 1))
  done
  mkdir -p "$CARD_CONF_DIR" 2>/dev/null
  _uid=$(card_uid)
  [ "$_uid" != "0" ] && chown "$_uid:$_uid" "$CARD_CONF_DIR" 2>/dev/null
  chmod 0771 "$CARD_CONF_DIR" 2>/dev/null
  [ -d "$CARD_CONF_DIR" ]
}

write_config() {
  _enabled="$1" _keep="$2"
  [ -d "$CARD_CONF_DIR" ] || return 1
  printf 'enabled=%s\nkeep_on=%s\naccent=#38BDF8\nfont_scale=1.0\n' \
    "$_enabled" "$_keep" > "$CARD_CONF.tmp" 2>/dev/null
  mv -f "$CARD_CONF.tmp" "$CARD_CONF" 2>/dev/null
  printf 'enabled=%s\nkeep_on=%s\naccent=#38BDF8\nfont_scale=1.0\n' \
    "$_enabled" "$_keep" > /data/local/tmp/xj_rear.conf 2>/dev/null
  chmod 0644 /data/local/tmp/xj_rear.conf 2>/dev/null
  _uid=$(card_uid)
  [ "$_uid" != "0" ] && chown "$_uid:$_uid" "$CARD_CONF" 2>/dev/null
  chmod 0660 "$CARD_CONF" 2>/dev/null
  [ -s "$CARD_CONF" ]
}

send_control() {
  _e=false; [ "$1" = "1" ] && _e=true
  _k=false; [ "$2" = "1" ] && _k=true
  am broadcast -a com.hyperos.xj.rear.CONTROL -n "$CARD_RECEIVER" \
    --ez enabled "$_e" --ez keep_on "$_k" >/dev/null 2>&1
}

rear_ensure() {
  has_subscreen || return 1
  if ! apk_ok; then
    pm install -r "$RS_APK" >/dev/null 2>&1
    apk_ok || { say "install failed"; return 1; }
    say "RearScreen APK installed"
  fi
  ensure_data_dir || { say "data dir unavailable"; return 1; }
  write_config 1 1 || true
  send_control 1 1
  enable_clock
  say "lsposed overlay ready"
  return 0
}

rear_stop() {
  write_config 0 0 || true
  send_control 0 0
  rm -f "$RS_STATE/on" "$RS_STATE/retry" 2>/dev/null
  enable_clock
  say "lsposed overlay disabled"
}

rear_tick() {
  [ "$(cfg_get rearscreen 0)" = "1" ] || {
    [ -f "$RS_STATE/on" ] && rear_stop
    rm -f "$RS_STATE/on" 2>/dev/null
    return 0
  }

  if [ ! -f "$RS_STATE/on" ]; then
    if [ -f "$RS_STATE/retry" ]; then
      _t=$(cat "$RS_STATE/retry" 2>/dev/null)
      case "$_t" in ''|*[!0-9]*) _t=0 ;; esac
      [ $(( $(date +%s) - _t )) -lt 60 ] && return 0
    fi
    if rear_ensure; then
      : > "$RS_STATE/on" 2>/dev/null
      rm -f "$RS_STATE/retry" 2>/dev/null
    else
      date +%s > "$RS_STATE/retry" 2>/dev/null
      return 0
    fi
  fi

  _n=$(cat "$RS_STATE/tick" 2>/dev/null); [ -n "$_n" ] || _n=0
  _n=$((_n + 1))
  echo "$_n" > "$RS_STATE/tick" 2>/dev/null
  if [ $((_n % 30)) -eq 0 ]; then
    grep -q '^enabled=1' "$CARD_CONF" 2>/dev/null || write_config 1 1
    am start-foreground-service -n "$CARD_SERVICE" >/dev/null 2>&1
  fi
}

rear_status() {
  echo "rearscreen=$(cfg_get rearscreen 0)"
  echo "device=$(has_subscreen && echo 1 || echo 0)"
  echo "apk=$(apk_ok && echo 1 || echo 0)"
  [ -f "$RS_STATE/on" ] && echo "on=1" || echo "on=0"
  echo "mode=lsposed"
  echo "conf=$(cat "$CARD_CONF" 2>/dev/null | tr '\n' ' ')"
  echo "service=$(dumpsys activity services "$CARD_PKG" 2>/dev/null | grep -c "$CARD_PKG")"
}
