# 引擎 API

入口：

```sh
sh /data/adb/modules/rezhale_toolbox/engine/xjctl.sh <命令> [参数]
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
- `pps`（公版 PPS 解锁）：`on/off` 保存开关，由守护确认 USB 拔线后准备或恢复验证节点。`restore` 保存关闭状态，守护已退出时只在确认拔线后恢复。恢复限于本次开机由本模块接管的节点。`status` 显示配置、现有节点值和节点是否可用。已插线、拔线信息未知、多个候选或独立 FreePPS 启用时不写节点。
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

## DTBO

所有写入都要求 root；App 先预检并显示固定对象摘要，再将独立的 snapshot token 交给引擎确认。摘要供人核对，64位 snapshot token 用于绑定本次预检并仅能在5分钟内消费一次；两者不是同一个值。预检不是刷入，批准代码修改也不代表批准在设备上刷写。

```sh
xjctl.sh dtbo status
xjctl.sh dtbo resolve
xjctl.sh dtbo backup [目录]
xjctl.sh dtbo backups
xjctl.sh dtbo download [--ack-os-unknown]
xjctl.sh dtbo preflight <镜像> [--ack-os-unknown]
xjctl.sh dtbo flash <镜像> --confirm <snapshot_token> [--ack-os-unknown]
xjctl.sh dtbo preflight --restore <备份路径>
xjctl.sh dtbo restore <备份路径> --confirm <snapshot_token>
```

- 不接受未知或矛盾槽位、不可确认的分区大小，以及缺少可信机型/大小/SHA256 记录的镜像；`local.img` 与手动选择镜像使用相同校验。
- 系统版本范围缺失时标记兼容性未知，用户额外确认后显式使用 `--ack-os-unknown`；范围明确不兼容时拒绝。
- 备份优先 `/sdcard/Download/dtbo_backup`，不可用时回退 `/data/adb/rezhale_toolbox/battery/state/dtbo_backup`。只有实际备份文件的槽位、大小与 SHA256 均核验通过才可复用，manifest 记录不单独代表备份有效。
- 确认后的镜像或分区身份变化时拒绝执行；短镜像补齐至已知分区大小后写入，并按整个分区回读校验。写入或校验失败时尝试恢复本次验证过的备份，恢复结果单独报告。
- 不自动重启，不在卸载时自动选择历史镜像刷回；备份只代表某时刻的分区内容，不能据“最早”或文件名断言是原厂镜像。

## 自检

```sh
su -c 'sh /data/adb/modules/rezhale_toolbox/engine/xjctl.sh selfcheck'
```

输出 14 项自动检查、人工确认（`HUMAN`）与不适用/未实现项（`SKIP`，含 19-22 项未实现的动作用例）；末行汇总 `PASS / FAIL / HUMAN / SKIP`。无背屏机型的背屏相关项（11～16）按 `SKIP` 计，不冒充通过。该入口跳过配置初始化、缓存和临时文件写入，不切换功率、温控、解容或背屏开关。不提供 `--full`：传入额外参数返回9。

有任一 `FAIL` 返回3，无 `FAIL` 返回0；返回0仍须完成 `HUMAN` 项。判据要点：第9/10项读 `/proc/1/mountinfo`（PID1 视角），读不到即 `FAIL 无法判定`，不退回自身 `/proc/mounts`；第4项核对 `daemon_pid` 的 cmdline 精确指向本模块 `engine/xjdaemon.sh`；第5项允许采样日志被截尾丢表头，但要求最新段有 13 列合法数据行且段头不是未来时间；第7项拒绝未来时间戳，上限 90000s（间隔 86400s + 宽限 3600s），仅说明清理流程按时执行；第8项用 awk 严格校验 flat JSON（逐字符校验引号/转义/逗号/重复键）并核 status 退出码。容器验证不代表真机验证通过。

## 单次应用

```sh
xjctl.sh apply
```

启动 `xjdaemon.sh --once`，跑一轮主循环。用于安装/卸载/调试。

## 退出码

- `0`：成功
- `2`：非法温控档位
- `3`：`selfcheck` 至少有一项 `FAIL`
- `9`：用法错误
- `battery unlock` 的 BAA 失败会返回 1/2/3/4（源缺失/目标缺失/校验失败/mount 失败）
