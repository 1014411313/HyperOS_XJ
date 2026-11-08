#!/system/bin/sh
# engine/lib/data.sh — 数据层：节点定位、每拍采样、详情扫描、机型信息。
# 逻辑整理自 base.apk(com.yunan.chargehub) 的 detect/read/sysinfo，全部改为函数。

find_ps() {
  BAT="/sys/class/power_supply/battery"
  [ -f "$BAT/capacity" ] || BAT=""
  [ -n "$BAT" ] || for _p in /sys/class/power_supply/*; do
    [ -f "$_p/capacity" ] && [ "$(cat "$_p/type" 2>/dev/null)" = "Battery" ] && { BAT="$_p"; break; }
  done
  USB="/sys/class/power_supply/usb"
  [ -f "$USB/online" ] || USB=""
  [ -n "$USB" ] || for _p in /sys/class/power_supply/*; do
    case "$(cat "$_p/type" 2>/dev/null)" in USB*) USB="$_p"; break ;; esac
  done
}

# 每拍采样：写入全局 BAT_TEMP(0.1℃) / BAT_CUR(uA) / BAT_CAP(%) / BAT_VOLT(uV) / BAT_STATUS。
sample() {
  _b="${BAT:-/sys/class/power_supply/battery}"
  BAT_TEMP=$(cat "$_b/temp" 2>/dev/null)
  case "$BAT_TEMP" in ''|*[!0-9-]*) BAT_TEMP=0 ;; esac
  BAT_CUR=$(cat "$_b/current_now" 2>/dev/null)
  case "$BAT_CUR" in ''|*[!0-9-]*) BAT_CUR=0 ;; esac
  BAT_CAP=$(cat "$_b/capacity" 2>/dev/null)
  case "$BAT_CAP" in ''|*[!0-9]*) BAT_CAP=0 ;; esac
  BAT_VOLT=$(cat "$_b/voltage_now" 2>/dev/null)
  case "$BAT_VOLT" in ''|*[!0-9]*) BAT_VOLT=0 ;; esac
  BAT_STATUS=$(cat "$_b/status" 2>/dev/null)
  [ -n "$BAT_STATUS" ] || BAT_STATUS="Unknown"
  IS_CHARGING=0
  case "$BAT_STATUS" in Charging|Full) IS_CHARGING=1 ;; esac
}

# 一次性扫描详情节点，写 detail.list + ps.cache（原 base.apk detect.sh）。
data_detect() {
  find_ps
  _out="$CACHE/detail.list"; : > "$_out"
  _dirs="$BAT /sys/class/power_supply/battery /sys/class/power_supply/bms /sys/class/qcom-battery /sys/class/xm_power /sys/class/power_supply/batt_verify /sys/class/power_supply/fuelgauge"
  _add() {
    _lab=$1; shift
    for _d in $_dirs; do
      [ -d "$_d" ] || continue
      for _n in "$@"; do
        if [ -f "$_d/$_n" ] && _v=$(cat "$_d/$_n" 2>/dev/null) && [ -n "$_v" ]; then
          echo "$_lab|$_d/$_n" >> "$_out"; return
        fi
      done
    done
  }
  _add "电池寿命(SOH)" soh battery_soh state_of_health
  _add "QMAX" qmax qmax0 batt_qmax fg_qmax
  _add "剩余容量(RM)" rm remaining_capacity fg_rm charge_counter
  _add "电芯电压" cell_voltage cell1_voltage cell1_volt batt_cell1_vol vcell fg_vcell
  _add "电池型号" battery_type model_name batt_type battery_id_name
  _add "电池厂商" manufacturer battery_vendor batt_manufacturer
  _add "生产日期" manufacturing_date battery_date mfg_date manufacture_date
  _add "温控等级" system_temp_level thermal_level
  _add "充电电流上限" constant_charge_current_max fcc
  _add "预计充满" time_to_full_now

  _pf() { [ -f "$2" ] && _v=$(cat "$2" 2>/dev/null) && [ -n "$_v" ] && echo "P:$1|$2" >> "$_out"; }
  _Q=/sys/class/qcom-battery; _T=/sys/class/typec/port0; _PT=/sys/class/power_supply/typec
  _pf "快充类型" "$_Q/quick_charge_type"
  _pf "实际充电器类型" "$_Q/real_type"
  _pf "USB 实际类型" "$_Q/usb_real_type"
  _pf "小米适配认证" "$_Q/pd_verified"
  _pf "系统标称功率" "$_Q/power_max"
  _pf "Type-C 供电模式" "$_T/power_operation_mode"
  _pf "Type-C 供电角色" "$_T/power_role"
  _pf "USB PD 版本" "$_T/usb_power_delivery_revision"
  _pf "APDO 最大功率" "$_PT/apdo_max"
  _pf "PPS PTF" "$_PT/typec_pps_ptf"
  for _kw in fast_charge apdo adapter_id strategy charger_type pd_auth; do
    _f=$(find -L /sys/class/xm_power -maxdepth 4 -name "*$_kw*" -type f 2>/dev/null | head -n 2)
    for _x in $_f; do _pf "xm ${_x##*/}" "$_x"; done
  done

  {
    echo "CV=60"; echo "BAT='$BAT'"; echo "USB='$USB'"
    _ps=""
    for _p in /sys/class/power_supply/*; do
      [ -f "$_p/online" ] || continue
      case "$(cat "$_p/type" 2>/dev/null)" in Battery|BMS|Unknown) ;; *) _ps="$_ps $_p/online" ;; esac
    done
    echo "ONL='$_ps'"
    _tz=""; _n=0
    for _z in /sys/class/thermal/thermal_zone*; do
      _t=$(cat "$_z/type" 2>/dev/null)
      case "$_t" in
        *usb*|*conn*|*chg*|*charger*|*batt*|*skin*)
          [ "$_n" -lt 8 ] && { _tz="$_tz $_t:$_z/temp"; _n=$((_n + 1)); } ;;
      esac
    done
    echo "TZ='$_tz'"
    _UT=""
    [ -n "$USB" ] && { [ -f "$USB/real_type" ] && _UT="$USB/real_type" || _UT="$USB/usb_type"; }
    [ -f /sys/class/qcom-battery/usb_real_type ] && _UT=/sys/class/qcom-battery/usb_real_type
    echo "UT='$_UT'"
    _fgf() { for _n in $2; do [ -f "$1/$_n" ] && cat "$1/$_n" >/dev/null 2>&1 && { echo "$1/$_n"; return; }; done; }
    _FM=/sys/class/xm_power/fg_master; _FS=/sys/class/xm_power/fg_slave
    _VN="voltage_now vbat fg_vbat batt_vol vol voltage vcell cell_voltage"
    _IN="current_now ibat fg_ibat batt_curr curr current"
    _QN="qmax fg_qmax qmax0 batt_qmax"
    if [ -f "$_Q/fg1_voltage" ] && [ -f "$_Q/fg2_voltage" ]; then
      echo "C1V=$_Q/fg1_voltage"; echo "C2V=$_Q/fg2_voltage"
      [ -f "$_Q/fg1_current" ] && echo "C1I=$_Q/fg1_current"
      [ -f "$_Q/fg2_current" ] && echo "C2I=$_Q/fg2_current"
      [ -f "$_Q/fg1_qmax" ] && echo "C1Q=$_Q/fg1_qmax"
      [ -f "$_Q/fg2_qmax" ] && echo "C2Q=$_Q/fg2_qmax"
    elif [ -d "$_FM" ] && [ -d "$_FS" ]; then
      echo "C1V='$(_fgf "$_FM" "$_VN")'"; echo "C2V='$(_fgf "$_FS" "$_VN")'"
      echo "C1I='$(_fgf "$_FM" "$_IN")'"; echo "C2I='$(_fgf "$_FS" "$_IN")'"
      echo "C1Q='$(_fgf "$_FM" "$_QN")'"; echo "C2Q='$(_fgf "$_FS" "$_QN")'"
      echo "FGL='$(ls "$_FM" 2>/dev/null | tr '\n' ' ')'"
    fi
    [ -f /sys/class/qcom-battery/power_max ] && echo "PMX=/sys/class/qcom-battery/power_max"
    [ -f /sys/class/qcom-battery/ibus_total ] && echo "IBT=/sys/class/qcom-battery/ibus_total"
  } > "$CACHE/ps.cache"
  cat "$_out"
}

# 单串电芯电压（µV）；双电芯机型 voltage_now 可能是串联总压。
cell_uv() {
  _b="${BAT:-/sys/class/power_supply/battery}"
  for _p in "$_b/voltage_avg" /sys/class/qcom-battery/cell1_volt "$_b/voltage_now"; do
    _v=$(cat "$_p" 2>/dev/null)
    case "$_v" in ''|*[!0-9]*) continue ;; esac
    [ "$_v" -ge 2500000 ] || continue
    [ "$_v" -le 5400000 ] && { echo "$_v"; return 0; }
  done
  _v=$(cat "$_b/voltage_now" 2>/dev/null)
  case "$_v" in ''|*[!0-9]*) echo 0 ;; *) echo $((_v / 2)) ;; esac
}

# 每秒读取（原 base.apk read.sh）：零 fork 读节点，输出 KEY=value 行。
data_read() {
  [ -f "$CACHE/ps.cache" ] || data_detect >/dev/null 2>&1
  . "$CACHE/ps.cache"
  [ "$CV" = 60 ] || { data_detect >/dev/null 2>&1; . "$CACHE/ps.cache"; }
  _rd() { [ -r "$2" ] && { IFS= read -r _v < "$2"; echo "$1=$_v"; }; }
  _rd cap "$BAT/capacity"; _rd status "$BAT/status"; _rd temp "$BAT/temp"
  _rd volt "$BAT/voltage_now"
  _cell=$(cell_uv)
  case "$_cell" in ''|0) ;; *) echo "cell=$_cell" ;; esac
  if [ -r "$BAT/current_now" ]; then _rd curr "$BAT/current_now"; else _rd curr /sys/class/power_supply/bms/current_now; fi
  _rd cycle "$BAT/cycle_count"; _rd cycr /sys/class/xm_power/fg_master/qmax_cyclecount; _rd health "$BAT/health"
  _rd full "$BAT/charge_full"; _rd design "$BAT/charge_full_design"
  _on=0
  for _f in $ONL; do [ -r "$_f" ] && { IFS= read -r _v < "$_f"; [ "$_v" = "1" ] && _on=1; }; done
  echo "online=$_on"
  if [ -n "$USB" ]; then
    _rd usb_v "$USB/voltage_now"
    if [ -r "$USB/input_current_now" ]; then
      _rd usb_i "$USB/input_current_now"
    elif [ -n "$IBT" ] && [ -r "$IBT" ]; then
      IFS= read -r _v < "$IBT"
      case "$_v" in ''|*[!0-9-]*) ;; *) echo "usb_i=$((_v * 1000))" ;; esac
    fi
  fi
  [ -n "$UT" ] && _rd usb_type "$UT"
  _rd c1v "$C1V"; _rd c2v "$C2V"; _rd c1i "$C1I"; _rd c2i "$C2I"
  _rd c1q "$C1Q"; _rd c2q "$C2Q"; _rd pmax "$PMX"
  [ -n "$FGL" ] && echo "fgl=$FGL"
  if [ -f "$CACHE/detail.list" ]; then
    while IFS='|' read -r _lab _path; do
      [ -r "$_path" ] && { IFS= read -r _v < "$_path"; case "$_path" in *charge_counter) case "$_v" in *[!0-9-]*|"") ;; *) _v=$((_v / 1000)) ;; esac ;; esac; echo "dt|$_lab=$_v"; }
    done < "$CACHE/detail.list"
  fi
  for _e in $TZ; do _n=${_e%%:*}; _f=${_e#*:}; [ -r "$_f" ] && { IFS= read -r _v < "$_f"; echo "tz|$_n=$_v"; }; done
}

# 四行输出：机型名 / 机型代号 / 系统 / 底包（原 base.apk sysinfo.sh）。
data_sysinfo() {
  _g() { for _p; do _v=$(getprop "$_p"); [ -n "$_v" ] && { echo "$_v"; return; }; done; }
  _DEV=$(_g ro.product.vendor.device ro.product.odm.device ro.product.device)
  _SUP=" dada haotian miro annibale warsaw "
  case "$_SUP" in *" $_DEV "*) ;; *) for _p in ro.product.vendor.device ro.product.odm.device ro.product.device ro.product.system.device; do _v=$(getprop "$_p"); case "$_SUP" in *" $_v "*) _DEV=$_v; break ;; esac; done ;; esac
  _bad() { case "$1" in ""|*" for arm64"*|*" for arm"*|*[Gg]eneric*|*AOSP*|*aosp*|mainline*|qssi*) return 0 ;; esac; return 1; }
  _gn() { for _p; do _v=$(getprop "$_p"); _bad "$_v" || { echo "$_v"; return; }; done; }
  _NAME=$(_gn ro.product.vendor.marketname ro.product.odm.marketname ro.vendor.oplus.market.name ro.product.marketname)
  if [ -z "$_NAME" ]; then
    for _f in /vendor/build.prop /odm/etc/build.prop /vendor/odm/etc/build.prop; do
      _v=$(grep -m1 -E '^ro\.product\.(vendor|odm)\.marketname=' "$_f" 2>/dev/null | cut -d= -f2-)
      _bad "$_v" || { _NAME=$_v; break; }
    done
  fi
  if [ -z "$_NAME" ]; then case "$_DEV" in
    dada) _NAME="Xiaomi 15" ;; haotian) _NAME="Xiaomi 15 Pro" ;; miro) _NAME="REDMI K80 Pro" ;;
    annibale) _NAME="REDMI K90" ;; warsaw) _NAME="REDMI K90 至尊版" ;;
    *) _NAME=$(_gn ro.product.vendor.model ro.product.odm.model ro.product.model) ;;
  esac; fi
  _OPL=$(getprop ro.build.version.oplusrom.display); [ -n "$_OPL" ] || _OPL=$(getprop ro.build.version.oplusrom)
  _MI=$(_g ro.mi.os.version.incremental)
  _INC=$(getprop ro.build.version.incremental)
  if [ -n "$_OPL" ]; then _OS="ColorOS ${_OPL#V}"
  elif [ -n "$_MI" ]; then _OS="$_MI"
  else case "$_INC" in OS*|V*) _OS="$_INC" ;; *) _OS=$(_g ro.build.display.id ro.build.version.incremental) ;; esac; fi
  _pick() { grep -oE '(OS[0-9]+(\.[0-9]+)+\.[A-Z]+|V[0-9]+(\.[0-9]+)+\.[A-Z]+)' | head -1; }
  _BASE=""
  for _p in ro.vendor.build.version.incremental ro.odm.build.version.incremental ro.vendor_dlkm.build.version.incremental ro.mi.os.version.incremental ro.vendor.build.fingerprint ro.odm.build.fingerprint ro.bootimage.build.fingerprint; do
    _BASE=$(getprop "$_p" | _pick); [ -n "$_BASE" ] && break
  done
  [ -n "$_BASE" ] || for _f in /vendor/build.prop /odm/etc/build.prop /vendor/odm/etc/build.prop /vendor_dlkm/etc/build.prop; do
    _BASE=$(grep -E '^ro\.(vendor|odm|vendor_dlkm)\.build\.(version\.incremental|fingerprint)=' "$_f" 2>/dev/null | _pick)
    [ -n "$_BASE" ] && break
  done
  echo "${_NAME:-未知机型}"; echo "${_DEV:-unknown}"; echo "${_OS:-未知系统}"; echo "$_BASE"
}
