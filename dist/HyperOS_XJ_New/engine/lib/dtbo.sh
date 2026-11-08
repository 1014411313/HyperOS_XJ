#!/system/bin/sh
# engine/lib/dtbo.sh — DTBO 分区 状态/备份/刷入。
# 被 xjctl.sh 在 `dtbo` 分支内 source；依赖 env.sh 的 MODDIR/LOGDIR 与 battery.sh 的 dev_code。
# 安全原则：分区节点查不到就拒绝，绝不猜测；刷入前必须机型匹配 + 自动备份 + 回读校验。

DTBO_DIR="$MODDIR/battery/dtbo"
DTBO_BACKUP_DEFAULT="/sdcard/Download/dtbo_backup"

dtbo_need_root() {
  [ "$(id -u 2>/dev/null)" = "0" ] && return 0
  echo "需要 root 才能读写 DTBO 分区"
  return 1
}

# 解析当前 DTBO 分区设备节点：优先当前槽位 dtbo_a/dtbo_b，回退 dtbo。
# 查不到返回非 0（调用方必须按「无法确定分区」处理）。
dtbo_dev() {
  _suf=""
  _sl=$(getprop ro.boot.slot_suffix 2>/dev/null)
  case "$_sl" in
    _a|_b) _suf="$_sl" ;;
    *)
      _s=$(getprop ro.boot.slot 2>/dev/null)
      case "$_s" in a|b) _suf="_$_s" ;; esac ;;
  esac
  for _base in /dev/block/by-name /dev/block/bootdevice/by-name; do
    [ -d "$_base" ] || continue
    for _n in "dtbo$_suf" dtbo; do
      [ -e "$_base/$_n" ] && { printf '%s' "$_base/$_n"; return 0; }
    done
  done
  return 1
}

# 分区字节大小：blockdev -> sysfs -> 逐字节读。未知返回非 0。
dtbo_size() {
  _d="$1"
  _sz=$(blockdev --getsize64 "$_d" 2>/dev/null)
  case "$_sz" in ''|*[!0-9]*) ;; *) printf '%s' "$_sz"; return 0 ;; esac
  _real=$(readlink -f "$_d" 2>/dev/null); [ -n "$_real" ] || _real="$_d"
  _sec=$(cat "/sys/class/block/$(basename "$_real")/size" 2>/dev/null)
  case "$_sec" in ''|*[!0-9]*) ;; *) printf '%s' "$((_sec * 512))"; return 0 ;; esac
  _sz=$(wc -c < "$_d" 2>/dev/null | tr -d ' ')
  case "$_sz" in ''|*[!0-9]*) return 1 ;; *) printf '%s' "$_sz"; return 0 ;; esac
}

dtbo_human() {
  awk -v b="$1" 'BEGIN{ if (b>=1048576) printf "%.1fMB", b/1048576; else if (b>=1024) printf "%.1fKB", b/1024; else printf "%dB", b }'
}

dtbo_sha() { sha256sum "$1" 2>/dev/null | awk '{print $1}'; }

dtbo_status() {
  dtbo_need_root || return 1
  _dev=$(dtbo_dev) || {
    echo "DTBO 分区未找到"
    echo "已查找: /dev/block/by-name/dtbo[_a/_b]、/dev/block/bootdevice/by-name/dtbo[_a/_b]"
    echo "槽位: $(getprop ro.boot.slot_suffix 2>/dev/null)"
    echo "结论: 无法确定分区设备，已停止（不猜测节点）"
    return 1
  }
  _name=$(basename "$_dev")
  _sz=$(dtbo_size "$_dev")
  _szh=$(dtbo_human "${_sz:-0}")
  if [ -w "$_dev" ]; then _w="可写"; else _w="只读"; fi
  _dc=$(dev_code)
  _img="$DTBO_DIR/$_dc.img"
  if [ "$_dc" = "unknown" ]; then
    _match="不匹配(机型未知)"
  elif [ -f "$_img" ]; then
    _match="匹配"
  else
    _match="不匹配(无内置镜像)"
  fi
  echo "$_name $_szh $_w 镜像$_match"
  echo "分区节点: $_dev"
  echo "分区名  : $_name"
  if [ -n "$_sz" ]; then echo "分区大小: $_sz 字节 ($_szh)"; else echo "分区大小: 未知"; fi
  if [ -w "$_dev" ]; then echo "可写    : 是"; else echo "可写    : 否"; fi
  echo "槽位    : $(getprop ro.boot.slot_suffix 2>/dev/null)"
  echo "机型代号: $_dc"
  echo "内置镜像: $_img"
  if [ -f "$_img" ]; then
    _isz=$(stat -c %s "$_img" 2>/dev/null)
    echo "镜像大小: ${_isz:-?} 字节 ($(dtbo_human "${_isz:-0}"))"
    echo "镜像sha256: $(dtbo_sha "$_img")"
  fi
  echo "镜像匹配: $_match"
  [ "$_match" = "匹配" ] || echo "提示: 需在 $DTBO_DIR 下放置 <机型代号>.img 才能刷入"
  return 0
}

