# Current Round

## Round Goal

把仓库表述统一收缩为 QEMU guest workspace runtime。

## Why Now

当前讨论已经明确：第一阶段不是重新制作沙箱操作系统，而是把 QEMU 简化为“Android 可控制的一台预装 agent 的 Linux 工作机”。继续沿用完整 sandbox/bridge 平台叙事会放大个人开发者不需要承担的设计空间。

## Scope

- 新增 QEMU guest workspace runtime 主线文档。
- 更新 AGENTS、README、项目边界、系统架构和 bridge 说明。
- 调整沙箱设计文档，使其服从更窄的 QEMU guest 工作机口径。
- 更新 `docs/05-open-questions.md` 中与执行环境定位相关的判断。
- 更新本轮时间线和开发结论。

## Non-Goals

- 不修改 Android/QEMU/rootfs 实现。
- 不实现新的 bridge、agent 或 workspace 代码。
- 不改变已有验证脚本行为。
- 不提交 artifacts、APK、二进制、日志或截图。
- 不承诺多机型 Android 兼容。

## Acceptance Criteria

- [x] README 明确第一阶段主线是 QEMU guest workspace runtime。
- [x] 文档明确 Android 端只是薄控制层，QEMU 跑预配置 Linux guest，guest 内 agent 负责 workspace。
- [x] bridge 被降级为最小命令/日志/预览通道，不再表述成完整产品协议前置条件。
- [x] 文档明确 QEMU 不自动实现产品语义，产品语义来自 guest 镜像、agent、git 和固定启动配置。
- [x] 本轮只修改文档，不改变 Android/QEMU/rootfs 行为。

## Planned Commit

`docs: narrow qemu guest workspace runtime`

## Notes

- 本轮会包含此前已认可但未提交的 `docs/05-open-questions.md` 内容，并在其上做口径收缩。
- “沙箱”仍可作为安全边界俗称，但第一阶段正式主语改为 QEMU guest workspace runtime。
