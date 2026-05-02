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

## Finding 7

- 来源轮次：2026-05-01 `android: expose linux lifecycle controls`
- 结论：第一阶段 Android 控制面可以先使用“一次输入一条命令”的 SSH exec 作为 terminal 占位，不应在生命周期控制面未稳定前实现完整 PTY。
- 证据：本轮 `VmController` 复用已有 JSch SSH 通道，新增 terminal 命令输入和 `Run Command`；同时将 QMP pause/resume 接入生命周期按钮。
- 对后续开发的影响：后续若要做完整 terminal，应作为独立轮次处理 PTY、会话重连、键盘、滚动缓冲和状态恢复，不与 QEMU 生命周期控制混在一起。

## Finding 8

- 来源轮次：2026-05-01 `android: expose linux lifecycle controls`
- 结论：当前本机 emulator 不能作为稳定 UI 验收通道。
- 证据：`docs/verification/2026-05-01-android-linux-controls.md` 记录了 `small_phone` 无 online ADB device、`Medium_Phone_API_36.1` emulator 进程死亡，并在日志中出现多个 QEMU2 CPU/main loop hanging thread。
- 对后续开发的影响：下一轮 Android 行为验收应优先使用真机，或先单独修复 emulator 环境；不要把 app 功能问题和 emulator 启动问题混为一谈。

## Finding 9

- 来源轮次：2026-05-02 `android: verify linux controls on device`
- 结论：Android 侧 `Linux process started` 只能说明 QEMU 子进程已启动，不能说明 guest terminal 已可用。
- 证据：真机验收中第一次 `Run Command` 在 dropbear 启动前触发，JSch 连接超时；加入 SSH readiness 等待和重试后，`echo ready` 返回 `stdout: ready` 和 `Ready probe succeeded.`。
- 对后续开发的影响：后续 terminal、agent bridge、preview port 和健康检查都必须有显式 readiness 判断，不能复用进程启动状态。

## Finding 10

- 来源轮次：2026-05-02 `android: verify linux controls on device`
- 结论：Android 侧 `Shutdown` 应优先表达 Linux/QEMU 生命周期语义，而不是直接清空引用并硬杀进程。
- 证据：真机验收中旧 shutdown 路径会让 App 回到 launcher 且缺少 `Linux stopped.`；改为保留进程引用、优先 QMP `quit`、等待退出并兜底回写 `Stopped` 后，App 保持前台且无残留 QEMU 子进程。
- 对后续开发的影响：后续 pause/resume/shutdown/snapshot 这类生命周期操作应先走 QMP 或 guest 内协议，失败后才进入宿主进程兜底清理。

## Finding 11

- 来源轮次：2026-05-02 `android: separate terminal from diagnostics`
- 结论：第一阶段 Android UI 必须把用户终端和系统诊断分离，否则即使 VM 链路跑通，体验也会退化成调试日志面板。
- 证据：用户试用后反馈“日志和信息同时挤在信息栏里，和 VPS 体验相距甚远”；本轮将 `terminalText` 与 `diagnosticsText` 分离，并让 QEMU/kernel/JSch/QMP 信息进入 Diagnostics。
- 对后续开发的影响：后续新增日志、agent 状态、preview port 或错误信息时，必须先判断它属于用户终端、用户级状态，还是后台诊断，不能默认追加到主终端。

## Finding 12

- 来源轮次：2026-05-02 `android: keep lifecycle messages out of terminal`
- 结论：生命周期状态不应伪装成终端输出；Terminal 应只承载用户命令会话。
- 证据：真机 preview 的第一版截图中 Terminal 仍包含 `[system] Starting Linux` 和 `[system] Linux started`；移入 Diagnostics 后，Terminal 只剩 prompt、`echo ready`、`ready`、`[exit 0]` 和探针结果。
- 对后续开发的影响：Start/Pause/Resume/Shutdown、readiness 和 VM 监控应显示在状态栏或 Diagnostics 中，不应写入主 Terminal。
