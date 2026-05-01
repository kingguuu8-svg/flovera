# 项目边界

## 核心概念

本项目的第一目标是在 Android 手机上为 AI Agent 提供一台 QEMU 承载的最小 Linux 工作机。

这个 Linux 工作机不是给人使用的桌面系统，而是给 AI 使用的 guest workspace。它需要提供命令行、文件系统、网络、进程、日志、git 版本和持久化能力。

## 第一阶段目标

第一阶段只证明一件事：

```text
一个固定的 QEMU Linux guest，可以作为 AI 的底层工作机。
```

必须具备：

- shell 命令入口
- 持久化 `/workspace`
- guest 内 agent 或 Codex 兼容 worker
- HTTPS 网络访问
- 基础包管理或扩展能力
- Python 脚本运行能力
- Git 版本能力
- 本地 HTTP 服务启动能力
- 日志和退出码可被宿主读取
- Android 侧可启动、停止、观察和打开预览

## 第一阶段不做

- 不做可发布 APK
- 不做完整 Android UI 工作台
- 不做可视化对象面板
- 不做通用 Android 虚拟化产品
- 不做 Linux GUI
- 不做完整桌面发行版
- 不做多用户系统
- 不做 systemd
- 不做 Docker
- 不做多 Agent
- 不做插件系统

## 后续阶段关系

```text
阶段 1：最小 Linux guest
阶段 2：QEMU 启动和网络/存储验证
阶段 3：预装 agent、工具链和 /workspace
阶段 4：Android 薄控制层
阶段 5：日志、预览和恢复体验
阶段 6：可视化对象工作台
```
