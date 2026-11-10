#!/system/bin/sh
# engine/lib/diag.sh — 日志查看与诊断打包。
# 被 xjctl.sh 在 `log` / `diag` 分支内 source；依赖 env.sh 的 MODDIR/CONFDIR/LOGDIR。
# 增补采集：state/、runtime.txt（daemon 存活）、sysfs.txt、thermal_dir.txt、
# module_state.txt、settings_conf.txt、dmesg_tail.txt；新增项同样过滤敏感名、逐项容错。

# settings.json 脱敏：命中敏感键名的值替换为 "***"，其余原样保留。
diag_sanitize_json() {
  awk '
    {
      line = $0
      if (match(line, /^[[:space:]]*"[^"]+"[[:space:]]*:[[:space:]]*/)) {
        key = substr(line, RSTART, RLENGTH)
        sub(/^[[:space:]]*"/, "", key)
        sub(/"[[:space:]]*:[[:space:]]*$/, "", key)
        lk = tolower(key)
        if (lk ~ /(token|secret|password|passwd|credential|api_?key|private|imei|serial|(^|_)sn($|_))/) {
          prefix = substr(line, 1, RSTART + RLENGTH - 1)
          rest = substr(line, RSTART + RLENGTH)
          comma = (rest ~ /,[[:space:]]*$/) ? "," : ""
          line = prefix "\"***\"" comma
        }
      }
      print line
    }
  ' "$1" > "$2"
}

diag_is_sensitive_name() {
  case "$1" in
    *token*|*secret*|*password*|*passwd*|*credential*|*api?key*|*secret_key*|*_private_key*|*.key|*imei*|*serial*) return 0 ;;
  esac
  return 1
}

# sysfs 逐项采集：存在才采（输出「标签 = 值」），并把 _sysfs_n 计数加一。
diag_node_line() {
  [ -e "$2" ] || return 1
  _dnv=$(cat "$2" 2>/dev/null)
  echo "$1 = ${_dnv:-<空>}" 
  _sysfs_n=$((_sysfs_n + 1))
}

# log/ 下按修改时间取最近的一个文件。
diag_recent_log() {
  [ -d "$LOGDIR" ] || return 1
  _newest=""; _mt=-1
  for _f in "$LOGDIR"/*; do
    [ -f "$_f" ] || continue
    _m=$(stat -c %Y "$_f" 2>/dev/null)
    case "$_m" in ''|*[!0-9]*) continue ;; esac
    if [ "$_m" -gt "$_mt" ]; then _mt="$_m"; _newest="$_f"; fi
  done
  [ -n "$_newest" ] || return 1
  printf '%s' "$_newest"
}

diag_log() {
  _n="${1:-200}"
  case "$_n" in ''|*[!0-9]*) echo "行数必须是数字: $_n"; return 2 ;; esac
  _f=$(diag_recent_log) || { echo "日志目录为空: $LOGDIR"; return 1; }
  echo "== 最近日志: $(basename "$_f")（最后 $_n 行）=="
  echo "路径: $_f"
  tail -n "$_n" "$_f" 2>/dev/null
}

# App 没有 /sdcard 读权限：分享与上传都要用 App 私有目录里的副本。
diag_copy_to_app() {
  _src="$1"
  [ -f "$_src" ] || return 1
  _appfiles=/data/user/0/com.hyperos.xj/files
  _dir="$_appfiles/diag"
  mkdir -p "$_dir" 2>/dev/null || return 1
  cp -f "$_src" "$_dir/" 2>/dev/null || return 1
  _uid=$(stat -c %u "$_appfiles" 2>/dev/null)
  case "$_uid" in ''|*[!0-9]*|0) ;; *) chown -R "$_uid:$_uid" "$_dir" 2>/dev/null ;; esac
  chmod 0644 "$_dir"/* 2>/dev/null
  return 0
}

# 图片已进包，清掉 App 侧待打包图，避免下次诊断重复上传同一批截图。
diag_clear_problem_images() {
  rm -f /data/user/0/com.hyperos.xj/files/diag_pics/* 2>/dev/null
}

# 打包：优先 zip（$.zip），设备无 zip 工具时回退 tar.gz（如实告知扩展名）。
diag_do_zip() {
  _stage="$1"; _out="$2"
  if command -v zip >/dev/null 2>&1; then
    ( cd "$(dirname "$_stage")" && zip -qr "$_out" "$(basename "$_stage")" ) >/dev/null 2>&1 \
      && [ -s "$_out" ] && { echo "zip"; return 0; }
  fi
  for _bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox; do
    [ -x "$_bb" ] || continue
    "$_bb" zip --help >/dev/null 2>&1 || continue
    ( cd "$(dirname "$_stage")" && "$_bb" zip -qr "$_out" "$(basename "$_stage")" ) >/dev/null 2>&1 \
      && [ -s "$_out" ] && { echo "busybox zip"; return 0; }
  done
  return 1
}

diag_do_targz() {
  _stage="$1"; _out="$2"
  if command -v tar >/dev/null 2>&1; then
    ( cd "$(dirname "$_stage")" && tar -czf "$_out" "$(basename "$_stage")" ) >/dev/null 2>&1 \
      && [ -s "$_out" ] && { echo "tar"; return 0; }
  fi
  for _bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox; do
    [ -x "$_bb" ] || continue
    ( cd "$(dirname "$_stage")" && "$_bb" tar -czf "$_out" "$(basename "$_stage")" ) >/dev/null 2>&1 \
      && [ -s "$_out" ] && { echo "busybox tar"; return 0; }
  done
  return 1
}

diag_pack() {
  _stamp=$(date +%Y%m%d-%H%M%S)
  _stage="/data/local/tmp/rezhale_toolbox_diag_$_stamp"
  rm -rf "$_stage" 2>/dev/null
  mkdir -p "$_stage/log" "$_stage/config" 2>/dev/null \
    || { echo "无法创建临时目录: $_stage"; return 1; }

  {
    echo "热炸了工具箱 诊断包"
    echo "采集时间: $(date '+%F %T')"
    echo "机型    : $(getprop ro.product.model 2>/dev/null) / $(getprop ro.product.device 2>/dev/null)"
    echo "机型代号: $(dev_code 2>/dev/null)"
    echo "模块版本: $(mod_version)"
    echo "内核    : $(uname -r 2>/dev/null)"
    echo "槽位    : $(getprop ro.boot.slot_suffix 2>/dev/null)"
    echo "系统    : Android $(getprop ro.build.version.release 2>/dev/null) (SDK $(getprop ro.build.version.sdk 2>/dev/null))"
  } > "$_stage/device_info.txt" 2>/dev/null

  # ---- images/：App 里选的问题截图，随诊断包一起上传（压缩在 App 侧完成） ----
  _img_n=0
  _img_kb=0
  for _id in /data/user/0/com.hyperos.xj/files/diag_pics "$DATA_DIR/state/diag_pics"; do
    [ -d "$_id" ] || continue
    for _f in "$_id"/*; do
      [ -f "$_f" ] || continue
      mkdir -p "$_stage/images" 2>/dev/null
      cp -f "$_f" "$_stage/images/" 2>/dev/null && _img_n=$((_img_n + 1))
    done
  done
  [ "$_img_n" -gt 0 ] && _img_kb=$(du -sk "$_stage/images" 2>/dev/null | awk '{print $1}')

  _n=0
  for _f in "$LOGDIR"/*; do
    [ -f "$_f" ] || continue
    cp -f "$_f" "$_stage/log/" 2>/dev/null && _n=$((_n + 1))
  done

  _cn=0
  for _f in "$CONFDIR"/*; do
    [ -f "$_f" ] || continue
    _b=$(basename "$_f")
    if [ "$_b" = "settings.json" ]; then
      diag_sanitize_json "$_f" "$_stage/config/$_b" 2>/dev/null && _cn=$((_cn + 1))
      continue
    fi
    diag_is_sensitive_name "$_b" && continue
    cp -f "$_f" "$_stage/config/$_b" 2>/dev/null && _cn=$((_cn + 1))
  done

  # ---- state/：STATE 运行态全量（含子目录；敏感名过滤；tmp/frag 临时件跳过） ----
  _sn=0
  mkdir -p "$_stage/state" 2>/dev/null
  for _f in "$STATE"/*; do
    if [ -d "$_f" ]; then
      cp -r "$_f" "$_stage/state/" 2>/dev/null && _sn=$((_sn + 1))
      continue
    fi
    [ -f "$_f" ] || continue
    _b=$(basename "$_f")
    case "$_b" in *tmp*|*frag*) continue ;; esac
    diag_is_sensitive_name "$_b" && continue
    cp -f "$_f" "$_stage/state/$_b" 2>/dev/null && _sn=$((_sn + 1))
  done

  # ---- runtime.txt：daemon 存活证据（/proc cmdline 扫描 + pid 核验）与时间基准 ----
  {
    echo "== xjdaemon 进程（/proc cmdline 扫描）=="
    # 读 cmdline 要访问目标进程内存，目标正在退出时可能阻塞；整体套 timeout（bug-121）
    timeout 8 sh -c 'for d in /proc/[0-9]*; do
      cl=$(tr "\0" " " < "$d/cmdline" 2>/dev/null) || continue
      case "$cl" in *xjdaemon.sh*) echo "${d#/proc/}: $cl" ;; esac
    done' 2>/dev/null
    echo "== daemon.pid 核验 =="
    _dp=$(cat "$STATE/daemon.pid" 2>/dev/null)
    case "$_dp" in
      ''|*[!0-9]*) echo "pid: ${_dp:-无}（缺失或非数字，不核验）"; _dp= ;;
    esac
    if [ -n "$_dp" ]; then
      _dpc=$(timeout 3 sh -c 'tr "\0" " " < "/proc/'"$_dp"'/cmdline"' 2>/dev/null)
      [ -n "$_dpc" ] && echo "pid: $_dp 存活 cmdline: $_dpc" \
        || echo "pid: $_dp 无 /proc/$_dp/cmdline（进程已退出）"
    fi
    echo "== 时间基准 =="
    echo "uptime_1: $(cut -d ' ' -f1 /proc/uptime 2>/dev/null)"
    echo "date: $(date '+%F %T')"
  } > "$_stage/runtime.txt" 2>/dev/null
  [ -s "$_stage/runtime.txt" ] && _rt=ok || _rt=失败

  # ---- sysfs.txt：充电/电池/USB/充电器节点与热区 type/temp 配对（存在才采） ----
  _sysfs_n=0
  {
    echo "== 充电节点 =="
    [ -n "${CHARGE_NODE:-}" ] && diag_node_line "charge_node($CHARGE_NODE)" "$CHARGE_NODE"
    diag_node_line "rt_charge_orig($CONFDIR/rt_charge_orig)" "$CONFDIR/rt_charge_orig"
    echo "== power_supply/battery =="
    for _pn in temp current_now voltage_now capacity status health; do
      diag_node_line "battery.$_pn" "/sys/class/power_supply/battery/$_pn"
    done
    echo "== power_supply/usb =="
    for _pn in voltage_max current_max input_power_limit real_type type online present; do
      diag_node_line "usb.$_pn" "/sys/class/power_supply/usb/$_pn"
    done
    echo "== 充电器功率 =="
    diag_node_line "charger_common.power_max" "/sys/class/xm_power/charger/charger_common/power_max"
    echo "== qcom-battery（PD 验证/快充类型/认证）=="
    for _pn in pd_verifed quick_charge_type real_type authentic power_max cid_status; do
      diag_node_line "qcom.$_pn" "/sys/class/qcom-battery/$_pn"
    done
    echo "== 充电限流与温控接线 =="
    diag_node_line "wired_chg_curr" "/sys/class/xm_power/charger/charger_thermal/wired_chg_curr"
    diag_node_line "smart_fv" "/sys/class/xm_power/charger/smart_charge/smart_fv"
    echo "== APL 状态 =="
    diag_node_line "apl_state" "$STATE/apl_state"
    diag_node_line "apl_target_ma" "$STATE/apl_target_ma"
    echo "== thermal_zone（type/temp 配对）=="
    for _tz in /sys/class/thermal/thermal_zone*; do
      [ -d "$_tz" ] || continue
      diag_node_line "${_tz##*/}.type" "$_tz/type"
      diag_node_line "${_tz##*/}.temp" "$_tz/temp"
    done
  } > "$_stage/sysfs.txt" 2>/dev/null

  # ---- thermal_dir.txt：热控目录清单 + t_map 尺寸对比（只比 size，不 dump 内容） ----
  if [ -d "$THERMAL_PATH" ]; then
    _th=ok
    {
      echo "THERMAL_PATH: $THERMAL_PATH"
      echo "== ls -l =="
      ls -l "$THERMAL_PATH" 2>/dev/null
      echo "== t_map 尺寸对比（不 dump 内容）=="
      echo "模块内 $MODDIR/t_map: $(stat -c %s "$MODDIR/t_map" 2>/dev/null || echo 无)"
      echo "目录内 $THERMAL_PATH/t_map: $(stat -c %s "$THERMAL_PATH/t_map" 2>/dev/null || echo 无)"
    } > "$_stage/thermal_dir.txt" 2>/dev/null
  else
    _th=无目录
    echo "THERMAL_PATH 不存在: ${THERMAL_PATH:-未设置}" > "$_stage/thermal_dir.txt" 2>/dev/null
  fi

  # ---- module_state.txt：模块/升级暂存/标记与 App 版本三方对账 ----
  {
    echo "== 模块 $MODDIR/module.prop =="
    if [ -f "$MODDIR/module.prop" ]; then cat "$MODDIR/module.prop" 2>/dev/null
    else echo "（缺失）"; fi
    echo "== 升级暂存 /data/adb/modules_update/rezhale_toolbox/module.prop =="
    if [ -f /data/adb/modules_update/rezhale_toolbox/module.prop ]; then
      cat /data/adb/modules_update/rezhale_toolbox/module.prop 2>/dev/null
    else
      echo "（无升级暂存）"
    fi
    echo "== 标记 =="
    [ -e "$MODDIR/update" ] && echo "update: 存在" || echo "update: 无"
    [ -e "$MODDIR/disable" ] && echo "disable: 存在" || echo "disable: 无"
    echo "== App 版本（dumpsys package com.hyperos.xj）=="
    _app=$(dumpsys package com.hyperos.xj 2>/dev/null | grep -E 'versionName|versionCode|lastUpdateTime')
    [ -n "$_app" ] && echo "$_app" || echo "（dumpsys 不可用或未装该包）"
  } > "$_stage/module_state.txt" 2>/dev/null
  [ -s "$_stage/module_state.txt" ] && _mr=ok || _mr=失败

  # ---- procs.txt：高 CPU / 卡死进程（tr、sh 卡在 cmdline 读取之类的问题靠它定位）----
  {
    echo "== top（按 CPU 排序，取头部）=="
    timeout 8 top -b -n 1 2>/dev/null | head -25
    echo "== ps（PID/PPID/STAT/%CPU/ARGS）=="
    timeout 8 ps -A -o PID,PPID,STAT,%CPU,ARGS 2>/dev/null | head -25
    echo "== tr / sh / timeout / xjdaemon 相关进程（含 fd0 指向）=="
    timeout 8 sh -c 'for d in /proc/[0-9]*; do
      [ -r "$d/comm" ] || continue
      c=$(cat "$d/comm" 2>/dev/null) || continue
      case "$c" in tr|sh|timeout|xjdaemon*|busybox) ;; *) continue ;; esac
      st=$(cat "$d/stat" 2>/dev/null)
      stt=$(echo "$st" | awk "{print \$3}")
      pp=$(echo "$st" | awk "{print \$4}")
      echo "${d#/proc/} comm=$c state=$stt ppid=$pp fd0=$(readlink "$d/fd/0" 2>/dev/null)"
    done' 2>/dev/null
    echo "== R/D 状态进程（可能卡住）=="
    timeout 8 sh -c 'for d in /proc/[0-9]*; do
      st=$(cat "$d/stat" 2>/dev/null) || continue
      case "$(echo "$st" | awk "{print \$3}")" in R|D) echo "${d#/proc/} $(cat "$d/comm" 2>/dev/null)" ;; esac
    done' 2>/dev/null | head -30
  } > "$_stage/procs.txt" 2>/dev/null
  [ -s "$_stage/procs.txt" ] && _pr=ok || _pr=失败

  # ---- settings_conf.txt：引擎功能配置（CONFDIR/settings.conf 优先，回退电池 settings.conf；均非密钥） ----
  _sc_src=""
  if [ -f "$CONFDIR/settings.conf" ]; then
    _sc_src="$CONFDIR/settings.conf"
  elif [ -n "${BT_CONF:-}" ] && [ -f "$BT_CONF" ]; then
    _sc_src="$BT_CONF"
  fi
  _sc=无
  if [ -n "$_sc_src" ]; then
    if diag_is_sensitive_name "$(basename "$_sc_src")"; then
      _sc=跳过
    elif cp -f "$_sc_src" "$_stage/settings_conf.txt" 2>/dev/null; then
      _sc=ok
    else
      _sc=失败
    fi
  fi

  # ---- dmesg_tail.txt：内核日志尾 200 行（无权限时如实标注，不报错） ----
  dmesg 2>/dev/null | tail -n 200 > "$_stage/dmesg_tail.txt" 2>/dev/null
  if [ -s "$_stage/dmesg_tail.txt" ]; then
    _dm=ok
  else
    echo "无权限或无 dmesg 输出" > "$_stage/dmesg_tail.txt" 2>/dev/null
    _dm=无权限
  fi

  # ---- problem.txt：用户在 App 里填写的问题描述，随包一起带给作者 ----
  for _pn in "$DATA_DIR/state/diag_note.txt" /data/user/0/com.hyperos.xj/files/diag_note.txt; do
    if [ -f "$_pn" ] && [ -s "$_pn" ]; then
      cp -f "$_pn" "$_stage/problem.txt" 2>/dev/null && break
    fi
  done

  mkdir -p /sdcard/Download 2>/dev/null
  _zip="/sdcard/Download/rezhale_toolbox_diag_$_stamp.zip"
  _how=$(diag_do_zip "$_stage" "$_zip") || _how=""
  if [ -n "$_how" ]; then
    _sz=$(stat -c %s "$_zip" 2>/dev/null)
    rm -rf "$_stage" 2>/dev/null
    echo "已导出: $_zip"
    diag_copy_to_app "$_zip"
    if [ "$_img_n" -gt 0 ]; then diag_clear_problem_images; fi
    echo "大小: ${_sz:-?} 字节   打包: $_how"
    echo "内容: 日志 $_n 个，配置 $_cn 个（settings.json 已脱敏）图片 $_img_n 张（${_img_kb:-0}KB）"
    echo "采集: state $_sn / runtime ${_rt} / sysfs ${_sysfs_n} 项 / thermal ${_th} / module ${_mr} / settings ${_sc} / dmesg ${_dm} / procs ${_pr:-n/a}"
    return 0
  fi

  # 设备无 zip 工具：回退 tar.gz，并按真实扩展名报告。
  _tgz="/sdcard/Download/rezhale_toolbox_diag_$_stamp.tar.gz"
  _how=$(diag_do_targz "$_stage" "$_tgz") || _how=""
  if [ -n "$_how" ]; then
    _sz=$(stat -c %s "$_tgz" 2>/dev/null)
    rm -rf "$_stage" 2>/dev/null
    echo "已导出: $_tgz"
    diag_copy_to_app "$_tgz"
    if [ "$_img_n" -gt 0 ]; then diag_clear_problem_images; fi
    echo "说明: 设备无 zip 工具，已用 $_how 打包（扩展名 .tar.gz）"
    echo "大小: ${_sz:-?} 字节"
    echo "内容: 日志 $_n 个，配置 $_cn 个（settings.json 已脱敏）图片 $_img_n 张（${_img_kb:-0}KB）"
    echo "采集: state $_sn / runtime ${_rt} / sysfs ${_sysfs_n} 项 / thermal ${_th} / module ${_mr} / settings ${_sc} / dmesg ${_dm} / procs ${_pr:-n/a}"
    return 0
  fi

  echo "打包失败: 设备无 zip/tar 可用；散装文件保留在 $_stage"
  echo "采集: state $_sn / runtime ${_rt} / sysfs ${_sysfs_n} 项 / thermal ${_th} / module ${_mr} / settings ${_sc} / dmesg ${_dm} / procs ${_pr:-n/a}"
  return 1
}
