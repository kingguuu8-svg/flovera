# AI in Linux

本仓库用于实现一个面向 AI Agent 的 QEMU guest workspace runtime。

第一阶段不做通用 Android 虚拟化产品，也不重新制作沙箱操作系统。当前目标是把 QEMU 固定为成熟执行底座，启动一台预装 agent 和工具链的 Linux guest，让它像一台可被 Android 控制的轻量 Linux 工作机。

## 当前阶段目标

构建一个可重复启动的 QEMU Linux 工作机，使 AI 具备基础操作空间：

- QEMU 启动固定 aarch64 Linux guest
- guest 内预装 agent、shell、Git、curl、Python、Node 和证书
- agent 默认操作 `/workspace`
- guest 可联网访问 HTTPS
- guest 可启动本地服务并通过 QEMU 端口转发预览
- Android 侧可启动/停止 VM、读取日志、打开预览和触发恢复

## 非目标

- 不做 Linux 桌面
- 不做 GUI/X11/Wayland
- 不做多用户系统
- 不做 systemd 依赖
- 不做 Docker 编排
- 不做完整前端可视化工作台
- 不把 AVF 作为 Android 12+ 普适主线
- 不承诺多机型 Android 兼容

Android 侧的最小控制面会以独立 spike 工程推进，入口在 `android/spike`。它只负责资产释放、QEMU 进程控制和最小验收，不代表完整工作台。

## 仓库结构

```text
.
├── AGENTS.md
├── README.md
├── docs/
│   ├── 00-project-scope.md
│   ├── 01-repository-workflow.md
│   ├── 02-minimal-linux-spec.md
│   ├── 03-implementation-routes.md
│   ├── 04-system-architecture.md
│   ├── 05-open-questions.md
│   ├── 06-sandbox-design-targets.md
│   ├── 07-qemu-guest-workspace-runtime.md
│   ├── rounds/
│   │   ├── README.md
│   │   ├── current-round.md
│   │   ├── round-timeline.md
│   │   ├── development-findings.md
│   │   └── idea-backlog.md
│   └── decisions/
│       └── 0001-first-stage-alpine-qemu.md
├── rootfs/
│   ├── README.md
│   └── alpine/
│       └── README.md
├── vm/
│   ├── README.md
│   └── qemu/
│       └── README.md
├── bridge/
│   └── README.md
├── android/
│   └── README.md
├── scripts/
│   └── README.md
├── examples/
│   └── README.md
└── artifacts/
    └── README.md
```

## 路线摘要

第一阶段主线：

```text
Fixed Linux guest image
  ↓
QEMU system VM
  ↓
guest agent + /workspace + git
  ↓
Android thin controller
```

备用路线：

- `proot`：用于快速验证 Linux userland 工作流，但不是真 VM。
- AVF/pKVM：作为长期高性能方向预留，不作为 Android 12+ 普通 App 的第一阶段依赖。
- Buildroot：作为后期极限瘦身和固化 runtime 的方向，不作为探索期主线。

## 推荐阅读顺序

1. [项目边界](docs/00-project-scope.md)
2. [仓库处理流程](docs/01-repository-workflow.md)
3. [最小 Linux 规格](docs/02-minimal-linux-spec.md)
4. [实现路线区别](docs/03-implementation-routes.md)
5. [系统架构](docs/04-system-architecture.md)
6. [待解决问题](docs/05-open-questions.md)
7. [沙箱设计目标与参考案例](docs/06-sandbox-design-targets.md)
8. [QEMU Guest Workspace Runtime](docs/07-qemu-guest-workspace-runtime.md)
9. [开发轮次流程](docs/rounds/README.md)
10. [架构决策 0001](docs/decisions/0001-first-stage-alpine-qemu.md)

## 开发轮次流程

本仓库采用按提交计算的开发轮次流程：一个非平凡 commit 对应一个轮次。

轮次入口：

- [轮次规则](docs/rounds/README.md)
- [当前轮次](docs/rounds/current-round.md)
- [轮次时间线](docs/rounds/round-timeline.md)
- [开发结论](docs/rounds/development-findings.md)
- [想法 backlog](docs/rounds/idea-backlog.md)

## 第一阶段本地验证

当前仓库已经提供 Alpine rootfs 构建和验证脚本。Windows 宿主建议通过 WSL 执行：

```powershell
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/build-alpine-rootfs.sh --arch x86_64 --force"
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/verify-alpine-rootfs.sh --rootfs artifacts/rootfs/alpine-x86_64"
```

这一步验证的是最小 Linux userspace 能力，不等于完整 QEMU guest 工作机已完成。

## QEMU 边界验证

```powershell
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/build-qemu-image.sh --rootfs artifacts/rootfs/alpine-x86_64 --force"
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/verify-qemu-vm.sh --image artifacts/qemu/ai-linux-x86_64.ext4 --timeout 150"
```

这一步通过 QEMU 启动 ext4 镜像，并通过 SSH 跨 VM 边界重复验证 shell、网络、包管理、Python、Git、Node、`/workspace` 和 HTTP 服务。它是 guest 工作机运行时基线，不等于最终 agent 工作层。
