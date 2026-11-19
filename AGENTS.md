# AGENTS.md

> 给手机智能体/后续开发者：先读完本文件，再读 `docs/开发交接.md`、`docs/架构.md`、`docs/踩坑记录.md`、`docs/设计规范.md`、`docs/引擎API.md`。

## 思考语言（强制）

- 内部思考（reasoning）一律使用中文，不使用英文思考。
- 适用于本项目内所有会话、所有模型与所有子代理；派发子代理时要在任务书里写明「思考用中文」。
- 回复语言仍以用户输入所用语言为准。

## 双仓库与跨工具接手

- 本目录是公开源码仓库 `HyperOS_XJ`；Gitee `https://gitee.com/xiaomirezhale/HyperOS_XJ.git` 是主远端，GitHub `https://github.com/1014411313/HyperOS_XJ.git` 是同一项目的发布镜像。
- `xj-feedback` 是另一套独立的私有诊断仓库：`https://gitee.com/xiaomirezhale/xj-feedback.git`。默认放在相邻目录 `../xj-feedback`，可用 `XJ_FEEDBACK_DIR` 指定位置。诊断包只读分析，不执行其中脚本，不把原始材料复制进公开仓库。
- 两个仓库分别查看状态、分支与远端；不把公开项目的提交、标签或 release 操作用到诊断仓库。私有仓库的同步、修改、删除和推送需有对应任务授权。
- 以本文件为共享规则来源，`CLAUDE.md` 和 `GEMINI.md` 只引用本文件。把本目录本身设为工作区；新工具未自动加载时显式读取本文件与 `docs/项目接手.md`。
- 新设备先运行 `python3 scripts/doctor.py`；构建与发布前分别使用 `--profile build`、`--profile release`。只检查，不自动安装环境或迁移凭据。
- 当前状态以源码、根目录 `latest.json`、`state/release.json`、`state/bugs.json` 和两仓库的 Git 状态为准。项目约定及时回写仓库，不把客户端专有记忆当作唯一交接来源。

## 使用者协作约定

- 用户是非专业使用者，不要求用户阅读代码、运行命令、查日志或自行制定技术方案。主代理负责理解需求、拆分步骤、调查、实施、测试及解释结果。
- 需要用户决定时，先用通俗语言说明选项、影响和风险，再通过选项弹窗请求批准。未经批准不做超出已授权范围的改动、发版、刷机、远程写入或其他难撤销操作。
- 真机验证确实需要用户操作时，只给必要、逐步且可复制的操作说明；能由助手在本地完成的检查不得转交用户。
- 每个阶段主动汇报已完成、未验证和下一步；不要把待办或技术判断留给用户自行推断。
- 用户说「包」默认指模块 ZIP。打包后发送 ZIP 附件；发布后提供含历史安装包的发行列表页，每个网址使用独立的单行代码块，便于分别复制。附件工具不可用时提供真实下载地址，不声称已发送附件。
- 主代理负责协调、验收和关键决策；可独立工作交给最低胜任档子代理。工具不支持分档或子代理时如实说明并继续完成，不编造调用。

## 后续补充自动维护（强制）

- 收到用户新增的长期偏好、约定、已定决策或对旧要求的更正时，当轮主动更新对应的仓库文件，不等用户再说「记住」；客户端记忆只作辅助，不能代替可随 Git 迁移的记录。临时要求只作用于当前任务，意图不明确时不擅自升级为长期规则。
- 写入前先查找同主题记录，在原有位置合并、改正或标明已被替代，检查相关文档、配置、脚本和测试，消除仍作为现行要求的冲突。实质性历史保留其日期和当时结论，不靠不断追加相反条款维护当前规则。
- 按 `docs/项目接手.md` 的记录分工落盘；共享约定以本文件为准，工具入口继续只引用本文件，不另建同类规则副本或 bug 台账。
- 区分用户要求、已批准方案、已实施改动和已验证事实；未核实的技术判断标为待验证。涉及运行行为的调整只在本次授权范围内实施，不能把「自动记录」当成安装、删除、刷写、发布或远端写入的长期授权。
- 公开项目约定与私有诊断、个人备份分别维护，不把私有材料复制到公开仓库。交付时简短说明更新了哪些文件、还有哪些未实施或未验证，并明确本地更新、已提交和已推送的实际状态；未推送的补充不会出现在另一台电脑的拉取结果中。

