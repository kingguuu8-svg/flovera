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

## Finding 4

- 来源轮次：2026-05-01 `docs: define first stage linux computer ux`
- 结论：第一阶段用户侧体验应是一台 Android 本地 Linux 电脑，而不是 QEMU 管理面板。
- 证据：`docs/08-first-stage-android-linux-computer-ux.md` 将用户能力限定为 Start Linux、Pause、Resume、Shutdown、Terminal 和基础状态，并把 QEMU、SSH、QMP、端口、日志和网络归为后台实现细节。
- 对后续开发的影响：后续 Android UI、控制器和验收流程应优先围绕 terminal 和 Linux 生命周期设计，不把技术验证按钮作为用户产品语义。

## Finding 5

- 来源轮次：2026-05-01 `guest: add ubuntu cloud runtime pipeline`
- 结论：第一阶段 guest 首启应优先快而稳定，不能把 Node/agent 等工具链安装放进 cloud-init 阻塞路径。
- 证据：`docs/verification/2026-05-01-ubuntu-cloud-guest.md` 记录了 Ubuntu arm64 cloud guest 在不首启安装大包的情况下通过 SSH terminal、HTTPS、Python、Git、`/workspace`、HTTP preview 和 QMP pause/resume 验证；Node 缺失被记录为 optional。
- 对后续开发的影响：后续工具链扩展应走 guest 内 provisioning 或预烘焙镜像版本，不应拖慢或破坏第一阶段 Start Linux 到 terminal 可用的主链路。

## Finding 6

- 来源轮次：2026-05-01 `guest: add ubuntu cloud runtime pipeline`
- 结论：QEMU 验证运行时文件应放在 Linux 原生文件系统中，不能默认把 overlay、QMP socket 和 seed 运行时副本放在 Windows 挂载盘上。
- 证据：验证脚本将运行时 image copy、seed copy、qcow2 overlay、PID file 和 QMP socket 放入 WSL `/tmp` 后，host 侧 Ubuntu arm64 guest 验收通过。
- 对后续开发的影响：Android 侧也应使用 app 私有原生路径承载运行时文件，不要把 VM 热路径放在慢速或语义不完整的外部/桥接文件系统上。
