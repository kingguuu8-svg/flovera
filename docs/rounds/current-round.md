# Current Round

## Round Goal

固化第一阶段 Android 本地 Linux 电脑体验。

## Why Now

当前讨论已经明确：用户侧不应该感知 QEMU、SSH、端口转发、日志归集等底层细节。第一阶段要呈现的是“Android 上的一台本地 Linux 电脑”：可以开机、暂停、恢复、关机，并像连接 VPS 一样操作终端。

## Scope

- 新增第一阶段 Android Linux 电脑体验文档。
- 更新 README 推荐阅读顺序。
- 更新项目边界、系统架构和 QEMU guest runtime 文档中的用户侧目标。
- 更新 AGENTS 项目原则，避免后续 agent 把后台实现细节暴露成用户概念。
- 更新本轮时间线和开发结论。

## Non-Goals

- 不修改 Android/QEMU/rootfs 实现。
- 不实现 terminal UI、QMP、SSH session 或 pause/resume 代码。
- 不改变已有验证脚本行为。
- 不提交 artifacts、APK、二进制、日志或截图。
- 不承诺多机型 Android 兼容。

## Acceptance Criteria

- [x] 文档明确第一阶段用户侧只需要 Start、Pause、Resume、Shutdown、Terminal 和基础状态。
- [x] 文档明确日志、端口、网络、QEMU 参数、SSH/QMP 都是后台实现细节。
- [x] 文档明确终端体验应像 VPS：用户直接输入 Linux 命令，而不是操作 QEMU 管理面板。
- [x] README 能导航到第一阶段体验定义。
- [x] 本轮只修改文档，不改变 Android/QEMU/rootfs 行为。

## Planned Commit

`docs: define first stage linux computer ux`

## Notes

- 第一阶段体验定义优先级高于内部实现命名。
- 后续 Android spike 改造应从技术验证按钮转向用户语义按钮。
