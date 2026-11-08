# HyperOS XJ · 动态温控 / 解容 / 背屏

新架构：**自研引擎 + 单 App（控制端 + LSP 背屏）+ 新模块**。

`source_snapshot/` 和 `references/` 只是本地参考资料，不属于新模块，**不要**再把它们当作模块底座。

## 目录

```text
engine/                        自研引擎（root shell）
  xjctl.sh                     唯一命令入口（App 只调它）
  xjdaemon.sh                  唯一常驻进程
  boot.sh                      开机启动 + 看护
  lib/env.sh                   路径/配置/JSON
  lib/data.sh                  节点探测/采样/机型信息
  lib/power.sh                 充电功率档
  lib/thermal.sh               温控场景/原厂备份/旁路
  lib/battery.sh               BAA 挂载/PSS
  lib/rear.sh                  背屏控制（单 App 的 LSP overlay）

ToolboxApp/                    Compose Multiplatform App
  app/                         共享 UI + 页面 + RuntimeBridge
  androidApp/                  Android 入口 + LSP hook + RearService
  backdrop/                    玻璃/背景库
  xposed-stubs/                仅编译用 Xposed API stub

dist/
  HyperOS_XJ_New/              新模块源码树（构建/打包输入）
  *.zip / *.apk                构建产物（git 忽略）

docs/                          架构、踩坑、设计规范、交接文档
功能思维导图与大纲.md          页面/功能大纲
页面线框草图.html              页面线框
```

## 快速构建

Windows（本机已验证）：

```powershell
$env:GRADLE_USER_HOME = "D:\gradle"
cd ToolboxApp
.\gradlew.bat :androidApp:assembleDebug --offline
```

产物：

```text
ToolboxApp/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

把它复制到：

```text
dist/HyperOS_XJ_New/app/HyperOS_XJ.apk
```

再按 `docs/开发交接.md` 打包模块 zip。

## 安装

1. 刷入 `dist/HyperOS_XJ_新模块_v3.1.0.zip`（构建产物，git 忽略）。
2. 在 LSPosed 启用 `com.hyperos.xj`，scope 只勾 `com.xiaomi.subscreencenter`。
3. 打开 App → 外观 → 背屏电池卡片。

## 当前架构

```text
App (com.hyperos.xj)
  ├─ UI/页面
  ├─ ToolboxRuntimeBridge ── su -c ──> engine/xjctl.sh
  ├─ RearHook (LSPosed, scope=subscreencenter)
  ├─ RearService / BootReceiver / ControlReceiver
  └─ manifests xposed_init + xposedscope

engine/xjctl.sh
  ├─ power / thermal / battery / setting / bypass / rear
  ├─ PID1 nsenter（BAA 挂载）
  └─ status JSON

engine/xjdaemon.sh（唯一常驻）
  ├─ sample → thermal scene → bypass → power → battery guard
  ├─ rear tick → status snapshot
  └─ 不启动旧 thermal_core / charge_guard
```

## 文档索引

- [AGENTS.md](AGENTS.md)：手机智能体必须遵守的工程约束。
- [docs/架构.md](docs/架构.md)：模块、引擎、App、背屏的完整结构。
- [docs/踩坑记录.md](docs/踩坑记录.md)：已经踩过的坑和不能重复的错误。
- [docs/设计规范.md](docs/设计规范.md)：背屏/页面/配色/尺寸规范。
- [docs/引擎API.md](docs/引擎API.md)：`xjctl.sh` 命令与状态 JSON。
- [docs/开发交接.md](docs/开发交接.md)：当前状态、构建、刷机、真机测试、TODO。
- [docs/项目记忆.md](docs/项目记忆.md)：历史修复记录。

## 当前限制

- 未在真机验证 LSPosed 加载、HyperOS `SubScreenLauncher` 生命周期与背屏渲染。
- App 内 DTBO 按钮仍是占位；DTBO 镜像已随模块提供，但不自动刷分区。
- 公版 PPS / 云控 / APL 目前是配置级，未把旧的原生守护进程搬进新引擎。
- 背屏面板目前是固定 4 项 + 功率光环；字段自选/字号联动还可以继续做。
