# Flovera 测试策略：非真人排查与真人待测

本文档定义 Flovera 的测试取舍。目标不是让个人开发者手动覆盖所有模型、
所有路径和所有体验细节，而是把大部分风险转成可重复的非真人排查，只把少量
真正依赖体验判断的部分留给真人 dogfood。

## 一句话原则

先用确定性测试证明“协议、状态、文件、事件、错误边界没有断”，再用少量真人
测试判断“这个产品是否真的好用”。

## 测试分层

| 层级 | 目的 | 主要手段 | 是否需要真人 |
| --- | --- | --- | --- |
| L0 静态与构建 | 防止代码、资源、manifest、APK 基础破坏 | Gradle compile/assemble、lint-like 检查、`git diff --check` | 不需要 |
| L1 协议等价 | 防止 provider、SSE、tool call、JSON 分片、错误语义破坏 | fake provider、fake stream、golden request/response | 不需要 |
| L2 状态机 | 防止 session、run、timeline、settings、workspace 状态错乱 | JVM/instrumented controller tests | 不需要 |
| L3 产品路径 | 防止核心用户路径断裂 | 真机 instrument、ADB、Compose semantics、workspace 文件断言 | 不需要或少量辅助 |
| L4 真实服务 smoke | 防止真实 provider 基础不可用 | 每个协议族少量真实 API smoke | 不需要长期手测 |
| L5 真人体验 | 判断是否黑箱、难用、绕路、不可理解 | 固定 dogfood 场景 | 需要 |

核心取舍：L0-L4 尽量自动化，L5 少而固定。

## 非真人排查规则

每个新增功能都必须先回答四个问题：

1. 触发条件是什么？
2. 可观察输出是什么？
3. 失败时应该落到什么错误类别？
4. 哪个自动测试能证明它没有断？

如果一个功能只能靠“我点了一下感觉可以”验证，它还没有进入可维护状态。

### 可观察输出

非真人测试必须优先断言这些确定性结果：

| 功能类型 | 优先断言 |
| --- | --- |
| Conversation | message 数量、role、draft/final 区分、timeline event |
| Agent loop | run started/completed/failed/interrupted、tool event、final delta |
| Provider | 请求 URL、headers 摘要、body schema、stream frame、错误映射 |
| Workspace file | 文件存在、内容片段、hash、路径权限、`.flovera` 边界 |
| WebView/HTML | 选中的 workspace path、URL 状态、错误状态、manifest 解析 |
| Python runtime | job status、stdout/stderr tail、exitCode、outputs、timeout |
| Settings | provider/model/API key/search/authority 在运行后是否保持 |
| Snapshot | 文件数量、路径集合、恢复后的新增/删除语义 |
| Search | 命中 path、line、contextLines、隐藏文件权限等级 |
| Error | provider/network/tool/permission/context/unknown 分类 |

不要把截图颜色、文字位置、人工视觉点击作为默认 oracle。视觉检查只用于 UI
打磨阶段，不能作为回归测试主体。

## 非真人测试门禁

### Gate A: 基础构建

适用：任何 Kotlin、Android、资源、Python runtime、manifest 相关变更。

建议命令：

```powershell
rtk powershell -NoProfile -Command "cd android/spike; ./gradlew.bat :app:compileFloveraDebugKotlin :app:compileFloveraDebugAndroidTestKotlin :app:assembleFloveraDebug :app:assembleFloveraDebugAndroidTest"
rtk git diff --check
```

通过标准：

- 编译通过。
- Android test APK 可构建。
- `git diff --check` 无 whitespace error。

### Gate B: Fake Provider / Fake Runtime

适用：provider、SSE、tool loop、final streaming、错误分类、上下文压缩。

必须覆盖：

- 普通文本返回。
- 分段 streaming delta。
- 空 delta。
- provider 中途断连。
- malformed JSON。
- tool call 参数跨 chunk。
- tool 成功、失败、超时、取消。
- 上下文接近阈值触发压缩。
- final message 只持久化一次。

通过标准：

- 不依赖真实 API。
- 失败原因可复现。
- 测试能断言 event 顺序和最终 session 状态。

### Gate C: Controller / Store 状态机

适用：session、workspace、settings、snapshot、run controller。

必须覆盖：

- settings 不被运行覆盖。
- active session 切换正确。
- run 中断后有 interrupted event。
- snapshot restore 后再次保存的文件集合正确。
- workspace search 权限边界正确。
- path link 不存在时显示状态而不是打开空页面。

通过标准：

- 断言数据结构和文件系统结果。
- 不要求人工打开 UI 看结果。

### Gate D: 真机 Instrumented Product Path

适用：UI、生命周期、WebView、Python runtime、Android 权限、安装更新。

优先使用：

```powershell
rtk powershell -NoProfile -File android/spike/scripts/verify-flovera-android.ps1
```

需要单类验证时使用脚本的 `-InstrumentationClass`，例如：

```powershell
rtk powershell -NoProfile -File android/spike/scripts/verify-flovera-android.ps1 -InstrumentationClass com.flovera.app.AgentRunControllerInstrumentedTest
```

规则：

- 默认不卸载主 app。
- 默认不清空用户设置。
- 安装只做 update install。
- 避免视觉点击，优先 ADB、semantics、session store、workspace 文件断言。

通过标准：

- 真机上 APK 可更新安装。
- 目标 instrumentation 通过。
- 运行后 provider/search/authority/API key 设置未被覆盖。

### Gate E: Optional Live Provider Smoke

适用：release 前、provider 适配改动后、SSE 通道改动后。

原则：

- 真实 API 只做 smoke，不做全面回归。
- 每个协议族选 1-2 个代表 provider。
- 不把 API key 写入 workspace 或 git。

