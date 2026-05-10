# Android Koog 产品化路线

## 目标

把当前 Android Koog agent app 从可运行 spike 推进到可发布产品雏形。

产品定义不是“手机版聊天机器人”，而是：

```text
Android-local agent runtime
  + persistent sessions
  + scoped workspace
  + file/web preview surface
  + configurable model providers
  + explicit tool permissions
  + reproducible verification path
```

第一版产品应当让用户在手机上完成一个闭环：

1. 选择或创建 session。
2. 让 agent 在本地 workspace 内创建、读取、修改文件。
3. 在 app 内查看生成的 HTML/web 文件。
4. 回溯、复制、归档和恢复工作历史。
5. 配置模型、网络能力和项目规则，且配置不进入源码。

## 参考产品抽象

| 产品 | 可复用思想 | 对本项目的落点 |
|---|---|---|
| OpenAI Codex CLI | 本地运行、围绕 workspace 执行任务、CLI/app/cloud 多入口分离 | Android app 只是交互入口，核心 agent/session/workspace 应保持可独立演进 |
| Claude Code | 读取代码库、编辑文件、运行命令、跨 terminal/IDE/desktop/web 多表面 | 同一套 agent 能力以后可以被 conversation、web preview、文件面板复用 |
| OpenCode | 明确工具集、权限确认、日志页、可配置 provider/self-hosted model | tool 调用需要有权限模型、日志/事件模型和 provider 配置边界 |
| Aider | repo map、chat mode、architect/editor 分工、明确 edit format | Android 端要补 workspace context map、ask/code 模式和更稳定的文件编辑协议 |

参考资料：

- OpenAI Codex CLI README: <https://github.com/openai/codex/blob/main/README.md>
- Claude Code overview: <https://code.claude.com/docs/en/overview>
- OpenCode README: <https://github.com/opencode-ai/opencode>
- Aider repo map: <https://aider.chat/docs/repomap.html>
- Aider chat modes: <https://aider.chat/docs/usage/modes.html>
- Aider edit formats: <https://aider.chat/docs/more/edit-formats.html>

## 当前状态

已经具备：

- Android app 引入 Koog agent runtime。
- session 可创建、持久化、回溯、复制、归档、置顶、重命名。
- workspace 有文件读写工具和树形文件浏览。
- WebView 已成为主展示页，可选择 workspace 内 HTML 文件打开。
- provider 配置已从源码中分离，支持多 provider 基础结构。
- AGENT.md 从 workspace 内读取。
- network 工具有开关，支持 `fetch_url` 和 `download_file`。
- conversation 支持 Markdown、工具调用缩略、长消息折叠和真机验证路径。

主要缺口：

- agent loop 和 UI 状态仍在一个 Controller 里，职责过宽。
- workspace context 只靠最近消息和 AGENT.md，缺少结构化 workspace map。
- 工具权限还只是 network 开关，没有按工具/路径/风险分级。
- 文件编辑协议偏工具调用，没有可审计 patch/diff 视图。
- WebView 只是展示，还没有和 session/tool event 建立稳定联动。
- 缺少 release build、签名、版本号、隐私说明、崩溃日志和升级策略。

## 目标架构

```text
ui/
  Conversation surface
  Workspace file surface
  Web preview surface
  Settings surface

app/
  AgentController
  SessionController
  WorkspaceController
  PermissionController

domain/
  AgentRun
  Session
  Workspace
  ToolEvent
  ProviderConfig

runtime/
  KoogAgentRuntime
  ToolRegistry
  WorkspaceContextBuilder
  ProviderClientFactory

storage/
  SettingsStore
  SessionStore
  WorkspaceStore
  EventLogStore
```

设计原则：

- UI 不拥有 agent loop 生命周期。
- Controller 不直接堆积所有业务逻辑。
- session 是可回放的历史，不只是聊天列表。
- workspace 是产品核心资产，WebView 只是其中一个展示方式。
- tool call 必须能被记录、缩略、展开、必要时要求确认。
- provider/API key/settings 必须和源码、workspace 内容隔离。

## 产品化阶段

### P0: 稳定现有闭环

目标：现有功能在真机上稳定，不因关闭界面、切换 session、长消息或工具调用而卡死或丢状态。

验收：

- 关闭 conversation 不会中断 running loop。
- 长 conversation 打开、滚动、回溯不卡死。
- session 切换、归档、复制、重命名后 active session 明确。
- 工具调用结果可追溯。
- 所有配置仍从 settings/env/store 读取，无硬编码密钥。

### P1: 拆职责

目标：把当前单 Controller 拆成可维护边界。

优先顺序：

1. `SessionController`: session CRUD、active session、archive/pin/rename/copy/revert。
2. `WorkspaceController`: file tree、HTML selection、share/open、AGENT.md。
3. `AgentRunController`: running state、draft、tool events、submit/cancel。
4. `SettingsController`: provider/model/API key/network/max iterations。

验收：

- UI 只依赖界面所需 state。
- agent loop 不因 Compose 生命周期变化被取消。
- 每个 controller 有对应 instrumentation 或 JVM test。

### P2: Workspace context map

目标：让 agent 更像 Codex/Aider，而不是只看最近聊天。

实现：

- 扫描 workspace 文件树。
- 摘要 HTML/CSS/JS/MD/JSON 文件。
- 生成 compact workspace map。
- 每次 run 将 AGENT.md、最近消息、workspace map 一起传入。

验收：

- agent 能知道已有文件，不重复创建无关版本。
- 大 workspace 不一次性塞全量文件。
- map 有 token/字符预算。

### P3: 权限和审计

目标：工具调用可控、可解释、可回放。

实现：

- 工具按风险分级：read、write、network、share/open、delete。
- 用户可设置默认策略：always allow、ask each time、deny。
- tool event 持久化为独立 event log，而不是只挂在 assistant message 上。
- 高风险工具调用前弹确认。

验收：

- network 开关升级为工具权限模型。
- 写文件、下载文件、分享/打开外部 app 都有记录。
- session 回放能看到关键工具行为。

### P4: Web preview 产品化

目标：WebView 成为 workspace 的稳定展示层。

实现：

- 最近打开 HTML 持久化。
- HTML 文件选择和文件树统一。
- WebView 错误页、刷新、前进/后退、外链策略。
- agent 生成 HTML 后可提示打开或自动切换到最新 HTML。

验收：

- 空状态、加载失败、文件不存在都有明确 UI。
- workspace 内相对资源路径可正常加载。
- 不暴露地址栏，不泄漏内部 file path 给普通用户。

### P5: Release readiness

目标：准备可安装、可测试、可分发的内部版本。

实现：

- release build type、签名配置说明。
- versionName/versionCode 策略。
- 隐私说明：API key、本地文件、网络访问。
- crash/log 导出入口。
- 基础 smoke test 脚本。

验收：

- `assembleRelease` 可重复执行。
- 不把 `.env`、`settings.json`、API key、签名文件提交进 git。
- README 能指导安装、配置 provider、创建第一个 workspace。

## 下一步建议

先做 P0/P1，不急着做更多功能。

最短路径：

1. 把 `AgentController` 拆出 `SessionController`。
2. 把 `WorkspaceController` 拆出，减少 conversation 重组。
3. 给 agent run 加 cancel/continue/error 状态模型。
4. 再做 workspace context map。

原因：当前功能已经能演示产品方向，但 Controller 继续膨胀会让后续权限、预览、上下文、发布配置全部堆在一起，维护成本会快速上升。
