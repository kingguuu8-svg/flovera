# vm

这里存放虚拟机承载层说明和配置。

当前主线：

```text
QEMU system VM
```

当前阶段目标是固定 QEMU runtime、guest 镜像、启动参数、网络转发和日志输出，使它成为一台可被 Android 控制的 Linux 工作机。

长期参考方向：

- AVF/pKVM
- crosvm
- proot fallback
- custom microVM backend

这些方向不进入第一阶段主线。
