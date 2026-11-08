# AGENTS.md

> 给手机智能体/后续开发者：先读完本文件，再读 `docs/开发交接.md`、`docs/架构.md`、`docs/踩坑记录.md`、`docs/设计规范.md`、`docs/引擎API.md`。

## 不可违反的约束

1. **单 App**：`com.hyperos.xj` 同时是控制端 UI 和 LSP 背屏模块。不要再创建 `com.hyperos.xj.rear` 之类的第二个 App。
2. **新模块**：模块底座是 `dist/HyperOS_XJ_New/`。不要把 `source_snapshot/` 的旧 `thermal_core.sh`、`battery/scripts/*`、旧 WebUI、旧 `rearscreen.sh`/`rs.apk` 复制进新模块。
3. **不碰原生背屏时钟**：背屏走 `SubScreenLauncher.onResume` 叠加 `PanelView`；不要 `pm disable com.xiaomi.subscreencenter`，不要默认 `am display move-stack`/`activity_task` 推任务。
4. **不自动刷 DTBO**：DTBO 镜像是危险操作，App 按钮目前是占位；需要用户明确确认后才能设计刷入流程。
5. **Xposed stub 只编译**：`xposed-stubs` 必须 `compileOnly`，不能打进 APK。
6. **配置跨进程**：hook 在 `subscreencenter` 进程，配置通过 `rear.conf`（外部目录 + `/data/local/tmp` 兜底）传递。

## 构建

```powershell
$env:GRADLE_USER_HOME = "D:\gradle"
cd ToolboxApp
.\gradlew.bat :androidApp:assembleDebug --offline
```

Linux/macOS：

```bash
cd ToolboxApp
./gradlew :androidApp:assembleDebug --offline
```

## 打包检查

- `module.prop` 在 zip 根目录。
- `META-INF/com/google/android/update-binary` 存在且权限 0755。
- 所有 `.sh` 过 `bash -n`。
- `app/HyperOS_XJ.apk` 来自最新构建。
- 新模块里只有一个 APK。
- 不包含旧 `thermal_core.sh`、`battery/scripts`、`webroot`、`hyperosui`、`rearscreen/`。

## 改动位置

- 引擎命令/参数：`engine/xjctl.sh`
- 守护循环：`engine/xjdaemon.sh`
- 功率：`engine/lib/power.sh`
- 温控：`engine/lib/thermal.sh`
- 解容/BAA/PSS：`engine/lib/battery.sh`
- 背屏控制：`engine/lib/rear.sh`
- App 数据桥：`ToolboxApp/app/src/commonMain/kotlin/com/hyperos/xj/runtime/ToolboxRuntimeBridge.kt`
- Android 实际实现：`ToolboxApp/app/src/androidMain/.../ToolboxRuntimeBridge.android.kt`
- 页面：`ToolboxApp/app/src/commonMain/kotlin/com/hyperos/xj/pages/`
- LSP hook：`ToolboxApp/androidApp/src/main/java/com/hyperos/xj/rear/`
- 模块安装/启动/卸载：`dist/HyperOS_XJ_New/{customize,service,post-fs-data,uninstall,action}.sh`

**同步规则**：`engine/` 是唯一编辑源；`dist/HyperOS_XJ_New/engine/` 是打包副本。改完根 `engine/` 后必须同步，`scripts/package_module.ps1` / `.sh` 会自动做。

## 当前 TODO

- 真机验证 LSPosed 加载、背屏叠加、开关隐藏。
- 把背屏四角字段/字号从 App 的 `LookPage` 写进 `rear.conf`。
- DTBO 刷入流程（需二次确认 + 备份）。
- 公版 PPS / 云控 / APL：决定继续配置级还是移植原生逻辑。
- 真机日志采集：`/data/local/tmp/xj_rear.conf`、`/data/adb/modules/HyperOS_XJ/log/`。
