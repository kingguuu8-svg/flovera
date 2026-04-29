# QEMU

QEMU 是第一阶段的真 VM 主线。

后续这里应定义：

- guest 架构
- kernel 来源
- rootfs 或磁盘镜像挂载方式
- 网络模式
- 端口转发策略
- 日志输出方式
- 启停命令

第一阶段目标不是性能最优，而是保证 VM 概念和 Linux 工作区闭环成立。

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
host verifies commands over forwarded SSH
```

本阶段保留两条链路：

- `x86_64`：用宿主侧 Ubuntu 内核和 ext4 镜像快速验证 VM 边界。
- `aarch64`：用 Alpine `virt` 内核和 initramfs root 验证更接近 Android 目标架构的最小 Linux 工作空间。

当前 `aarch64` 选择 initramfs root，不是最终磁盘方案。原因是 Alpine netboot initramfs 在当前 QEMU 参数下没有直接挂载这个最小 ext4 rootfs；为了先证明 VM 边界，先把 rootfs 打成 initramfs，并只注入网络验证必需模块。
