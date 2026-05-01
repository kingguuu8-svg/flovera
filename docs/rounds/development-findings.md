# Development Findings

本文件记录可跨轮继承的软件工程结论。

只有满足以下条件的内容才能写入：

- 有明确来源轮次、commit、验收记录或架构文档。
- 对后续开发决策有约束作用。
- 不是单次调试现象、临时 workaround 或未经验证的直觉。

## Finding 1

- 来源轮次：2026-05-01 `docs: add development round workflow`
- 结论：仓库后续非平凡开发应按“一个 commit 一个轮次”推进。
- 证据：本轮建立 `current-round.md`、`round-timeline.md`、`development-findings.md` 和 `idea-backlog.md`，并把规则写入仓库处理流程。
- 对后续开发的影响：后续功能、修复、架构整理都必须先定义本轮目标、边界和验收，再提交。

## Finding 2

- 来源轮次：2026-05-01 `docs: define sandbox design targets`
- 结论：本项目沙箱应优先复用成熟工程中的抽象边界，而不是复制平台实现。
- 证据：`docs/06-sandbox-design-targets.md` 将 AVF、Firecracker、gVisor、Flatpak、Crostini/Termina 拆成生命周期、控制面、文件桥、权限、VM 边界和宿主集成等可迁移抽象。
- 对后续开发的影响：后续 bridge、Android spike 和 UI 工作台不得把 QEMU、SSH、dropbear、JSch 或 WebView 细节写成长期核心协议。

## Finding 3

- 来源轮次：2026-05-01 `docs: narrow qemu guest workspace runtime`
- 结论：第一阶段主线应表述为 QEMU guest workspace runtime，而不是完整沙箱平台。
- 证据：`docs/07-qemu-guest-workspace-runtime.md` 将职责拆为 Android thin controller、QEMU runtime 和 Linux guest workspace，并明确 workspace 语义由 guest 内 agent、Linux 工具链和 git 承担。
- 对后续开发的影响：后续优先固化 QEMU 版本、guest 镜像、agent 启动和 `/workspace`，bridge 只保留 readiness/log/preview 等最小通道，直到 guest 工作机稳定。
