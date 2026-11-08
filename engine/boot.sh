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
# （bug-056：/proc/<旧pid> 指向 system_server，导致误判 daemon 存活而永不拉起）。
daemon_pid() {
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -f 'xjdaemon\.sh' 2>/dev/null | head -n 1
    return
  fi
  # 兜底扫 /proc cmdline：无 pgrep 时不可依赖 ps（各实现输出列名不一，procps 只显示进程名）
  for _d in /proc/[0-9]*; do
    [ -r "$_d/cmdline" ] || continue
    case "$(tr '\0' ' ' < "$_d/cmdline" 2>/dev/null)" in
      *xjdaemon.sh*) echo "${_d#/proc/}" && return ;;
    esac
  done
}

daemon_alive() {
  [ -n "$(daemon_pid)" ]
}

start_daemon() {
  daemon_alive && return 0
  setsid sh "$DIR/xjdaemon.sh" >> "$LOGDIR/daemon.log" 2>&1 &
  sleep 1
  _p=$(daemon_pid)
  if [ -n "$_p" ]; then
    echo "$_p" > "$PIDFILE"
    log "xjdaemon started pid=$_p"
  else
    rm -f "$PIDFILE" 2>/dev/null
    log "xjdaemon started, real pid unknown (pidfile cleared)"
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
