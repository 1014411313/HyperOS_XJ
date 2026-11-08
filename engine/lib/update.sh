#!/system/bin/sh
# engine/lib/update.sh — 检查更新。被 xjctl.sh 在 `update` 分支内 source。
# 拉取 <update_base>/latest.json（形如 {"version":"3.1.2","versionCode":312,"url":"..."}）
# 与本地 module.prop 的 versionCode 比较。下载源 = battery/config/settings.conf 的 update_base。

update_check() {
  # 键缺失时用内置默认（老用户配置里可能没有 update_base）
  _base=$(cfg_get update_base "https://raw.githubusercontent.com/1014411313/HyperOS_XJ/master")
  [ -n "$_base" ] || { echo "未配置更新源（settings.conf 的 update_base）"; return 1; }
  command -v curl >/dev/null 2>&1 || { echo "本机无 curl，无法检查更新"; return 1; }
  _url="${_base%/}/latest.json"
  # 私有仓库：可配 github_token（存设备本地配置，不随模块分发）
  _tok=$(cfg_get github_token "")
  if [ -n "$_tok" ]; then
    _json=$(curl -fsSL -H "Authorization: token $_tok" --connect-timeout 15 --max-time 60 "$_url" 2>/dev/null)
  else
    _json=$(curl -fsSL --connect-timeout 15 --max-time 60 "$_url" 2>/dev/null)
  fi
  [ -n "$_json" ] || { echo "检查失败：无法获取更新信息（$_url）"; return 1; }
  _new=$(printf '%s' "$_json" | tr ',' '\n' \
    | sed -n 's/.*"versionCode"[[:space:]]*:[[:space:]]*\([0-9]\{1,\}\).*/\1/p' | head -n1)
  _name=$(printf '%s' "$_json" | tr ',' '\n' \
    | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)
  _cur=$(grep -m1 '^versionCode=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2 | tr -d '\r')
  case "$_cur" in ''|*[!0-9]*) _cur=0 ;; esac
  case "$_new" in ''|*[!0-9]*) echo "更新信息格式异常"; return 1 ;; esac
  if [ "$_new" -gt "$_cur" ]; then
    echo "发现新版本 ${_name:-代码$_new}（本地 $_cur），请到发布页下载"
  else
    echo "已是最新（本地 $_cur，云端 $_new）"
  fi
}
