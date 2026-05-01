# Current Round

## Round Goal

改造 Android spike 为第一阶段 Linux 电脑控制面。

## Why Now

上一轮已经在 host 侧证明 Ubuntu arm64 guest 可以通过 QEMU 启动，并具备 SSH terminal、网络、`/workspace`、HTTP preview 和 QMP pause/resume。下一步应该把 Android spike 的用户语义从 `Start VM`/`Run echo ready` 推进到第一阶段定义的 `Start Linux`、`Pause`、`Resume`、`Shutdown` 和 terminal 命令输入。

## Scope

- 修改 `android/spike` 的 Compose UI 文案和布局。
- 在 `VmController` 中增加 Linux 生命周期状态、QMP `stop`/`cont` 控制和通用 SSH 命令执行。
- 更新 Android spike 测试和文档，使验收目标转向第一阶段 Linux 电脑控制面。
- 更新本轮时间线和开发结论。

## Non-Goals

- 不切换 Android guest 镜像到 Ubuntu cloud image；本轮继续适配已有 Android spike 输入。
- 不实现完整 PTY 交互终端、终端复用或会话保持。
- 不安装或打包 Hermes Agent。
- 不承诺模拟器可启动完整 QEMU guest；模拟器缺输入时仍应显示明确错误。
- 不重新制作 QEMU runtime 或提交 native libraries。
- 不提交 artifacts、APK、二进制、日志或截图。

## Acceptance Criteria

- [x] UI 显示 `Prepare Linux`、`Start Linux`、`Pause`、`Resume`、`Shutdown`、terminal 命令输入和日志区。
- [x] `VmController` 启动 QEMU 时暴露 QMP 控制通道，并能发送 `stop` 和 `cont`。
- [x] terminal 命令输入通过 SSH exec 执行任意命令，`echo ready` 作为默认命令仍可验收。
- [x] 模拟器或缺少输入时不崩溃，日志能明确说明缺失项。
- [x] Android spike build 或可替代的静态验收通过。
- [x] 本轮不提交 artifacts、APK 或 native runtime。

## Planned Commit

`android: expose linux lifecycle controls`

## Notes

- 本轮 terminal 先是“一次输入一条命令”的 SSH exec，不是完整 PTY。原因是 PTY 会话、键盘、滚动缓冲和重连需要单独设计，不应阻塞生命周期控制面落地。
- QMP 在 Android spike 中优先走 localhost TCP，避免 Java/Kotlin 直接处理 Unix domain socket。
- 验证记录见 `docs/verification/2026-05-01-android-linux-controls.md`。
- 模拟器 UI layout 未完成：本机 AVD 未进入 online ADB 状态，`Medium_Phone_API_36.1` 日志显示 emulator QEMU 线程挂起。这是环境阻塞，不是 APK 构建失败。
