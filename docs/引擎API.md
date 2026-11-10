# 引擎 API

入口：

```sh
sh /data/adb/modules/HyperOS_XJ/engine/xjctl.sh <命令> [参数]
```

App 通过 `su -c` 调用；页面不直接拼 root 命令，统一走 `Engine` 对象。

## 状态

### `status`

输出平铺 JSON（所有值都是字符串）：

```json
{
  "cap": "64",
  "status": "Charging",
  "temp": "375",
  "curr": "1800000",
  "volt": "4480000",
  "cell": "4480000",
  "full": "4680000",
  "design": "5000000",
  "cycle": "128",
  "health": "2",
  "thermal_mode": "level_3",
  "thermal_default": "default",
  "power_tier": "inf",
  "power_applied": "inf",
  "unlock": "1",
  "freepps": "0",
  "cloud_guard": "1",
  "apl_enable": "0",
  "cycle_fake": "",
  "term_v": "",
  "bypass_manual": "0",
  "rearscreen": "0",
  "module_version": "3.1.0",
  "date": "2026-11-08",
  "device_name": "Xiaomi ...",
  "device_code": "...",
  "os_version": "...",
  "base_firmware": ""
}
```

单位：

- `temp`: 0.1℃
- `curr`: µA
- `volt` / `cell`: µV
- `full` / `design`: µAh

### `sysinfo`

四行：机型名 / 代号 / 系统 / 底包。

### `read`

KV 行，实时节点原始值。

### `detect`

重扫详细节点，写 `cache/detail.list` / `cache/ps.cache`。

## 功率

```sh
xjctl.sh power get
xjctl.sh power set inf|fast|mid|slow|<5-119>
xjctl.sh power status
xjctl.sh power restore
```

- `inf`：thermal_remove=1，恢复原电流。
- `fast/mid/slow`：40/25/10W（可在 settings.conf 覆盖 `fast_w/mid_w/slow_w`）。
- 数字：自定义瓦数；>=120 归一为 `inf`，<5 归一为 5。

## 温控

```sh
xjctl.sh thermal get
xjctl.sh thermal set default|unlimited|level_1..level_5
xjctl.sh thermal gov on|off
xjctl.sh thermal status
```

`thermal set` 只写 `settings.json` 的 `default_mode`；daemon 下一拍应用。

## 设置

```sh
xjctl.sh setting get <key>
xjctl.sh setting set <key> <value>
xjctl.sh setting dump
```

`set` 自动识别 bool / 数字 / 字符串。常用 key：

`enabled, default_mode, charge_mode, charge_level, sleep_level, time_start, time_end, time_level, game_mode, game_level, game_apps, game_fps, game_bypass, bypass_temp_on, bypass_temp_off, bypass_level, bypass_current, hyperos4, no_hitemp_stop, charge_gov, power_plan_on, power_plan_start, power_plan_end, power_plan_mode`

## 电池

```sh
xjctl.sh battery boot
xjctl.sh battery unlock on|off
xjctl.sh battery pps on|off|restore|status
xjctl.sh battery cloud on|off
xjctl.sh battery apl on|off
xjctl.sh battery cycle <N|clear|get>
xjctl.sh battery volt term <mV>
xjctl.sh battery volt off
xjctl.sh battery restore
xjctl.sh battery status
```

- `battery` 命令会 re-exec 到 PID1 mount namespace（BAA 需要）。
- `unlock on`：BAA mount + 可选 PSS disable。
- `unlock off`：BAA umount + PSS enable。
- `pps`（公版 PPS 解锁）：`on/off` 写配置并立即应用；`restore` 只把验证节点归零；`status` 打印 `freepps` 与两个验证节点的当前值。守护每拍调 `pps_tick`，把 `pd_verifed` 与 `usbpd_verifed` 锁到与开关一致（回读校验，失败记日志）。
- `cloud/apl`：写配置；引擎的 `battery_guard_tick` 负责 cycle、smart_fv 的基础节点写。

`battery status` 输出 KEY=value：

```text
unlock=1
baa=active|inactive
pss=enabled|disabled
freepps=0
cloud_guard=1
apl_enable=0
cycle_fake=
term_v=
power_limit=inf
```

## 旁路

```sh
xjctl.sh bypass on|off|status
```

写/删 `config/bypass_manual`；daemon 的 `bypass_decide` 读取。

## 背屏

```sh
xjctl.sh rear on|off|status
```

- `on`：安装/确认主 App，写 `rear.conf`，向 `com.hyperos.xj.rear.ControlReceiver` 发广播。
- `off`：写 `enabled=0`，发广播；hook 2 秒内隐藏。
- `status`：设备/APK/on/mode/conf/service。

## 单次应用

```sh
xjctl.sh apply
```

启动 `xjdaemon.sh --once`，跑一轮主循环。用于安装/卸载/调试。

## 退出码

- `0`：成功
- `2`：非法温控档位
- `9`：用法错误
- `battery unlock` 的 BAA 失败会返回 1/2/3/4（源缺失/目标缺失/校验失败/mount 失败）
