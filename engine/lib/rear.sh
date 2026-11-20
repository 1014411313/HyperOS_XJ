#!/system/bin/sh
# engine/lib/rear.sh — 自研背屏电池卡片（LSPosed overlay）。
# 模块 hook com.xiaomi.subscreencenter.SubScreenLauncher.onResume，在原生时钟上
# 叠加 PanelView；不禁用 SubScreenLauncher，也不推 Display #1 任务。

RS_APK="$MODDIR/app/rezhale_toolbox.apk"
RS_LOG="$LOGDIR/rearscreen.log"
RS_STATE="$STATE/rear"
CARD_PKG=com.hyperos.xj
CARD_SERVICE="$CARD_PKG/com.hyperos.xj.rear.RearService"
CARD_RECEIVER="$CARD_PKG/com.hyperos.xj.rear.ControlReceiver"
CARD_CONF_DIR="/storage/emulated/0/Android/data/com.hyperos.xj/files"
CARD_CONF="$CARD_CONF_DIR/rear.conf"
# hook 侧读取的兜底配置（外部目录不可用时用）。
RS_HOOK_CONF=/data/local/tmp/xj_rear.conf
SUB_PKG=com.xiaomi.subscreencenter
# 支持判据缓存：测温/状态/守护共用一份，避免每拍 fork pm。
RS_SUP="$RS_STATE/supported"
RS_SUP_TS="$RS_STATE/supported.ts"
# 关闭/无支持且收尾完成后置静默标记，60 秒内不再 grep 配置；开启态健康维护按真实时间节流。
RS_IDLE="$RS_STATE/idle"
RS_IDLE_TS="$RS_STATE/idle.ts"
RS_HEALTH_TS="$RS_STATE/health.ts"

[ "$XJ_READONLY" = "1" ] || mkdir -p "$RS_STATE" 2>/dev/null

say() { echo "$(date '+%F %T') $*" >> "$RS_LOG" 2>/dev/null; }

# 调度时钟：守护循环可注入 XJ_NOW（单调秒，避免系统时间跳变影响窗口）；
# 独立命令/自检未注入时回退 date epoch。
_now_sec() {
  case "${XJ_NOW:-}" in
    ''|*[!0-9]*) date +%s ;;
    *) printf '%s' "$XJ_NOW" ;;
  esac
}

# 时间窗口判断（$1=now $2=last $3=ttl）：last 缺失、时钟倒退或超出 ttl 都返回非 0。
_within() {
  case "${2:-}" in ''|*[!0-9]*) return 1 ;; esac
  [ "$1" -lt "$2" ] && return 1
  [ $(( $1 - $2 )) -lt "$3" ]
}

# 唯一判据：com.xiaomi.subscreencenter 在装。dumpsys 的 Display #1 可能来自外接屏/
# 投屏，不能当背屏硬件依据；也不看屏幕亮灭（熄屏时照样有硬件）。
has_subscreen() {
  pm path "$SUB_PKG" >/dev/null 2>&1
}

# 现探一次并写缓存：用户点开关时用，不吃缓存里的旧值。
subscreen_probe() {
  if has_subscreen; then _rsp=1; else _rsp=0; fi
  if [ "$XJ_READONLY" != "1" ]; then
    mkdir -p "$RS_STATE" 2>/dev/null
    printf '%s' "$_rsp" > "$RS_SUP" 2>/dev/null
    printf '%s' "$(_now_sec)" > "$RS_SUP_TS" 2>/dev/null
  fi
  printf '%s' "$_rsp"
}

