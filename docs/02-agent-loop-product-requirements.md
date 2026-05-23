# Agent Loop 产品要求与实现路径

本文档固定 Flovera 下一阶段 agent loop 的产品要求和实现路径。这里的
loop 不是单纯的 `while(model asks tool) run tool`，而是用户能信任、能中断、
能恢复、能定位问题的产品级执行系统。

## 一句话目标

把 Flovera 的 agent loop 从“调用一次 Koog 黑箱并等待结果”推进到“可观察、
可恢复、可测试、可逐步替换运行时的本地 workspace agent loop”。

## 为什么必须做

当前体验里的核心问题不是模型能力本身，而是用户看不到运行过程的结构：

```text
用户输入任务
  -> UI 显示正在运行
  -> 中间发生模型推理、工具调用、文件读写、网络请求、上下文压缩
  -> 如果成功，最后出现一段回答
  -> 如果失败，只看到笼统错误或日志文件路径
```

这会导致三个产品问题：

1. 用户无法判断 agent 是在认真工作、卡住、绕路，还是已经失败。
2. 用户中断任务后，看不到已完成部分、未完成部分和可继续入口。
3. 开发者调试时只能追日志，无法从会话本身复盘 loop 的行为。

因此，产品级 loop 的目标不是炫技，而是把执行过程变成可被用户理解的事件流。

## 当前边界

截至 `android: add agent run streaming substrate` 提交，Flovera 已经具备这些基础：

- `AgentRunEvent` 事件总线入口。
- `AgentRunTimelineEvent` 会话持久化。
- 工具调用进度可以进入会话 timeline。
- final assistant draft 可以通过 `FINAL_TEXT_DELTA` 事件逐步更新。
- Koog runtime 接口上已经有 `runStreaming(...)` 扩展点。
- workspace artifact 已经有 local HTTP / Python HTTP runtime 基座。

但核心 loop 仍然有一个关键边界：

```text
Flovera UI / Controller
  -> AgentRunController
  -> AgentRuntime.runStreaming(...)
  -> KoogAgentRuntime
  -> Koog AIAgent.run(...)
  -> String final answer
```

也就是说，Flovera 已经准备好了事件承载层，但 Koog 默认 `AIAgent.run(...)`
仍然是返回最终字符串的路径。只要继续走这条路径，模型 token 级流式输出、
tool call delta、模型和工具之间的细粒度 interleaving 都无法被完整捕获。

## 产品定义

产品级 loop 应该让用户看到这样的过程：

```text
User task
  -> thinking/status
  -> assistant narration delta
  -> tool started
  -> tool progress/result
  -> assistant narration delta
  -> context/compression checkpoint if needed
  -> next tool or final response
  -> completed / failed / interrupted with recovery options
```

注意：这里的 `thinking/status` 不是暴露模型隐藏思维链。它是产品状态，例如：

- 正在分析 workspace。
- 正在读取文件。
- 正在运行 Python。
- 正在等待模型继续。
- 正在压缩上下文。
- 网络请求失败，准备停止。

不应该展示未经许可的 chain-of-thought，也不应该伪造“思考过程”。

## 必须满足的产品要求

### 1. 可观察

用户必须能从会话里看到 loop 的关键事件：

| 事件 | 用户需要看到什么 | 开发者需要记录什么 |
| --- | --- | --- |
| run started | 任务开始、使用的 provider/model | run id、session id、settings 摘要 |
| context checked | 上下文估计与是否接近压缩阈值 | token/字符估计、消息范围 |
| compression | 是否发生压缩、压缩后状态 | 压缩前后消息边界 |
| model streaming | assistant 文本逐步出现 | delta、完成原因 |
| tool call | 工具名、输入摘要、状态 | 完整参数、权限判定、耗时 |
| file mutation | 修改了哪些文件 | path、操作、快照/patch 摘要 |
| network | 访问了哪里 | URL、状态码、错误类型 |
| failure | 失败类别和下一步选择 | stack/log id、可恢复性 |
| interruption | 已完成与未完成边界 | cancel source、最后事件 |

### 2. 可中断

用户中断不应该等价于“丢掉整个 run”。中断后会话应当保留：

- 中断前已经输出的 assistant draft。
- 已经完成的工具调用。
- 正在执行但被取消或失去连接的工具调用。
- 文件已经发生的变更。
- 是否可以继续、重试或回滚的提示。

中断后的展示建议：

```text
Run interrupted
Completed:
- read_file README.md
- edit_file src/app.js
Interrupted:
- python_run npm test
Next:
- continue from current workspace
- retry failed command
- inspect changed files
```

### 3. 可恢复

恢复不是魔法恢复任意进程，而是恢复产品语义：

