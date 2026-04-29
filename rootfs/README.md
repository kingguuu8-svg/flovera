# rootfs

这里存放 Linux rootfs 的定义、构建脚本说明和发行版子目录。

当前主线是 `rootfs/alpine/`。

原则：

- rootfs 定义进 git
- rootfs 构建产物不进 git
- 大型产物输出到 `artifacts/`
- 每个新增包都要能解释为什么需要

