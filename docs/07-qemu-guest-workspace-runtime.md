# QEMU Guest Workspace Runtime

本文把第一阶段路线收缩为一个更窄的目标：在 Android 上启动一台由 QEMU 承载的 Linux 工作机，并在 guest 内预装 agent 和工具链。

第一阶段不是重新制作操作系统，也不是先设计完整沙箱平台。更准确的定义是：

```text
Android App controls QEMU
  -> QEMU boots a fixed Linux guest image
  -> the guest runs an agent inside /workspace
  -> Android exposes start/pause/resume/shutdown and terminal
```

用户侧体验由 [第一阶段 Android Linux 电脑体验](08-first-stage-android-linux-computer-ux.md) 固化。简单说，用户打开 Android App 后，应感觉自己在使用一台本地 Linux 电脑，而不是在操作 QEMU 管理面板。

## 核心定位

`QEMU` 是第一阶段唯一成熟执行底座。它负责把 Linux guest 跑起来，并提供 VM 边界、网络转发、磁盘/镜像和串口/stdout 等基础通道。

`Linux guest image` 是真正的工作系统。它应该预装 agent、shell、git、curl、python、node、证书和最小服务能力，并暴露一个固定的 `/workspace`。

`Android App` 只是薄控制层。它负责准备输入、启动 QEMU、停止 QEMU、显示日志、打开预览端口、展示错误和触发恢复，不承担 Linux GUI，也不直接实现 workspace 语义。

`Agent` 运行在 guest 内。文件读写、命令执行、项目日志、git 版本和工作区状态优先交给 guest 内 agent 和 Linux 工具链处理。

## 三层结构

```text
Android thin controller
  ├── prepare inputs
  ├── start / stop QEMU
  ├── pause / resume QEMU
  ├── terminal session
  ├── show stdout / stderr
  ├── open forwarded preview ports
  └── trigger coarse restore actions

QEMU runtime
  ├── qemu-system-aarch64
  ├── fixed launch arguments
  ├── fixed kernel / firmware / initramfs or disk image
  ├── user networking and hostfwd
  └── serial / process logs

Linux guest workspace
  ├── /workspace
  ├── shell / python / node / git / curl
  ├── agent or Codex-compatible worker
  ├── project files and services
  └── git commits / workspace logs
```

## QEMU 负责什么

QEMU 负责虚拟机层能力：

- 启动 guest Linux。
- 隔离 guest 和 Android app 进程。
- 提供虚拟 CPU、内存、磁盘、串口和网络设备。
- 通过 user networking 暴露固定端口转发。
- 通过 stdout/stderr 或串口输出启动日志。
- 必要时提供 VM 级镜像或快照能力。

QEMU 不负责产品语义：

- 不知道什么是“项目”。
- 不知道什么是“AI 修改了一次文件”。
- 不自动归档日志到某个 agent step。
- 不自动生成用户可理解的版本记录。
- 不自动把端口解释成“预览对象”。

这些语义来自 guest 内 agent、`/workspace`、git 和 Android 展示层。

## 第一阶段最小控制面

第一阶段 Android 端只需要这些操作：

- `prepareInputs`
- `startVm`
- `pauseVm`
- `resumeVm`
- `stopVm`
- `openTerminal`
- `showLogs`
- `openPreviewPort`
- `runReadinessProbe`
- `restoreKnownImage`

`openTerminal` 是用户侧主体验。当前可以通过 SSH/JSch/dropbear 或串口实现，后续也可以替换成 HTTP endpoint、vsock 或 guest agent。用户不应该感知这些实现差异。

`runReadinessProbe` 只是确认 guest 和 agent 可用的后台控制通道，不是完整业务协议。

## Workspace 和版本

第一阶段的 workspace 语义优先放在 guest 内：

- `/workspace` 是 agent 的默认工作目录。
- 项目文件由 guest 内 agent 读写。
- 命令由 guest 内 shell 或 agent 执行。
- 项目版本优先用 guest 内 git commit 表示。
- Android 只展示“当前日志、预览端口、VM 状态、可恢复镜像”。

QEMU 级 snapshot 可以作为底层兜底，但不等同于用户理解的项目版本。项目版本应优先由 `/workspace` 内 git 管理。

## Bridge 的收缩定义

本项目第一阶段不需要先设计完整 bridge 平台。

`bridge` 在当前阶段只表示 Android 和 guest 之间的最小可替换通道：

- 发送 readiness probe。
- 读取启动和运行日志。
- 暴露预览端口。
- 将少量用户命令交给 guest 内 agent。

长期如果需要更强控制，再把 bridge 扩展为结构化协议。扩展之前，业务语义优先留在 guest 内 agent，而不是由 Android 重新实现一遍。

## 发布边界

第一阶段发布边界按个人开发者可维护范围定义：

- 一个固定 QEMU 版本。
- 一个固定 aarch64 Linux guest 镜像。
- 一个参考真机和一个模拟器验收链路。
- 不承诺多机型 Android 兼容。
- 不支持 Linux 桌面。
- 不支持 Docker 编排。
- 不支持多 agent 平台。

可交付目标是“可安装、可启动、可观察、可恢复的 QEMU Linux 工作机”，不是通用 Android 虚拟化产品。
