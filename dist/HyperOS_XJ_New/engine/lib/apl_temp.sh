#!/system/bin/sh
# engine/lib/apl_temp.sh — 温控档位 → 电池温度上限（℃）单一来源。被 apl.sh source。
# 语义：该档允许电池升到的最高温度，达到即由 APL 降功率/降流；低于（上限 − 滞回）恢复上探。
# 无档位温度线（unlimited/default/bypass/off/disabled/空）输出空，调用方退回原 DTBO 行为。

APL_TEMP_LIB=1

# 档位名 → 温度上限（℃）；无档位温度线输出空
apl_level_temp_c() {
  case "$1" in
    level_1) printf '30' ;;
    level_2) printf '35' ;;
    level_3) printf '40' ;;
    level_4) printf '45' ;;
    level_5) printf '50' ;;
    *) printf '' ;;
  esac
}

# 当前档位（$CONFDIR/current_mode）→ 温度上限（℃）；无档位温度线输出空
apl_mode_temp_c() {
  apl_level_temp_c "$(cat "$CONFDIR/current_mode" 2>/dev/null)"
}

# 档位名 → 温度超限后的充电功率上限（W）；无档位线输出空。
# 温度超过该档上限时，APL 目标电流会被压到「该瓦数 / 当前电压」，而不是只降 20%。
apl_level_power_w() {
  case "$1" in
    level_1) printf '15' ;;
    level_2) printf '25' ;;
    level_3) printf '35' ;;
    level_4) printf '45' ;;
    level_5) printf '55' ;;
    *) printf '' ;;
  esac
}

apl_mode_power_w() {
  apl_level_power_w "$(cat "$CONFDIR/current_mode" 2>/dev/null)"
}
