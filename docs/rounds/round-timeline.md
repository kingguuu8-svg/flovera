# Round Timeline

本文件按时间记录开发轮次。每个非平凡 commit 应对应一条轮次记录。

## 2026-05-01 - pending - docs: add development round workflow

- 目标：建立按提交计算的开发轮次流程。
- 结果：新增 `docs/rounds/` 文档组，并把轮次规则接入仓库处理流程和根 README。
- 验收：确认本轮只修改文档，不修改 Android/QEMU/rootfs 行为，不提交 artifacts。
- 后续影响：后续非平凡 commit 必须先更新 `current-round.md`，结束时更新本时间线。
