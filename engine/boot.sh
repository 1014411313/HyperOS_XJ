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

# 存活判定：pidfile 优先，缺失时回退到 daemon 锁目录里的 pid（两者都由 daemon 写出）。
daemon_alive() {
  _p=$(cat "$PIDFILE" 2>/dev/null)
  case "$_p" in ''|*[!0-9]*) _p=$(cat "$STATE/daemon.lock.d/pid" 2>/dev/null) ;; esac
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
  # daemon 先跑 bootstrap/factory_backup/data_detect 再写 pidfile，可能超过几秒；
  # 这里轮询等待，且不能把等待期间才写好的 pidfile 删掉——那会让看护永远误判 lost。
  _w=0
  while [ "$_w" -lt 15 ]; do
    sleep 1
    _w=$((_w + 1))
    daemon_alive && { log "xjdaemon started pid=$(cat "$PIDFILE" 2>/dev/null)"; return 0; }
  done
  log "xjdaemon started, pidfile 未就绪（${_w}s 内未见）"
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
