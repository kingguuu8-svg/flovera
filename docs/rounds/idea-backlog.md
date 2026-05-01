# Idea Backlog

本文件记录暂不进入当前轮的新想法。

Backlog 条目不是当前待办。进入主线前，必须先成为新的 `current-round.md` 目标。

## Item 1 - 轮次自动化脚本

- 想法：增加脚本生成新轮次、归档当前轮次、检查 timeline 和 commit 是否一致。
- 为什么不进当前轮：第一版只做文档模板和流程规则，避免过早固化自动化实现。
- 进入主线的条件：连续多轮手工维护后确认字段稳定，且手工流程成为明显成本。

## Item 2 - 统一 SSH identity 命名

- 想法：把兼容名 `id_ed25519` 逐步统一为语义名，例如 `vm_ssh_identity.pem`。
- 为什么不进当前轮：这会影响脚本、Android spike 输入路径和验收文档，不属于轮次流程文档落地。
- 进入主线的条件：启动“路径和命名统一”轮次，并先确认兼容策略。

## Item 3 - 第一版 sandbox threat model

- 想法：为当前 QEMU/Android spike 路线写一份威胁模型，明确能防什么、不能防什么、哪些只是工程隔离而非安全承诺。
- 为什么不进当前轮：本轮只定义设计目标和参考案例，不扩展到安全证明。
- 进入主线的条件：guest 工作机 runtime 固化后，先写清第一阶段真实安全边界。

## Item 4 - Guest agent 安装和启动策略

- 想法：定义 agent 或 Codex 兼容 worker 如何进入 guest 镜像、如何启动、如何写日志、如何绑定 `/workspace`。
- 为什么不进当前轮：本轮只统一仓库表述，不修改镜像构建或 Android spike。
- 进入主线的条件：启动“固化 guest 镜像和 agent”轮次。
