# Current Round

## Round Goal

真机验收 Android Linux 控制面。

## Why Now

上一轮已经把 Android spike 改造成 `Prepare Linux`、`Start Linux`、`Pause`、`Resume`、`Shutdown` 和 terminal 命令输入的控制面；模拟器受本机 AVD QEMU 线程挂起影响，无法作为可靠行为验收通道。当前真机已连接，应直接验证真实 Android 环境中的 QEMU 启动、SSH 命令、QMP 暂停/恢复和清理。

## Scope

- 构建当前 `android/spike` debug APK。
- 使用 `scripts/verify-android-spike-device.sh` 安装 APK，并把输入文件写入 app 私有目录。
- 使用 Android CLI / adb 验证 UI 控件、日志和真实按钮行为。
- 修复真机验收暴露的 Android spike 小问题：shutdown 收尾和 SSH readiness 等待。
- 更新本轮时间线、验收记录和可继承结论。

## Non-Goals

- 不 root、不解锁、不使用 fastboot、不改系统分区、不读取用户数据。
- 不切换 Android guest 镜像到 Ubuntu cloud image。
- 不实现完整 PTY terminal。
- 不重新制作 QEMU runtime，不提交 native libraries。
- 不提交 artifacts、APK、二进制、日志或截图。

## Acceptance Criteria

- [x] 真机 preflight 通过：Android 12+、arm64、`run-as` 可用；低电量由用户明确允许覆盖。
- [x] APK 安装和 app 私有输入 staging 通过。
- [x] `android layout` 能看到 `Prepare Linux`、`Start Linux`、`Pause`、`Resume`、`Shutdown`、`Terminal command`、`Run Command`。
- [x] 点击 `Start Linux` 后日志显示 QEMU/Linux process 启动。
- [x] 点击 `Run Command` 后 `echo ready` 返回 `Ready probe succeeded.`。
- [x] 点击 `Pause` / `Resume` 后日志显示 QMP 成功响应。
- [x] 点击 `Shutdown` 后 App 保持前台，日志显示 QMP quit 和 `Linux stopped.`。
- [x] 点击 `Shutdown` 后无残留 QEMU/VM 子进程。
- [x] 本轮不提交 artifacts、APK 或 native runtime。

## Planned Commit

`android: verify linux controls on device`

## Notes

- 本轮所有设备操作限制在 debug APK、app 私有目录和 app 子进程内。
- 真机电量低于原脚本阈值，但用户明确要求忽视电量问题；脚本使用 `--allow-low-battery`。
- 验收暴露两个真实问题：shutdown 不能提前清空进程引用并硬杀；terminal 不能假设 SSH 在 QEMU process started 后立即可用。