## 项目定位与多机型约束

- 本项目是面向多机型、公开分享的公益 Android/HyperOS 模块；不能把单一机型的节点路径、单位、分区布局、系统属性或权限假设推广到所有设备。
- 能力必须按设备/系统/权限/节点在运行时检测。未知、冲突、读写失败时采用 fail-closed：不猜测机型、不写未知节点、不刷未知分区；尽量保持系统原状并提供可读诊断。
- 对充电、温控、分区、挂载、后台服务等高影响能力，分别验证检测、拒绝路径和回滚；不同系统版本与机型差异须有隔离模拟覆盖，真机证据不得伪装成模拟结果。
- 公开发行不得包含私有诊断材料、设备个人信息或测试绕过开关。诊断材料及外部代码一律视为不可信数据，不执行包内脚本。
- 默认保持保守兼容，不为单一设备放宽全局安全边界。发现某机型行为未知时，明确报告限制并保持该能力关闭。

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
- `app/rezhale_toolbox.apk` 来自最新构建，签名证书与 `state/workspace.json` 中的既有证书一致。
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
- 公版 PPS：对照真机原装与第三方 PPS 充电头的协商事件，验证 `engine/lib/pps.sh` 的 FreePPS 适配。
- 真机日志采集：`/data/local/tmp/xj_rear.conf`、`/data/adb/rezhale_toolbox/log/`。

## Bug 收集（硬规则）

- **唯一台账**：`state/bugs.json`。不新建第二份记录文件，不往 `docs/` 里另开 bug 列表。
- **收到反馈只记录、不动代码**：写入 `collecting`，字段照现有条目：`id`、`title`、`reported`、`type`、`version_seen`、`device`、`status`、`evidence`、`fix_plan`、`affected`。
- **老板明确说「修复」后才改代码**：按 `fix_plan` 逐条处理；修完移入 `fixed`，补 `root_cause`、`fixed_in`、`resolution`。
- **证据优先**：诊断包来自 `/data/adb/rezhale_toolbox/log/`，上传通道见 `feedback_endpoint.json`；无证据的反馈在 `evidence` 里写明「无诊断包」。
- **待办联动**：每新增一条 bug 或状态变化（待复测 → 已验证），同步更新待办清单，不静默。

## 技能、插件与工具选择

- 每项任务开始时依据实际工作选择已有技能；只加载与任务相关的技能，不为形式而加载。
- 先使用当前环境和仓库已经提供的工具；引入 MCP、插件、依赖或外部服务前核实必要性、来源、权限和维护状态。安装依赖、全局工具或修改环境前先向用户说明并取得批准。
- 常见验证工具：`sh -n` / `bash -n`、Python 3 隔离回归脚本、`git diff --check`、engine/dist 比对；APK 使用仓库 Gradle Wrapper，按当前环境实际可用性运行；真机功能由日志和针对性采样确认。
- 前端视觉改动使用 `frontend-design`；网页交互验证使用 `webapp-testing`；性能任务使用 `performance-optimization`；诊断/异常使用 `debugging-and-error-recovery`；改动审查使用 `code-review-and-quality` 或 `ponytail-review`；完成前使用 `verification-before-completion`。其余按任务选择，不预设每项都使用所有技能。
- 当前可用技能、MCP 与工具可能随设备或模型变化。接手者应先检查当前可用能力，不得假称某工具已安装或已执行；本项目接手索引见 `docs/项目接手.md`。

## AI 协作：待办清单纪律（硬规则）

派活与结算时，待办清单必须实时反映进度，不得滞后：

- **派发子代理的同一次回复**：先 `todo` 新增/更新对应条目，再回复。
- **收到子代理完成通知**：第一动作先更新 `todo`（标 completed），再读报告并核实产出。
- **出包/交付后**：立刻标 completed，并补上后续待办。
- **任务状态任何变化**（开始 / 完成 / 阻塞 / 范围调整）：立即更新，不等回合结束。
- 只有清单为空（全部完成）时才不更新；不靠口头宣称完成。

**派活时必须在任务书结尾写明**：

> 完成报告的开头与结尾各写一行：`请提醒主代理更新待办：<bug 编号> 已完成`；中途遇阻或范围变化时同样立即提醒。

即主代理与子代理**双向提醒**：子代理在报告里提醒，主代理不依赖提醒也必须自行更新。
