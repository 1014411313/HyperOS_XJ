#!/system/bin/sh
# engine/lib/update.sh — 检查更新。被 xjctl.sh 在 `update` 分支内 source。
# 依次尝试多个更新源（国内网络下 GitHub raw 常不可达）：jsDelivr → Gitee 镜像 → GitHub raw。
# 每个源拉取 <base>/latest.json（{"version":"3.1.19","versionCode":329,...}），首个成功的即用。
# settings.conf 里配了 update_base 时只用该源（自建镜像）。

update_default_sources() {
  printf '%s\n' \
    "https://cdn.jsdelivr.net/gh/1014411313/HyperOS_XJ@master" \
    "https://gitee.com/xiaomirezhale/HyperOS_XJ/raw/master" \
    "https://raw.githubusercontent.com/1014411313/HyperOS_XJ/master"
}

update_fetch() {
  if [ -n "$2" ]; then
    curl -fsSL -H "Authorization: token $2" --connect-timeout 5 --max-time 12 "$1" 2>/dev/null
  else
    curl -fsSL --connect-timeout 5 --max-time 12 "$1" 2>/dev/null
  fi
}

update_check() {
  command -v curl >/dev/null 2>&1 || { echo "本机无 curl，无法检查更新"; return 1; }
  _tok=$(cfg_get github_token "")
  _custom=$(cfg_get update_base "")
  _json=""
  if [ -n "$_custom" ]; then
    _json=$(update_fetch "${_custom%/}/latest.json" "$_tok")
  else
    for _base in $(update_default_sources); do
      _json=$(update_fetch "${_base%/}/latest.json" "$_tok")
      [ -n "$_json" ] && break
    done
  fi
  if [ -z "$_json" ]; then
    echo "检查失败：更新源均不可达（jsDelivr / Gitee / GitHub）。请检查网络后重试，或到发布页手动下载最新模块"
    return 1
  fi
  _new=$(printf '%s' "$_json" | tr ',' '\n' \
    | sed -n 's/.*"versionCode"[[:space:]]*:[[:space:]]*\([0-9]\{1,\}\).*/\1/p' | head -n1)
  _name=$(printf '%s' "$_json" | tr ',' '\n' \
    | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)
  _cur=$(grep -m1 '^versionCode=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2 | tr -d '\r')
  _curname=$(grep -m1 '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2 | tr -d '\r')
  case "$_cur" in ''|*[!0-9]*) _cur=0 ;; esac
  case "$_new" in ''|*[!0-9]*) echo "更新信息格式异常"; return 1 ;; esac
  if [ "$_new" -gt "$_cur" ]; then
    echo "发现新版本 ${_name:-?}（当前 ${_curname:-?}），请到发布页下载"
  else
    echo "已是最新（${_curname:-$_cur}）"
  fi
}
