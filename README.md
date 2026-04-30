# AI in Linux

本仓库用于实现一个面向 AI Agent 的 Android 侧最小 Linux 执行环境。

第一阶段不做 APK、不做图形化界面、不做完整 AI 工作台。当前目标是先把一个可被 AI 操作的最小 Linux 系统定义清楚，并逐步做成可启动、可联网、可持久化、可执行命令的基础运行环境。

## 当前阶段目标

构建一个最小可用 Linux 系统，使 AI 具备基础操作空间：

- 可执行 shell 命令
- 可读写持久化工作目录
- 可联网访问 HTTPS
- 可安装或扩展基础软件包
- 可运行 Python/Node 小工具
- 可启动本地服务
- 可被 Android 侧或宿主控制层读取日志、状态和退出码

## 非目标

- 不做 Linux 桌面
- 不做 GUI/X11/Wayland
- 不做多用户系统
- 不做 systemd 依赖
- 不做 Docker 编排
- 不做 APK 打包
- 不做完整前端可视化工作台
- 不把 AVF 作为 Android 12+ 普适主线

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
Alpine minimal rootfs
  ↓
QEMU system VM
  ↓
shell / file / network / service / log 基础能力
  ↓
后续再接 Android App 控制层
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
7. [架构决策 0001](docs/decisions/0001-first-stage-alpine-qemu.md)

## 第一阶段本地验证

当前仓库已经提供 Alpine rootfs 构建和验证脚本。Windows 宿主建议通过 WSL 执行：

```powershell
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/build-alpine-rootfs.sh --arch x86_64 --force"
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/verify-alpine-rootfs.sh --rootfs artifacts/rootfs/alpine-x86_64"
```

这一步验证的是最小 Linux userspace 能力，不等于 Android APK 或 QEMU VM 已完成。QEMU 启动链路是下一阶段。

## QEMU 边界验证

```powershell
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/build-qemu-image.sh --rootfs artifacts/rootfs/alpine-x86_64 --force"
wsl bash -lc "cd /mnt/e/main/ai-in-linux && bash scripts/verify-qemu-vm.sh --image artifacts/qemu/ai-linux-x86_64.ext4 --timeout 150"
```

这一步通过 QEMU 启动 ext4 镜像，并通过 SSH 跨 VM 边界重复验证 shell、网络、包管理、Python、Git、Node、`/workspace` 和 HTTP 服务。
