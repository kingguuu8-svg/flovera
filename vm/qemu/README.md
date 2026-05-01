# QEMU

QEMU 是第一阶段唯一执行底座。

后续这里应定义：

- QEMU runtime 来源和固定版本
- guest 架构
- kernel 来源
- rootfs 或磁盘镜像挂载方式
- 网络模式
- 端口转发策略
- 日志输出方式
- 启停命令
- guest agent 启动方式

第一阶段目标不是性能最优，而是把 QEMU 固化成一台可重复启动的 Linux 工作机。workspace、文件、命令、项目日志和 git 版本优先由 guest 内 agent 处理。

## 当前启动链路

```text
Ubuntu test kernel
  ↓
QEMU x86_64 TCG
  ↓
ext4 disk image copied from Alpine rootfs
  ↓
/usr/local/sbin/ai-vm-init
  ↓
DHCP + dropbear SSH
  ↓
host verifies commands over forwarded SSH
```

```text
Alpine virt kernel
  ↓
QEMU aarch64 TCG
  ↓
generated initramfs root copied from Alpine aarch64 rootfs
  ↓
/usr/local/sbin/ai-vm-init
  ↓
static QEMU usernet address + dropbear SSH
  ↓
host verifies guest readiness over forwarded SSH
```

本阶段保留两条链路：

- `x86_64`：用宿主侧 Ubuntu 内核和 ext4 镜像快速验证 VM 边界。
- `aarch64`：用 Alpine `virt` 内核和 initramfs root 验证更接近 Android 目标架构的最小 Linux 工作空间。

当前 `aarch64` 选择 initramfs root，不是最终磁盘方案。原因是 Alpine netboot initramfs 在当前 QEMU 参数下没有直接挂载这个最小 ext4 rootfs；为了先证明 VM 边界，先把 rootfs 打成 initramfs，并只注入网络验证必需模块。

## 收缩后的主线

```text
qemu-system-aarch64
  ↓
fixed Linux guest image
  ↓
agent starts in /workspace
  ↓
Android observes logs and preview ports
```

SSH/JSch/dropbear 当前只是 readiness probe 和验收通道。它可以继续用于 spike，但不代表长期业务协议。
