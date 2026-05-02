# Current Round

## Round Goal

把 Android 控制面从混合日志框改成更接近 VPS 的终端视图。

## Why Now

真机验收已经证明 QEMU 生命周期和 SSH exec 链路能跑通，但实际使用时所有 QEMU、kernel、SSH、状态和用户命令输出都挤在同一个信息栏里。这个体验不符合“像使用一台 Linux/VPS”的第一阶段目标。

## Scope

- 将 `VmUiState` 的单一 `logText` 拆成用户 `terminalText` 和系统 `diagnosticsText`。
- 让用户命令输入、stdout、stderr、exit code 进入 Terminal。
- 让 QEMU 参数、kernel stdout/stderr、JSch 握手、readiness、生命周期细节进入 Diagnostics。
- 调整 Compose UI，使 Terminal 成为主视图，Diagnostics 成为次级信息区。
- 更新 Android spike 测试和验收文档。

## Non-Goals

- 不实现完整 PTY terminal、交互式 shell、键盘会话保持或光标控制。
- 不改 QEMU runtime、guest 镜像、SSH key 或端口策略。
- 不改真机 staging 脚本。
- 不提交 artifacts、APK、native libraries、截图或日志。

## Acceptance Criteria

- [x] UI 中 Terminal 是主输出区，不再被 kernel/QEMU/JSch 日志淹没。
- [x] Diagnostics 仍保留系统调试信息，方便排查问题。
- [x] `Run Command` 后 Terminal 显示命令、stdout/stderr 和 exit code。
- [x] 旧的 UI 测试更新到新的 Terminal/Diagnostics 文案。
- [x] `assembleDebug` 通过。
- [x] `testDebugUnitTest` 通过。
- [x] 本轮不提交 artifacts、APK 或 native runtime。

## Planned Commit

`android: separate terminal from diagnostics`

## Notes

- 本轮是“一次输入一条命令”的终端占位优化，不是完整 SSH PTY。
- 关键产品判断：用户默认看到的是 Linux 命令行结果；系统诊断只是后台可观察性，不应抢占主体验。
- 当前没有在线 Android 设备，因此本轮未做真机 VM 行为 preview。