# 无背屏是常态：0 只缓存 60 秒，既省掉每拍 pm，又能在装机后自动重探；
# 1 缓存 600 秒，装机/卸载由 subscreen_probe 立即刷新。
subscreen_supported_cached() {
  _rsv=$(cat "$RS_SUP" 2>/dev/null)
  case "$_rsv" in 1) _rsttl=600 ;; 0) _rsttl=60 ;; *) _rsv=""; _rsttl=0 ;; esac
  _rsnow=$(_now_sec); _rslast=$(cat "$RS_SUP_TS" 2>/dev/null)
  case "$_rslast" in ''|*[!0-9]*) _rslast=0 ;; esac
  if [ -z "$_rsv" ] || ! _within "$_rsnow" "$_rslast" "$_rsttl"; then
    subscreen_probe
    return 0
  fi
  printf '%s' "$_rsv"
}

apk_ok() { pm path "$CARD_PKG" >/dev/null 2>&1; }

# App uid 的可靠来源：优先 App 数据目录属主（存存即权威，免 fork dumpsys）；
# 退 dumpsys 的 userId=/appId=（Android 17 起可能只给 appId=）。
# 多用户下 uid = userId*100000 + appId，合法 appId 区间 10000–19999；
# 只校验 appId 部分（uid % 100000），不猜、不用非应用 uid。
card_uid() {
  _u=""
  for _d in "/data/data/$CARD_PKG" "/data/user/0/$CARD_PKG"; do
    _v=$(stat -c %u "$_d" 2>/dev/null)
    case "$_v" in
      ''|*[!0-9]*) ;;
      *) _u=$_v; break ;;
    esac
  done
  if [ -z "$_u" ]; then
    _dump=$(dumpsys package "$CARD_PKG" 2>/dev/null)
    _u=$(printf '%s\n' "$_dump" | grep -m1 -o 'userId=[0-9]*' | cut -d= -f2)
    [ -n "$_u" ] || _u=$(printf '%s\n' "$_dump" | grep -m1 -o 'appId=[0-9]*' | cut -d= -f2)
  fi
  case "$_u" in ''|*[!0-9]*) _u=0 ;; esac
  _app=$(( _u % 100000 ))
  if [ "$_u" -le 0 ] || [ "$_app" -lt 10000 ] || [ "$_app" -gt 19999 ]; then _u=0; fi
  echo "$_u"
}

# 静默标记只记「上次已核验」的时间；只读模式不写状态。
_set_idle() {
  [ "$XJ_READONLY" = "1" ] && return 0
  : > "$RS_IDLE" 2>/dev/null
  printf '%s' "${1:-$(_now_sec)}" > "$RS_IDLE_TS" 2>/dev/null
}

_clear_idle() {
  [ -e "$RS_IDLE" ] && rm -f "$RS_IDLE" "$RS_IDLE_TS" 2>/dev/null
}

enable_clock() {
  [ -f "$RS_STATE/clock_disabled" ] || return 0
  _c=$(cat "$RS_STATE/clock_disabled" 2>/dev/null)
  [ -n "$_c" ] || return 0
  pm enable --user 0 "$_c" >/dev/null 2>&1
  rm -f "$RS_STATE/clock_disabled" 2>/dev/null
  say "native clock restored: $_c"
}

# 开机恢复：只清旧版（禁时钟+推任务）真实留下的残留：有 clock_disabled 才 pm enable，
# 有旧开态才收尾；不无条件动包管理器，也不写伪成功日志。
rear_boot_recover() {
  rear_cleanup_pending && rear_cleanup_once
  rear_cleanup_pending || rm -f "$RS_STATE/on" "$RS_STATE/retry" "$RS_STATE/push_fail" 2>/dev/null
  enable_clock
  ! rear_cleanup_pending && _set_idle
  return 0
}

