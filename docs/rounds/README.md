# Development Rounds

本目录定义按提交计算的开发轮次流程。

## 核心规则

- 一轮等于一个非平凡 commit。
- 每轮只能有一个主目标。
- 每轮开始前必须更新 `current-round.md`。
- 每轮结束前必须更新 `round-timeline.md`。
- 稳定、可继承的软件工程结论写入 `development-findings.md`。
- 新想法先写入 `idea-backlog.md`，不得直接偷渡进当前轮。
- 小型错别字或纯格式修正可以标记为 `trivial`，不要求完整轮次。

没有轮次记录的非平凡 commit，视为流程不完整。

## 文件职责

| 文件 | 职责 |
|---|---|
| `current-round.md` | 当前轮次控制面，定义本轮目标、边界、验收和计划提交。 |
| `round-timeline.md` | 按时间记录每轮提交、目标、结果和后续影响。 |
| `development-findings.md` | 记录有证据来源、可跨轮继承的软件工程结论。 |
| `idea-backlog.md` | 收纳暂不进入当前主线的新想法。 |
| `archive/` | 可选归档目录；第一版不强制每轮创建完整归档。 |

## Current Round 模板

```md
# Current Round

## Round Goal
一句话说明本轮要推进什么。

## Why Now
说明为什么现在做这一轮，而不是做别的。

## Scope
本轮允许修改什么。

## Non-Goals
本轮明确不做什么。

## Acceptance Criteria
- [ ] 条件 1
- [ ] 条件 2
- [ ] 条件 3

## Planned Commit
`type: message`

## Notes
执行中发现但不应偷渡进本轮的事项。
```

## Timeline 模板

```md
## YYYY-MM-DD - commit-hash - commit message

- 目标：
- 结果：
- 验收：
- 后续影响：
```

当前轮次还没提交时，`commit-hash` 可以临时写 `pending`。提交完成后，下一轮维护时补齐或保留为 bootstrap 记录。

## Findings 模板

```md
## Finding N

- 来源轮次：
- 结论：
- 证据：
- 对后续开发的影响：
```

只有有明确轮次或验收证据的结论，才能写入 `development-findings.md`。

## Backlog 模板

```md
## Item N - 标题

- 想法：
- 为什么不进当前轮：
- 进入主线的条件：
```

Backlog 是边界保护工具，不是待办清单。当前轮未收口前，不得把 backlog 条目直接变成实现范围。
