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

# 存活判定只认真实进程，不认 pidfile：旧 pidfile 的 pid 重启后可能被系统进程复用

# 存活判定：读 pidfile + kill -0 + comm 校验。
# 不遍历 /proc 读 cmdline——读 cmdline 要访问目标进程内存，目标正在退出时该读会阻塞，
# 曾把 tr 卡在 R 状态吃满一个核（bug-121）；comm 读的是内核结构，不会卡。
daemon_alive() {
  _p=$(cat "$PIDFILE" 2>/dev/null)
  case "$_p" in ''|*[!0-9]*) return 1 ;; esac
  kill -0 "$_p" 2>/dev/null || return 1
  [ "$(cat "/proc/$_p/comm" 2>/dev/null)" = "sh" ] || return 1
  return 0
}

# 旧模块 id 的残留进程：只在拉起 daemon 前清一次，且整个扫描套 timeout。
# cmdline 读取可能被正在退出的进程卡住，不能让看护循环陪着一起死。
kill_stale_daemons() {
  timeout 5 sh -c '
    DIR="'"$DIR"'"
    for d in /proc/[0-9]*; do
      [ -r "$d/cmdline" ] || continue
      cl=$(tr "\0" " " < "$d/cmdline" 2>/dev/null) || continue
      case "$cl" in
        *xjdaemon.sh*) ;;
        *) continue ;;
      esac
      case "$cl" in
        *"$DIR/xjdaemon.sh"*) continue ;;
      esac
      kill "${d#/proc/}" 2>/dev/null && echo "killed ${d#/proc/}"
    done
  ' 2>/dev/null | while read -r _l; do log "stale daemon: $_l"; done
}

start_daemon() {
  kill_stale_daemons
  daemon_alive && return 0
  rm -f "$PIDFILE" 2>/dev/null
  setsid sh "$DIR/xjdaemon.sh" >> "$LOGDIR/daemon.log" 2>&1 < /dev/null &
  sleep 2
  # pid 由 daemon 自己写进 pidfile（setsid 可能 fork，$! 不可靠）
  if daemon_alive; then
    log "xjdaemon started pid=$(cat "$PIDFILE" 2>/dev/null)"
  else
    rm -f "$PIDFILE" 2>/dev/null
    log "xjdaemon started, pidfile 未就绪"
  fi
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
  daemon_alive || { log "daemon lost, restarting"; start_daemon; }
done