ensure_data_dir() {
  [ "$(subscreen_supported_cached)" = "1" ] || return 1
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
  # 仅刷新展示配置时（参数为空）沿用现值，主副本不可用时读 hook 兜底副本。
  if [ -z "$_enabled" ] || [ -z "$_keep" ]; then
    _cur=$(cat "$CARD_CONF" 2>/dev/null)
    [ -n "$_cur" ] || _cur=$(cat "$RS_HOOK_CONF" 2>/dev/null)
    [ -z "$_enabled" ] && _enabled=$(printf '%s\n' "$_cur" | grep -a '^enabled=' | tail -n1 | cut -d= -f2)
    [ -z "$_keep" ] && _keep=$(printf '%s\n' "$_cur" | grep -a '^keep_on=' | tail -n1 | cut -d= -f2)
    case "$_enabled" in 0|1) ;; *) _enabled=$(cfg_get rearscreen 0) ;; esac
    case "$_keep" in 0|1) ;; *) _keep=1 ;; esac
  fi
  _fields=$(cfg_get rear_fields "temp,soc,volt,cur")
  _accent=$(cfg_get rear_accent "#38BDF8")
  _font=$(cfg_get rear_font_scale "1.0")
  _bg=$(cfg_get rear_bg "")
  # 未设背景时落到内置默认动图（service.sh 开机铺到 /data/local/tmp，与用户自选图分文件）。
  [ -n "$_bg" ] || _bg="image:/data/local/tmp/xj_rear_bg_default.gif"
  _body=$(printf 'enabled=%s\nkeep_on=%s\naccent=%s\nfont_scale=%s\nfields=%s' \
    "$_enabled" "$_keep" "$_accent" "$_font" "$_fields")
  [ -n "$_bg" ] && _body="$_body
bg=$_bg"
  if [ -d "$CARD_CONF_DIR" ]; then
    printf '%s\n' "$_body" > "$CARD_CONF.tmp" 2>/dev/null
    mv -f "$CARD_CONF.tmp" "$CARD_CONF" 2>/dev/null
    _uid=$(card_uid)
    [ "$_uid" != "0" ] && chown "$_uid:$_uid" "$CARD_CONF" 2>/dev/null
    chmod 0660 "$CARD_CONF" 2>/dev/null
  fi
  # hook 兜底副本：App 无支持 stop 时不会自己写配置，壳必须把已有的改成 enabled=0；
  # 两份都不存在时不创建（无背屏设备不产生新配置）。
  if [ -d "$CARD_CONF_DIR" ] || [ -s "$RS_HOOK_CONF" ]; then
    printf '%s\n' "$_body" > "$RS_HOOK_CONF" 2>/dev/null
    chmod 0644 "$RS_HOOK_CONF" 2>/dev/null
  fi
  [ -s "$CARD_CONF" ] || [ -s "$RS_HOOK_CONF" ]
}

send_control() {
  _e=false; [ "$1" = "1" ] && _e=true
  _k=false; [ "$2" = "1" ] && _k=true
  am broadcast -a com.hyperos.xj.rear.CONTROL -n "$CARD_RECEIVER" \
    --ez enabled "$_e" --ez keep_on "$_k" \
    --es fields "$(cfg_get rear_fields "temp,soc,volt,cur")" \
    --es accent "$(cfg_get rear_accent "#38BDF8")" \
    --es bg "$(cfg_get rear_bg "image:/data/local/tmp/xj_rear_bg_default.gif")" \
    --ef font_scale "$(cfg_get rear_font_scale "1.0")" >/dev/null 2>&1
}

# rear set 之后刷新：沿用当前开关状态重写 rear.conf 并广播新展示配置。
rear_push_config() {
  ensure_data_dir || return 1
  write_config "" "" || return 1
  _en=$(grep -a '^enabled=' "$CARD_CONF" 2>/dev/null | tail -n1 | cut -d= -f2)
  _kp=$(grep -a '^keep_on=' "$CARD_CONF" 2>/dev/null | tail -n1 | cut -d= -f2)
  send_control "${_en:-0}" "${_kp:-1}"
}

