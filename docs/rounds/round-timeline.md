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
