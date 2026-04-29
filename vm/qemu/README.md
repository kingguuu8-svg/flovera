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

本阶段用宿主侧 Ubuntu 内核做测试内核，目的是先验证 VM 边界和 guest userspace。后续 Android/aarch64 阶段需要替换为目标架构内核与镜像。
