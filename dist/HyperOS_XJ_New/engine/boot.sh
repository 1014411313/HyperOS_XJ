#!/system/bin/sh
# engine/boot.sh — 开机服务入口：拉起唯一常驻进程 xjdaemon，并做掉线看护。

DIR=$(cd "$(dirname "$0")" && pwd)
. "$DIR/lib/env.sh"

mkdir -p "$STATE" 2>/dev/null
PIDFILE="$STATE/daemon.pid"

wait_boot() {
  _i=0
  while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    _i=$((_i + 1))
    [ "$_i" -ge 180 ] && break
    sleep 1
  done
}

start_daemon() {
  [ -f "$PIDFILE" ] && _p=$(cat "$PIDFILE" 2>/dev/null) && [ -d "/proc/$_p" ] && return 0
  setsid sh "$DIR/xjdaemon.sh" >> "$LOGDIR/daemon.log" 2>&1 &
  echo $! > "$PIDFILE"
  log "xjdaemon started pid=$(cat "$PIDFILE" 2>/dev/null)"
}

case "$1" in
  --now)
    start_daemon
    exit 0
    ;;
  --once)
    sh "$DIR/xjdaemon.sh" --once
    exit 0
    ;;
esac

wait_boot
log "boot service start (waited)"

# 配置与数据缓存就绪
if [ ! -s "$CONFDIR/settings.json" ] && [ -s "$MODDIR/default_settings.json" ]; then
  cp -f "$MODDIR/default_settings.json" "$CONFDIR/settings.json" 2>/dev/null
fi
sh "$DIR/xjctl.sh" detect >/dev/null 2>&1

start_daemon

while true; do
  sleep 60
  [ -f "$MODDIR/disable" ] && continue
  _p=$(cat "$PIDFILE" 2>/dev/null)
  case "$_p" in ''|*[!0-9]*) start_daemon; continue ;; esac
  [ -d "/proc/$_p" ] || { log "daemon lost, restarting"; start_daemon; }
done
