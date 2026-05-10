# Current Round

## Round Goal

真机 preview Terminal/Diagnostics 分离后的命令行体验。

## Why Now

上一轮把 Terminal 和 Diagnostics 拆开，但当时没有在线设备，只做了构建和静态验证。现在真机已连接，应验证实际 VM 启动和命令输出是否真的接近 VPS 终端体验，并修正 preview 暴露的残余噪音。

## Scope

- 在真机上安装当前 Android spike APK。
- 启动 QEMU/Linux，执行默认 `echo ready`，再关闭 Linux。
- 检查 Terminal 是否只显示 prompt、用户命令、stdout/stderr 和 exit code。
- 检查 QEMU/kernel/JSch/QMP 信息是否只进入 Diagnostics。
- 必要时只修正 Terminal/Diagnostics 信息路由。

## Non-Goals

- 不实现完整 PTY terminal。
- 不改 QEMU runtime、guest 镜像、SSH key 或端口策略。
- 不改 staging 脚本。
- 不提交 artifacts、APK、native libraries、截图或日志。

## Acceptance Criteria

- [x] 真机 preflight、APK 安装和 app 私有输入 staging 通过。
- [x] `assembleDebug` 通过。
- [x] `testDebugUnitTest` 通过。
- [x] `Run Command` 后 Terminal 显示 `root@ai-linux:~# echo ready`、`ready` 和 `[exit 0]`。
- [x] Terminal 不显示 `[system] Starting Linux` 或 `[system] Linux started`。
- [x] Diagnostics 仍显示 QEMU/kernel/JSch/QMP 诊断信息。
- [x] `Shutdown` 后 App 保持前台，且无残留 QEMU 子进程。
- [x] 本轮不提交 artifacts、APK 或 native runtime。

## Planned Commit

`android: keep lifecycle messages out of terminal`

## Notes

- 真机：RMX3841，Android SDK 36，arm64，电量约 54-55%，USB 供电。
- 本轮仍是一次性 SSH exec，不是完整 VPS PTY；但主终端已经不再承担系统诊断职责。