rear_ensure() {
  [ "$(subscreen_probe)" = "1" ] || return 1
  if ! apk_ok; then
    pm install -r "$RS_APK" >/dev/null 2>&1
    apk_ok || { say "install failed"; return 1; }
    say "RearScreen APK installed"
  fi
  # 先广播开态：已验证宿主上的 ControlReceiver 会写 Prefs.enabled=true 并 sync/建外部
  # 目录；先拉 FGS 会被「Prefs 未开」的 Java 侧早停，目录白白等不到。
  send_control 1 1
  ensure_data_dir || { say "data dir unavailable"; return 1; }
  write_config 1 1 || { say "config write failed"; return 1; }
  enable_clock
  rm -f "$RS_STATE/off" "$RS_STATE/unsupported" 2>/dev/null
  say "lsposed overlay ready"
  return 0
}

# xjctl rear on 入口：先验硬件再写配置，避免无背屏设备先落 rearscreen=1 再失败。
rear_enable() {
  if [ "$(subscreen_probe)" != "1" ]; then
    echo "该机型无背屏组件（$SUB_PKG），不支持背屏卡片"
    return 4
  fi
  cfg_set rearscreen 1
  _clear_idle
  rear_ensure
}

# 主副本或 hook 兜底副本是否仍为 enabled=1（App 无支持 stop 时不写配置，靠壳清）。
rear_conf_enabled() {
  grep -q '^enabled=1' "$CARD_CONF" 2>/dev/null && return 0
  grep -q '^enabled=1' "$RS_HOOK_CONF" 2>/dev/null
}

# 是否还有活动开态：on 标记或任一配置副本仍 enabled=1。
rear_active_leftover() {
  [ -f "$RS_STATE/on" ] && return 0
  rear_conf_enabled
}

# 幂等关闭：已关且无残留（on 标记 / 配置 enabled=1）时静默返回，重复 off 不再广播/写日志。
rear_stop() {
  _sup=$(subscreen_supported_cached)
  _on=0; [ -f "$RS_STATE/on" ] && _on=1
  _conf=0; rear_conf_enabled && _conf=1
  if [ -f "$RS_STATE/off" ] && [ "$_on" = "0" ] && [ "$_conf" = "0" ]; then
    return 0
  fi
  _acted=0; _fail=0
  if [ "$_sup" = "1" ] || [ "$_conf" = "1" ]; then
    write_config 0 0 || _fail=1
    _acted=1
  fi
  # 无背屏且没旧配置时只收 on 标记，不广播 off。
  [ "$_on" = "1" ] && _acted=1
  if [ "$_acted" = "1" ]; then
    send_control 0 0 || _fail=1
  fi
  [ "$_on" = "1" ] && am stopservice -n "$CARD_SERVICE" >/dev/null 2>&1
  rm -f "$RS_STATE/on" "$RS_STATE/retry" 2>/dev/null
  : > "$RS_STATE/off" 2>/dev/null
  enable_clock
  [ "$_acted" = "1" ] && say "lsposed overlay disabled"
  [ "$_fail" = "1" ] && return 1
  _set_idle
  return 0
}

# 还有待收尾：残留开态，或上次收尾失败（配置写/广播失败）未补。
rear_cleanup_pending() {
  [ -f "$RS_STATE/cleanup_retry" ] && return 0
  rear_active_leftover
}

# 一次收尾：失败保留残留与 retry 标记，60 秒内不重试，避每拍广播。
rear_cleanup_once() {
  if [ -f "$RS_STATE/cleanup_retry" ]; then
    _t=$(cat "$RS_STATE/cleanup_retry" 2>/dev/null)
    case "$_t" in ''|*[!0-9]*) _t=0 ;; esac
    _within "$(_now_sec)" "$_t" 60 && return 2
    _first=0
  else
    _first=1
  fi
  _ok=1
  if rear_active_leftover; then
    rear_stop || _ok=0
  else
    # 上次广播失败但配置已写回：补一次 off 下发。
    send_control 0 0 || _ok=0
  fi
  rear_active_leftover && _ok=0
  if [ "$_ok" = "0" ]; then
    printf '%s' "$(_now_sec)" > "$RS_STATE/cleanup_retry" 2>/dev/null
    [ "$_first" = "1" ] && say "cleanup incomplete: retry in 60s"
    return 1
  fi
  rm -f "$RS_STATE/cleanup_retry" 2>/dev/null
  return 0
}