- 对已经完成的 tool call 不重复执行，除非用户明确重试。
- 对未完成工具标记为 interrupted/unknown。
- 从当前 workspace 状态继续，而不是假设回到旧状态。
- 对危险操作依赖快照、diff 或权限记录做回滚。

Flovera 不应承诺 Android 杀进程后透明恢复 Python 栈帧、socket、线程和模型连接。
应承诺的是：恢复会话、恢复文件状态视图、恢复事件边界，并给出下一步。

### 4. 可测试

不能依赖真实 API 才能验证 loop。必须有 fake provider 覆盖：

- 普通文本流式输出。
- 空 delta。
- 多段 final answer。
- tool call JSON 分片。
- tool call 参数跨 chunk 拼接。
- malformed tool call。
- provider 中途断连。
- tool 成功、失败、超时、取消。
- context 接近阈值触发压缩。
- 用户中断发生在模型流式输出中间。
- 用户中断发生在工具运行中间。

真实 API 只做 smoke test，不作为基本回归测试前提。

### 5. 不伪造

如果底层 runtime 只返回最终字符串，UI 可以一次性显示最终结果，但不能把最终字符串
拆成假 delta 冒充模型流式输出。

允许的行为：

```text
Koog returns final String
  -> append one final assistant message
```

不允许的行为：

```text
Koog returns final String
  -> timer splits the String
  -> UI pretends the model streamed it
```

原因是伪流式会掩盖真实瓶颈，无法暴露 provider/tool loop 的时序问题。

## 用户体验要求

Conversation 不应该只是“用户一句，assistant 一句”。更合适的结构是 run timeline：

```text
User
  "实现一个本地聊天 demo"

Agent run
  Status: checking context
  Status: reading files
  Tool: list_files workspace
  Tool: read_file AGENT.md
  Assistant: "我会先建立普通 Web 项目结构..."
  Tool: write_file demo/index.html
  Tool: write_file demo/app.js
  Status: running verification
  Tool: python_run ...
  Assistant final: "已完成，入口是 demo/index.html"
```

UI 层需要区分四种内容：

1. 用户消息。
2. assistant 自然语言。
3. 运行事件和工具事件。
4. 错误、恢复和系统状态。

这样中断后也能保留结构：

```text
Agent run
  Status: reading files
  Tool: read_file ...
  Tool: python_run ...
  Interrupted by user
  Recovery: continue / inspect / retry
```

## 实现路径

### L0: 保持 Koog，补齐事件边界

目标：不改 Koog，不自研 loop，只把 Flovera 的事件承载层稳定下来。

已完成基础：

- `AgentRunEvent`。
- final response draft surface。
- run timeline 持久化。
- tool progress narration baseline。

还需要补齐：

- 统一 run started / completed / failed / interrupted 事件。
- 错误分类：provider、network、tool、permission、context、unknown。
- UI 对同一 run 的事件分组，而不是散落成普通消息。
- 日志文件路径可点击打开。

验收：

- 没有真实 API 时，fake runtime 能稳定生成 timeline。
- 用户中断后，会话保留 interrupted 事件。
- 同一次 run 的工具、状态、assistant draft 能被 UI 归为一组。

### L1: Provider 流式输出直连到事件总线

目标：先解决 final assistant response streaming，不急着自研完整 tool loop。

做法：

```text
Flovera provider client streaming API
  -> StreamFrame / delta
  -> AgentRunEvent(FINAL_TEXT_DELTA)
  -> AgentRunController draft
  -> Compose conversation surface
```

要求：

- 只在 provider/runtime 真实给出 delta 时发 `FINAL_TEXT_DELTA`。
- 对不支持 streaming 的 provider，仍然走最终字符串。
- DeepSeek/OpenAI-compatible 优先，因为普通 API provider 覆盖面最大。
- 不在 UI 层猜测 chunk。

验收：

- fake streaming provider 的 delta 能逐步显示。
- connection abort 后，draft 保留，run 标记 failed/interrupted。
- 最终持久化时只保存一条 assistant message，不重复保存 draft 碎片。

### L2: Flovera-owned OpenAI-compatible tool loop

目标：实现一个最小但产品级的自有 loop，先覆盖 OpenAI-compatible 请求/响应族。

最小 loop 结构：

```text
messages + tools
  -> provider stream
  -> text delta -> UI
  -> tool_call delta accumulate
  -> finish_reason = tool_calls
  -> execute allowed tools
  -> append tool results
  -> repeat until final text or stop condition
```

必须实现：

- tool schema 生成。
- tool call delta 拼接。
- tool 参数 JSON 容错与错误反馈。
- max iteration 变成产品级防护，不再是硬编码 20 次失败。
- context 估计与压缩 checkpoint。
- tool result 截断和完整日志保存。
- cancel token 贯穿模型请求和工具执行。

