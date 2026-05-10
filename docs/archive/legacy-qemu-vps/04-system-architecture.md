# 系统架构

## 第一阶段抽象

```text
Android thin controller
  ├── input preparer
  ├── QEMU process manager
  ├── pause / resume controller
  ├── terminal view
  ├── log viewer
  ├── preview port opener
  └── restore trigger

QEMU runtime
  ├── qemu-system-aarch64
  ├── fixed launch arguments
  ├── kernel / firmware / initramfs or disk image
  ├── user networking and hostfwd
  └── serial / stdout / stderr output

Linux guest
  ├── /workspace
  ├── agent or Codex-compatible worker
  ├── shell
  ├── package manager
  ├── python/node/git/curl
  ├── lightweight service process
  └── workspace logs and git commits
```

## 模块职责

| 模块 | 职责 |
|---|---|
| `rootfs/` | 定义和构建 Linux 文件系统 |
| `vm/` | 定义虚拟机启动方式 |
| `bridge/` | 记录 Android 与 guest 的最小可替换控制通道 |
| `android/` | 放 Android 薄控制层和 spike |
| `scripts/` | 放可复现构建和验证脚本 |
| `examples/` | 放最小验证用例 |
| `artifacts/` | 放本地生成产物，不进 git |

## 第一阶段控制边界

第一阶段不先设计完整产品级 bridge。Android 只需要控制 VM 生命周期、显示日志、打开预览端口，并用一个可替换通道确认 guest 和 agent 可用。

```text
prepareInputs()
startVm()
pauseVm()
resumeVm()
stopVm()
openTerminal()
showLogs()
openPreviewPort(port)
runReadinessProbe()
restoreKnownImage()
```

终端是第一阶段主体验。Android 可以通过 SSH、串口或后续 guest agent 呈现 terminal，但用户侧只感知“正在操作 Linux 终端”，不感知具体通道。

文件读写、命令执行、项目日志和 git 版本优先由 guest 内 agent 和 `/workspace` 负责。Android 不重新实现一套 workspace 管理系统。

长期如果需要结构化自动化，再把 `bridge/` 扩展为 action/event 协议。扩展之前，QEMU guest 镜像和 agent 是主线，不把 Android 控制层做成新的操作系统。
