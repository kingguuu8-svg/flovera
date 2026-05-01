# Current Round

## Round Goal

新增 Ubuntu arm64 guest 工作机流水线。

## Why Now

第一阶段目标已经收缩为 Android 上的一台本地 Linux 电脑。继续沿用自制 Alpine rootfs 会增加镜像设计成本；更短路径是复用官方 Ubuntu arm64 cloud image，先在 host QEMU 中验证 terminal、网络、workspace 和暂停/恢复能力。Node/agent 等工具链安装放到后续 guest 内 provisioning，不阻塞首启验收。

## Scope

- 新增 Ubuntu arm64 cloud image 下载脚本。
- 新增 NoCloud seed 生成脚本，用于注入 SSH key、`/workspace` 和 readiness marker。
- 新增 Ubuntu arm64 QEMU guest 验证脚本。
- 更新脚本文档和 QEMU/guest 文档，说明 Ubuntu cloud image 路线。
- 更新本轮时间线和开发结论。

## Non-Goals

- 不修改 Android/QEMU/rootfs 实现。
- 不实现 Android terminal UI。
- 不安装或打包 Hermes Agent 到仓库。
- 不提交下载得到的 cloud image、seed、overlay、日志或截图。
- 不提交 artifacts、APK、二进制、日志或截图。
- 不承诺多机型 Android 兼容。

## Acceptance Criteria

- [x] 脚本能下载并校验 Ubuntu 24.04 arm64 cloud image。
- [x] 脚本能生成 NoCloud seed，注入 SSH key、`/workspace` 和 readiness marker。
- [x] 验证脚本能启动 arm64 QEMU guest，并检查 SSH terminal、HTTPS、Python、Git、`/workspace`、HTTP 预览。
- [x] 验证脚本覆盖 QMP `stop` / `cont` / `query-status`。
- [x] 本轮不提交 artifacts 或 Android/QEMU 大二进制。

## Planned Commit

`guest: add ubuntu cloud runtime pipeline`

## Notes

- 本轮先完成 host 侧 guest 基线；Android UI 改造放到下一轮。
- Hermes Agent 安装先作为后续 provisioning 目标，不把第三方安装脚本直接固化到第一版验证脚本。
- 验证记录见 `docs/verification/2026-05-01-ubuntu-cloud-guest.md`。
- 首启 cloud-init 不再联网安装 Node；Node 缺失时记录为 optional，后续通过 guest 内 provisioning 处理。
