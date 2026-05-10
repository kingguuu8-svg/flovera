# 实现路线区别

## 路线总览

| 路线 | 是否真 VM | Android 12+ 普适性 | 轻量性 | 兼容性 | 第一阶段判断 |
|---|---:|---:|---:|---:|---|
| `proot` | 否 | 高 | 高 | 中 | 备用验证 |
| QEMU | 是 | 中高 | 中 | 高 | 唯一第一阶段主线 |
| AVF/pKVM | 是 | 低 | 高 | 中 | 长期预留 |
| Buildroot | 取决于承载 | 高 | 极高 | 低 | 后期瘦身 |

## proot

`proot` 路线运行的是 Linux userland，不启动独立 Linux 内核。

优点：

- 快速
- 轻量
- 容易验证 shell、Python、git、curl
- 适合探索 AI 操作流程

缺点：

- 不是真 VM
- 隔离弱
- 仍然受 Android 进程模型影响明显
- 产品概念不如“Linux VM”干净

## QEMU

QEMU 路线在 Android App 进程中运行虚拟机。

优点：

- 是真 VM
- 可自带 kernel 和 rootfs
- 不依赖 AVF 系统权限
- 适合 Android 12+ 普通设备路线
- 可直接复用成熟 QEMU 引擎，把复杂度压到 guest 镜像和 agent

缺点：

- 无 KVM 时性能较弱
- 启动和资源消耗高于 proot
- Android 侧需要固定 runtime、启动参数和输入文件布局
- workspace 语义需要由 guest 内 agent、Linux 工具链和 git 承担

当前判断：

```text
QEMU runtime
  -> fixed Linux guest image
  -> guest agent + /workspace
  -> Android thin controller
```

第一阶段不再寻找第二套沙箱引擎。其他路线只作为参考或长期备选。

## Ubuntu cloud image

Ubuntu 24.04 arm64 cloud image 是第一阶段“像 VPS 一样使用 Linux”的优先 guest 镜像来源。

优点：

- 官方 arm64 cloud image 可直接作为 QEMU guest 基线。
- glibc 兼容性好。
- SSH、cloud-init、apt 和常见工具链更接近 VPS。
- 更适合后续安装 Hermes Agent。

缺点：

- 比 Alpine 大。
- 首次通过 apt 安装大量工具会很慢，尤其在 TCG 下。
- NoCloud seed 只注入 SSH key、`/workspace` 和 readiness marker；Node/agent 等工具链放到后续 guest 内 provisioning。

## AVF/pKVM

AVF 是 Android 官方虚拟化方向，适合长期关注。

优点：

- 隔离强
- 性能潜力好
- 系统级集成更正统

缺点：

- 不适合作为 Android 12+ 普通第三方 App 的第一阶段基础能力
- 设备、系统版本、权限和 API 可用性差异大
- 更偏安全 payload，不是天然的通用 Linux 工作区

## Alpine

Alpine 是第一阶段轻量对照路线。

优点：

- 体积小
- 有 `apk` 包管理器
- 默认工具链简单
- 适合最小 VM

缺点：

- 使用 musl libc
- 某些 Python/Node 原生依赖可能不如 Debian 省心

## Debian slim

Debian slim 是兼容性优先路线。

优点：

- glibc 兼容性强
- apt 生态成熟
- AI 默认生成的命令更常匹配 Debian/Ubuntu

缺点：

- 比 Alpine 重
- 依赖更容易膨胀

## Buildroot

Buildroot 适合后期固化 guest 镜像。

优点：

- 极小
- 完全可控
- 启动快

缺点：

- 默认没有通用包管理
- 初期迭代慢
- 对 AI 自主扩展不友好