# 无背屏：确认没残留后才落 unsupported 标记，清理失败就留待 tick 重试，不丢失败。
rear_unsupported_cleanup() {
  [ -f "$RS_STATE/unsupported" ] && return 0
  if rear_cleanup_pending; then
    rear_cleanup_once
    rear_cleanup_pending && return 0
  fi
  : > "$RS_STATE/unsupported" 2>/dev/null
  say "subscreen not supported: overlay skipped"
}

rear_tick() {
  _now=$(_now_sec)
  _changed=0; [ "${XJ_CONFIG_CHANGED:-0}" = "1" ] && _changed=1
  # 关闭/无支持收尾完成后置静默标记，60 秒内不读配置；XJ_CONFIG_CHANGED=1
  # 只穿透本次快速返回，不删除静默标记，避免每轮归零丢状态。
  if [ "$_changed" = "0" ] && [ -f "$RS_IDLE" ]; then
    _last=$(cat "$RS_IDLE_TS" 2>/dev/null)
    _within "$_now" "$_last" 60 && return 0
  fi

  if [ "$(cfg_get rearscreen 0)" != "1" ]; then
    if rear_cleanup_pending; then
      rear_cleanup_once
    fi
    rm -f "$RS_STATE/on" 2>/dev/null
    # 收尾失败保留 cleanup_retry（60 秒窗口）继续重试，不置静默标记。
    if ! rear_cleanup_pending; then
      _set_idle "$_now"
    fi
    return 0
  fi
  _clear_idle

  if [ "$(subscreen_supported_cached)" != "1" ]; then
    rear_unsupported_cleanup
    ! rear_cleanup_pending && _set_idle "$_now"
    return 0
  fi
  rm -f "$RS_STATE/unsupported" 2>/dev/null

  if [ ! -f "$RS_STATE/on" ]; then
    if [ -f "$RS_STATE/retry" ]; then
      _t=$(cat "$RS_STATE/retry" 2>/dev/null)
      _within "$_now" "$_t" 60 && return 0
    fi
    if rear_ensure; then
      : > "$RS_STATE/on" 2>/dev/null
      rm -f "$RS_STATE/retry" 2>/dev/null
    else
      printf '%s' "$_now" > "$RS_STATE/retry" 2>/dev/null
      return 0
    fi
    _clear_idle
  fi

  # 开启态健康维护：真实 60 秒一次，不按拍数（守护 tick 可能 1 秒一拍）。
  _hlast=$(cat "$RS_HEALTH_TS" 2>/dev/null)
  if [ "$_changed" = "1" ] || ! _within "$_now" "$_hlast" 60; then
    [ "$XJ_READONLY" = "1" ] || printf '%s' "$_now" > "$RS_HEALTH_TS" 2>/dev/null
    # 参数变化（字/色/背景/开关）需要即时下发，不只补 enabled=1。
    if [ "$_changed" = "1" ]; then
      rear_push_config || write_config 1 1
    else
      grep -q '^enabled=1' "$CARD_CONF" 2>/dev/null || write_config 1 1
    fi
    am start-foreground-service -n "$CARD_SERVICE" >/dev/null 2>&1
  fi
}

rear_status() {
  echo "rearscreen=$(cfg_get rearscreen 0)"
  echo "device=$(subscreen_supported_cached)"
  echo "apk=$(apk_ok && echo 1 || echo 0)"
  [ -f "$RS_STATE/on" ] && echo "on=1" || echo "on=0"
  echo "mode=lsposed"
  echo "conf=$(cat "$CARD_CONF" 2>/dev/null | tr '\n' ' ')"
  echo "service=$(dumpsys activity services "$CARD_PKG" 2>/dev/null | grep -c "$CARD_PKG")"
}