dtbo_backup() {
  dtbo_need_root || return 1
  _dir="${1:-$DTBO_BACKUP_DEFAULT}"
  _dev=$(dtbo_dev) || { echo "DTBO 分区未找到，无法备份"; return 2; }
  mkdir -p "$_dir" 2>/dev/null || { echo "备份目录不可用: $_dir"; return 1; }
  _name=$(basename "$_dev")
  _out="$_dir/${_name}_$(date +%Y%m%d-%H%M%S).img"
  echo "备份 $_dev -> $_out ..."
  if dd if="$_dev" of="$_out" bs=1M 2>/dev/null; then
    sync
    echo "备份完成: $_out"
    echo "大小: $(stat -c %s "$_out" 2>/dev/null) 字节"
    echo "sha256: $(dtbo_sha "$_out")"
    return 0
  fi
  echo "备份失败"; rm -f "$_out" 2>/dev/null; return 1
}

dtbo_flash() {
  _arg="$1"
  _skipname="${2:-}"
  [ -n "$_arg" ] || { echo "用法: xjctl.sh dtbo flash <镜像文件名>"; return 9; }
  dtbo_need_root || return 1

  case "$_arg" in
    */*) _img="$_arg" ;;
    *)   _img="$DTBO_DIR/$_arg" ;;
  esac
  [ -f "$_img" ] || { echo "已拒绝: 镜像文件不存在 -> $_img"; return 2; }
  _base=$(basename "$_img")

  # 机型匹配：除非显式跳过（模块内置 local.img），文件名必须是 <当前机型代号>.img，否则拒绝（exit 2）。
  _dc=$(dev_code)
  if [ "$_skipname" = "1" ]; then
    [ "$_dc" = "unknown" ] && echo "警告: 机型未知，按显式路径刷入（已跳过文件名校验）"
  else
    if [ "$_dc" = "unknown" ]; then
      echo "已拒绝: 无法识别当前机型代号，不敢刷入（镜像=$_base）"; return 2
    fi
    if [ "$_base" != "$_dc.img" ]; then
      echo "已拒绝: 镜像与机型不匹配（当前机型=$_dc，镜像=$_base，应为 $_dc.img）"; return 2
    fi
  fi

  _dev=$(dtbo_dev) || { echo "已拒绝: DTBO 分区未找到，不猜测设备节点"; return 2; }

  _fsz=$(stat -c %s "$_img" 2>/dev/null)
  case "$_fsz" in ''|*[!0-9]*) echo "已拒绝: 无法读取镜像大小"; return 2 ;; esac
  [ "$_fsz" -ge 1024 ] || { echo "已拒绝: 镜像过小（$_fsz 字节），疑似损坏"; return 2; }
  _dsz=$(dtbo_size "$_dev")
  case "$_dsz" in
    ''|*[!0-9]*) _dsz=""; echo "提示: 无法读取分区大小，跳过上限检查" ;;
    *) [ "$_fsz" -le "$_dsz" ] || { echo "已拒绝: 镜像($_fsz 字节) 大于分区($_dsz 字节)"; return 2; } ;;
  esac

  echo "机型: $_dc   镜像: $_base ($_fsz 字节)"
  echo "分区: $_dev ($(dtbo_human "${_dsz:-0}"))"

  # (c) 自动备份当前分区：备份失败即中止，绝不动分区。
  _bdir="$DTBO_BACKUP_DEFAULT"
  mkdir -p "$_bdir" 2>/dev/null || { echo "已拒绝: 备份目录不可建 ($_bdir)，不刷入"; return 1; }
  _bf="$_bdir/$(basename "$_dev")_before_$(date +%Y%m%d-%H%M%S).img"
  echo "自动备份 -> $_bf"
  if ! dd if="$_dev" of="$_bf" bs=1M 2>/dev/null; then
    echo "已中止: 备份失败，分区未被改动"; rm -f "$_bf" 2>/dev/null; return 1
  fi
  sync
  _bhsz=$(stat -c %s "$_bf" 2>/dev/null)
  if [ -n "$_dsz" ] && [ -n "$_bhsz" ] && [ "$_bhsz" != "$_dsz" ]; then
    echo "已中止: 备份大小异常（$_bhsz != $_dsz），分区未被改动"; return 1
  fi
  echo "备份完成: $_bf (${_bhsz:-?} 字节, sha256=$(dtbo_sha "$_bf"))"

  # 镜像小于分区时补零到分区大小：否则尾部残留旧 dtbo 数据导致头尾不一致，
  # 而按文件大小回读又会误报成功。补零失败即中止。
  _src="$_img"; _pad=""
  if [ -n "$_dsz" ] && [ "$_fsz" -lt "$_dsz" ]; then
    _pad="/data/local/tmp/dtbo_flash_pad.$$.img"
    if cp -f "$_img" "$_pad" 2>/dev/null && truncate -s "$_dsz" "$_pad" 2>/dev/null \
       && [ "$(stat -c %s "$_pad" 2>/dev/null)" = "$_dsz" ]; then
      _src="$_pad"; echo "镜像已补零至分区大小（$_dsz 字节）"
    else
      echo "已中止: 补零失败（cp/truncate 异常），分区未被改动"
      rm -f "$_pad" 2>/dev/null; return 1
    fi
  fi

  echo "写入中..."
  if ! dd if="$_src" of="$_dev" bs=1M 2>/dev/null; then
    echo "写入失败! 分区可能已损坏，请用备份恢复: $_bf"
    [ -n "$_pad" ] && rm -f "$_pad" 2>/dev/null
    return 1
  fi
  sync

  # (d) 回读校验：按写入源大小读回分区并比对 sha256。
  _wsz=$(stat -c %s "$_src" 2>/dev/null)
  case "$_wsz" in ''|*[!0-9]*) _wsz="$_fsz" ;; esac
  _fsha=$(dtbo_sha "$_src")
  _nsha=$(head -c "$_wsz" "$_dev" 2>/dev/null | sha256sum | awk '{print $1}')
  [ -n "$_pad" ] && rm -f "$_pad" 2>/dev/null
  if [ -n "$_nsha" ] && [ "$_nsha" = "$_fsha" ]; then
    echo "刷入成功 · 校验一致"
    echo "分区: $_dev"
    echo "镜像: $_base"
    echo "sha256: $_nsha"
    echo "备份: $_bf"
    echo "重启后生效"
    return 0
  fi
  echo "校验失败! 分区=$_nsha 镜像=$_fsha"
  echo "请用备份恢复: $_bf"
  return 1
}

# ---- 模块内置本地镜像（配合「在线下载」或手动放入 battery/dtbo/local.img）----
dtbo_local_img() { printf '%s' "$DTBO_DIR/local.img"; }

dtbo_haslocal() {
  [ -f "$(dtbo_local_img)" ] && echo yes || echo no
}

dtbo_flashlocal() {
  _f=$(dtbo_local_img)
  [ -f "$_f" ] || { echo "已拒绝: 本地镜像不存在 -> $_f（请先下载或放入）"; return 2; }
  echo "刷本地镜像: $_f"
  dtbo_flash "$_f" 1
}

# ---- 在线下载当前机型镜像 ----
# 下载源 = battery/config/settings.conf 的 dtbo_base（可直链的仓库 raw 根），
# 约定镜像路径 <dtbo_base>/dtbo/<dev_code>.img，清单 <dtbo_base>/dtbo/index.json（含 sha256）。
# 下到 battery/dtbo/local.img，直接配合「刷入模块内置镜像」。
dtbo_download() {
  _dev=$(dev_code 2>/dev/null)
  case "$_dev" in ''|unknown) echo "机型未识别，无法确定要下载的镜像"; return 1 ;; esac
  _base=$(cfg_get dtbo_base "https://gitee.com/xiaomirezhale/miwkjr-dtbo/raw/master")
  [ -n "$_base" ] || { echo "未配置 DTBO 下载源（settings.conf 的 dtbo_base）"; return 1; }
  command -v curl >/dev/null 2>&1 || { echo "本机无 curl，无法下载"; return 1; }
  _base=${_base%/}
  _url="$_base/dtbo/${_dev}.img"
  _dst=$(dtbo_local_img)
  mkdir -p "$DTBO_DIR" 2>/dev/null
  echo "下载中: $_url"
  if ! curl -fsSL --connect-timeout 20 --max-time 600 -o "$_dst.tmp" "$_url" 2>/dev/null; then
    rm -f "$_dst.tmp" 2>/dev/null
    echo "下载失败（网络不可达或地址无效）"
    return 1
  fi
  _sz=$(wc -c < "$_dst.tmp" 2>/dev/null | tr -d ' ')
  case "$_sz" in ''|*[!0-9]*|0)
    rm -f "$_dst.tmp" 2>/dev/null
    echo "下载内容为空，已丢弃"
    return 1 ;;
  esac
  if [ "$_sz" -lt 32768 ]; then
    rm -f "$_dst.tmp" 2>/dev/null
    echo "下载内容过小（${_sz}B，疑似错误页），已丢弃"
    return 1
  fi
  # 仓库清单里的 sha256；拿不到清单就跳过校验，不阻断
  _want=$(curl -fsSL --connect-timeout 15 --max-time 60 "$_base/dtbo/index.json" 2>/dev/null \
    | grep "\"$_dev\"" | sed -n 's/.*"sha256"[[:space:]]*:[[:space:]]*"\([0-9a-f]\{64\}\)".*/\1/p' | head -n1)
  if [ -n "$_want" ]; then
    _got=$(sha256sum "$_dst.tmp" 2>/dev/null | awk '{print $1}')
    if [ "$_got" != "$_want" ]; then
      rm -f "$_dst.tmp" 2>/dev/null
      echo "校验失败：sha256 与仓库清单不符，已丢弃"
      return 1
    fi
    echo "sha256 校验通过"
  fi
  mv -f "$_dst.tmp" "$_dst" 2>/dev/null || { rm -f "$_dst.tmp" 2>/dev/null; echo "写入失败"; return 1; }
  echo "已保存: $_dst（${_sz} 字节，机型 $_dev）"
  echo "可点「刷入模块内置镜像」刷入（刷入前自动备份）"
}