不要求一开始覆盖：

- 所有 provider 私有格式。
- 并行 tool call。
- 多 agent 协作。
- 复杂 planner/executor 架构。

验收：

- fake OpenAI-compatible provider 可完整模拟：文本、工具、继续文本、最终答案。
- 工具失败时，模型能收到结构化错误并决定继续或结束。
- 用户取消时，不再追加伪 final answer。
- 每个 iteration 都有事件记录。

### L3: 上下文、压缩和 run 分段

目标：上下文统计从“按对话回合粗算”变成“按 run event/message/tool result 精确估计”。

实现：

- 以 message、tool result、timeline event 为估计单位。
- 对大工具输出保存完整日志，但传给模型的是摘要和可读取路径。
- 压缩边界优先放在 tool result 之后，而不是工具调用中间。
- 压缩事件写入 timeline。

验收：

- 上下文百分比随会话增长正常变化，不长期停留在异常低值。
- 大文件读取不会一次性污染全部上下文。
- 压缩后仍能解释哪些内容被压缩。

### L4: 恢复、重试和快照

目标：让失败和中断变成可继续处理的产品状态。

实现：

- run checkpoint：last completed event id、last completed tool id。
- 文件变更摘要与可选快照关联。
- retry failed tool。
- continue from current workspace。
- open changed files。
- copy diagnostics。

验收：

- Android 后台杀进程后，重启显示 stale running run 为 interrupted。
- 用户能看到中断前已完成工具。
- 对文件修改类任务，能看到 changed files。

### L5: Provider 扩展与 Koog 去留决策

目标：在自有 loop 稳定后，再决定 Koog 是保留为 fallback，还是仅作为部分能力来源。

优先顺序：

1. OpenAI-compatible / DeepSeek / Qwen 类普通 API。
2. OpenAI Responses / Codex Responses。
3. Anthropic Messages。
4. Gemini API。
5. Bedrock / Cloud Code Assist 等低优先级或高成本 provider。

决策标准：

- 如果 Koog 能暴露足够事件和 streaming hook，就继续复用 Koog。
- 如果 Koog 只能返回 final string，Flovera 自有 loop 应成为主路径。
- 不为低使用面的 provider 牺牲主路径可观察性。

## 自研 loop 的难点

loop 表面上不难，难的是产品级边界：

| 难点 | 为什么难 | Flovera 需要做到什么程度 |
| --- | --- | --- |
| streaming tool call | 参数可能跨 chunk、JSON 不完整 | OpenAI-compatible 先精确支持 |
| 错误恢复 | 模型、网络、工具、权限错误语义不同 | 分类展示和结构化回传 |
| 上下文管理 | 工具输出可能巨大 | 摘要入上下文，完整内容留文件 |
| 取消 | 可能发生在模型请求或工具执行中间 | cancel token 和 interrupted 状态 |
| 幂等 | 重试可能重复写文件或调用网络 | 先以文件快照和用户确认兜底 |
| 多 provider | 协议相似但细节不同 | 以 OpenAI-compatible 为底座逐步适配 |
| 测试 | 真实 API 不稳定且成本高 | fake provider 行为测试为主 |

这也是 Koog 这类框架有价值的地方：它处理了大量边界。但如果框架不暴露产品所需的
事件粒度，Flovera 必须在主路径上拥有自己的 loop。

## 不做什么

- 不展示模型隐藏思维链。
- 不伪造流式输出。
- 不承诺 Android 进程死亡后恢复任意 Python 执行现场。
- 不一次性重写所有 provider。
- 不把 max iterations 当成用户可见失败的主要机制。
- 不把日志文件当成唯一错误解释。

## 推荐近期任务顺序

```text
1. UI run 分组和 run started/completed/failed/interrupted 事件
2. 错误分类与日志路径点击
3. DeepSeek/OpenAI-compatible final text delta 接入 AgentRunEvent
4. fake streaming provider 测试
5. 最小 OpenAI-compatible tool loop spike
6. context 估计和压缩边界重做
7. 中断/恢复/重试产品化
```

这条路径的原因：

- 先提升可观察性，能马上改善用户信任。
- 再接真实 streaming，避免 UI 做假效果。
- 最后替换 loop，避免一上来重写 Koog 带来大面积回归。

## 验收总门槛

进入“产品级 loop baseline”前，至少满足：

- fake provider 覆盖模型流式、工具调用、失败、中断。
- 真机上运行长任务时，conversation 能持续展示进度事件。
- 用户中断后，历史里能看到中断前发生了什么。
- final response 可以真实流式显示，或者明确降级为一次性显示。
- 工具调用次数不再因为固定 20 次硬失败，而是有可解释的运行预算/保护策略。
- 所有失败都能落到可读错误类别，并提供下一步操作。

