# Round Timeline

本文件按时间记录开发轮次。每个非平凡 commit 应对应一条轮次记录。

## 2026-05-01 - pending - docs: add development round workflow

- 目标：建立按提交计算的开发轮次流程。
- 结果：新增 `docs/rounds/` 文档组，并把轮次规则接入仓库处理流程和根 README。
- 验收：确认本轮只修改文档，不修改 Android/QEMU/rootfs 行为，不提交 artifacts。
- 后续影响：后续非平凡 commit 必须先更新 `current-round.md`，结束时更新本时间线。

## 2026-05-01 - pending - docs: define sandbox design targets

- 目标：落实 `05-open-questions.md` 第二点，定义沙箱设计目标和参考案例。
- 结果：新增 `docs/06-sandbox-design-targets.md`，把 AVF、Firecracker、gVisor、Flatpak、Crostini/Termina 的可借鉴抽象映射到当前 Android 普通 App 约束。
- 验收：确认本轮只修改文档，不修改 Android/QEMU/rootfs 行为，不提交 artifacts。
- 后续影响：后续 bridge、Android spike 和 UI 工作台设计必须优先复用这些边界，而不是自创隐式协议。

## 2026-05-01 - pending - docs: narrow qemu guest workspace runtime

- 目标：把仓库表述从完整沙箱平台收缩为 QEMU guest workspace runtime。
- 结果：新增 `docs/07-qemu-guest-workspace-runtime.md`，并更新 README、项目边界、系统架构、沙箱边界、bridge、rootfs、QEMU 和 ADR 文档。
- 验收：确认本轮只修改文档，不修改 Android/QEMU/rootfs 行为，不提交 artifacts。
- 后续影响：后续开发优先固化 QEMU runtime、guest 镜像、agent 和 `/workspace`，不先扩展完整 bridge/action 平台。

## 2026-05-01 - pending - docs: define first stage linux computer ux

- 目标：固化第一阶段用户侧体验为 Android 上的一台本地 Linux 电脑。
- 结果：新增 `docs/08-first-stage-android-linux-computer-ux.md`，并把 README、AGENTS、项目边界、系统架构、QEMU runtime、bridge 和 QEMU 模块说明接入该体验定义。
- 验收：确认本轮只修改文档，不修改 Android/QEMU/rootfs 行为，不提交 artifacts。
- 后续影响：后续 Android spike 改造应优先实现 Start Linux、Pause、Resume、Shutdown、Terminal 和基础状态，而不是暴露 QEMU/SSH/QMP 等内部概念。

## 2026-05-01 - pending - guest: add ubuntu cloud runtime pipeline

- 目标：新增官方 Ubuntu 24.04 arm64 cloud image 的 guest 工作机流水线。
- 结果：新增镜像下载、NoCloud seed 生成和 host 侧 QEMU 验证脚本，并把 Ubuntu guest 路线接入 README、QEMU 文档和第一阶段体验文档。
- 验收：`scripts/verify-ubuntu-cloud-vm.sh` 通过 SSH terminal、cloud-init、`aarch64`、HTTPS、Python、Git、`/workspace`、HTTP preview 和 QMP pause/resume 检查；验证记录见 `docs/verification/2026-05-01-ubuntu-cloud-guest.md`。
- 后续影响：下一轮可以在 Android 侧接入 terminal 和 Linux 生命周期按钮；Node/agent 安装应作为 guest 内 provisioning，而不是首启 cloud-init 阻塞项。

## 2026-05-01 - pending - android: expose linux lifecycle controls

- 目标：把 Android spike 从技术验证按钮改造成第一阶段 Linux 电脑控制面。
- 结果：UI 暴露 `Prepare Linux`、`Start Linux`、`Pause`、`Resume`、`Shutdown`、terminal 命令输入和日志区；`VmController` 增加 QMP TCP pause/resume 和通用 SSH exec。
- 验收：`assembleDebug` 和 `testDebugUnitTest` 通过；`android describe` 仍受 Windows launcher `CreateProcess error=193` 阻塞；模拟器 layout 验收被本机 AVD QEMU 线程挂起阻塞。验证记录见 `docs/verification/2026-05-01-android-linux-controls.md`。
- 后续影响：下一轮应优先做真机 UI 行为验收，或者先修复本机 emulator 环境；完整 PTY terminal 不应塞进本轮控制面。