# ---- 候选镜像扫描目录（list/adddir/deldir 持久化在 state/extra_dirs.txt）----
DTBO_EDIRS_FILE="$STATE/extra_dirs.txt"

dtbo_dirs_show() {
  echo "[扫描目录]"
  if [ -s "$DTBO_EDIRS_FILE" ]; then
    while IFS= read -r _d; do
      [ -n "$_d" ] || continue
      if [ -d "$_d" ]; then echo "  ✓ $_d"; else echo "  - $_d (目录不存在)"; fi
    done < "$DTBO_EDIRS_FILE"
  else
    echo "  (无)"
  fi
  echo "添加: dtbo adddir <绝对路径>"
  return 0
}

dtbo_dirs_add() {
  _p=$(printf '%s' "$1" | sed 's:/*$::')
  [ -n "$_p" ] || { echo "用法: dtbo adddir <绝对路径>"; return 9; }
  case "$_p" in /*) ;; *) echo "请使用绝对路径（以 / 开头）"; return 2 ;; esac
  [ -d "$_p" ] || { echo "目录不存在: $_p"; return 2; }
  mkdir -p "$STATE" 2>/dev/null
  touch "$DTBO_EDIRS_FILE" 2>/dev/null
  if grep -Fxq "$_p" "$DTBO_EDIRS_FILE" 2>/dev/null; then
    echo "已在列表: $_p"; return 0
  fi
  printf '%s\n' "$_p" >> "$DTBO_EDIRS_FILE"
  echo "已添加: $_p"
  return 0
}

dtbo_dirs_del() {
  _p=$(printf '%s' "$1" | sed 's:/*$::')
  [ -n "$_p" ] || { echo "用法: dtbo deldir <绝对路径>"; return 9; }
  [ -s "$DTBO_EDIRS_FILE" ] || { echo "扫描列表为空"; return 0; }
  grep -Fxq "$_p" "$DTBO_EDIRS_FILE" 2>/dev/null || { echo "不在列表: $_p"; return 2; }
  grep -Fxv "$_p" "$DTBO_EDIRS_FILE" > "$DTBO_EDIRS_FILE.tmp" 2>/dev/null
  mv -f "$DTBO_EDIRS_FILE.tmp" "$DTBO_EDIRS_FILE" 2>/dev/null
  echo "已移除: $_p"
  return 0
}

# 列出候选镜像：序号|完整路径|字节|sha256前12位
dtbo_scan_list() {
  _i=0
  if [ -s "$DTBO_EDIRS_FILE" ]; then
    while IFS= read -r _d; do
      [ -n "$_d" ] || continue
      [ -d "$_d" ] || continue
      for _f in "$_d"/*.img; do
        [ -f "$_f" ] || continue
        _i=$((_i+1))
        _sz=$(stat -c %s "$_f" 2>/dev/null)
        _sh=$(dtbo_sha "$_f" | cut -c1-12)
        printf '%s|%s|%s|%s\n' "$_i" "$_f" "${_sz:-0}" "$_sh"
      done
    done < "$DTBO_EDIRS_FILE"
  fi
  [ "$_i" = "0" ] && echo "(未找到 .img)"
  return 0
}