当前建议：

| 协议族 | 代表 smoke | 覆盖含义 |
| --- | --- | --- |
| OpenAI-compatible | DeepSeek | 普通文本、SSE、错误映射 |
| OpenAI-compatible 变体 | Qwen 或第三方兼容服务 | baseUrl/model/header 差异 |
| OpenAI Responses | Codex/Responses fake + 可选真实 smoke | request model 差异 |
| Anthropic Messages | fake + 可选真实 smoke | 非 OpenAI tool schema 差异 |
| Gemini/Bedrock | fake 优先 | 高成本 provider 不阻塞主路径 |

DeepSeek live smoke 可通过 verifier 的可选参数进入。没有 key 时必须跳过，而不是失败。

## 黄金场景库

后续应建立 `quality/scenarios/`，每个场景用普通文本记录：

```text
id:
scope:
prompt:
preconditions:
non_human_oracles:
manual_checks:
known_gaps:
```

优先场景：

| 场景 | 非真人 oracle |
| --- | --- |
| 新 workspace 生成 HTML 小工具 | 生成指定文件，WebView selected path 正确 |
| 修改已有文件 | 文件 hash 变化，旧内容被替换，新内容存在 |
| Python 计算并产出文件 | job completed，output 文件存在 |
| workspace_search 查找片段 | 命中 path/line/contextLines 正确 |
| 生成 workspace app | manifest 被发现，local_http status 正确 |
| provider 缺 key | 错误类别为 provider/auth，设置未被修改 |
| 长任务中断 | interrupted event 存在，已完成 tool 保留 |
| 后台/前台切换 | run 状态不丢，stale running 可解释 |
| snapshot restore 再保存 | 文件路径集合符合恢复后的状态 |
| conversation path link | 存在文件可打开，不存在文件显示状态 |

这些场景先服务自动化，再服务真人体验。真人只检查自动化无法判断的部分。

## 真人待测清单

真人测试的目的不是穷举 bug，而是判断产品是否符合人的工作直觉。每轮大功能或
release 前，至少跑下面 8 条。

| 编号 | 真人待测 | 主要判断 |
| --- | --- | --- |
| M1 | 让 agent 从空 workspace 写一个可打开的 HTML 小工具 | 第一闭环是否顺，是否知道入口在哪 |
| M2 | 让 agent 修改自己刚生成的项目 | 是否围绕已有文件迭代，而不是重建一套 |
| M3 | 让 agent 写并运行一个 Python 计算/文档处理脚本 | 产物是否能找到，错误是否可理解 |
| M4 | 打开生成的 workspace app 并交互 | artifact 是否像普通产品，而不是调试面板 |
| M5 | 中途切后台、回来、再中断 | run 过程是否可理解，是否知道下一步 |
| M6 | 故意使用错误 API key 或缺失 key | 错误是否说人话，是否保护已有设置 |
| M7 | 切换 provider/model 做一次短任务 | 设置心智是否清楚，失败边界是否清楚 |
| M8 | 长 conversation 后继续任务 | 上下文统计、压缩和历史是否让人放心 |

扩展体验场景：

| 编号 | 真人待测 | 主要判断 |
| --- | --- | --- |
| M9 | 使用 workspace_search 找文件并让 agent 修改 | 搜索结果是否够用，是否节省沟通 |
| M10 | 恢复旧 snapshot 后再保存 snapshot | 文件管理心智是否正确 |
| M11 | 点击 conversation 里的文件路径 | 跳转是否符合直觉 |
| M12 | 让 agent 生成一个稍复杂的一日项目 | 是否出现绕路、重复看文档、乱建文件 |

真人测试结果只记录三类结论：

```text
pass: 可以继续推进
friction: 能完成但体验别扭，需要 backlog
blocker: 无法完成主要目的，必须修
```

不要把真人测试写成长篇流水账。每条只记录：

- 任务。
- 是否完成。
- 最大摩擦点。
- 需要进入 backlog 的修正。

## Provider 覆盖语义

不要说“全部模型都测试过”。应该使用更准确的状态：

| 状态 | 含义 |
| --- | --- |
| fake-covered | 协议边界由 fake provider 覆盖 |
| smoke-covered | 至少一个真实请求通过 |
| dogfooded | 真人实际使用完成一个任务 |
| unsupported | 当前未承诺 |
| high-cost | 可以实现但不作为个人开发默认验证对象 |

示例：

```text
DeepSeek: fake-covered + smoke-covered + dogfooded
Qwen OpenAI-compatible: fake-covered + smoke-covered
Other OpenAI-compatible: fake-covered
Bedrock: fake-covered / high-cost
```

这种说法比“多 provider 全覆盖”更诚实，也更适合个人开发。

## 发布前最低门槛

一次内部 release 至少满足：

- Gate A 通过。
- 核心 fake provider / fake runtime 测试通过。
- 真机 verifier 通过，且不重装主 app。
- DeepSeek 或当前主 provider live smoke 通过；没有 key 时记录为 skipped。
- 真人 M1、M5、M6 通过或有明确 blocker 修复。
- 新增功能的非真人 oracle 已记录在测试或文档中。

如果时间不够，优先顺序是：

```text
Gate A
  -> fake provider/state tests
  -> 真机核心路径
  -> M1/M5/M6 真人体验
  -> 其他 provider smoke
```

## 后续落地任务

1. 建立 `quality/scenarios/` 黄金场景目录。
2. 为 fake provider streaming/tool/error 建立稳定测试夹具。
3. 让 verifier 输出测试摘要：通过、跳过、live smoke、保留设置检查。
4. 给真人 dogfood 增加极简记录模板。
5. 在 `PRODUCT_QUALITY.md` 中把本策略列为 review gate。
